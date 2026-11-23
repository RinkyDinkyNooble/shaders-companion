package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The main thread's half of the far store: which sections wait to be copied, and the copying itself within a budget
 * of microseconds per frame. Sections are kept per chunk column with a due time: a chunk that loads is due 1 second
 * later (its light arrives after its blocks), a block or light change 1 second after the last change, at most 5 seconds
 * after the first (so a section that never stops changing is still copied). Each frame, due columns go into rings by
 * distance from the camera's chunk, and their sections are copied nearest first (nearest ring, then nearest section
 * height) until the budget is spent; a copy goes to the worker at once. Main thread only.
 */
final class FarCapture {
    private static final long DELAY = 1_000_000_000L;
    private static final long MAX_DELAY = 5_000_000_000L;
    /**
     * Main-thread time per frame for copying the dirty sections of chunks that unload (their last copy) before the rest
     * are let go: with the frame's own budget it stays under the 1 ms a frame the design allows (a teleport right after
     * joining unloads a render distance of chunks never copied; the client barely showed them).
     */
    private static final long UNLOAD_ALLOWANCE = 800_000L;
    private static final int RINGS = 96;

    /** A chunk column with sections waiting to be copied. */
    private static final class Column {
        final int x;
        final int z;
        final long[] dirty;
        int dirtyCount;
        long firstMark;
        long due;
        /** The due time it was queued with (the waiting queue's order); due may have moved later since. */
        long queuedDue;
        boolean waiting;
        boolean ready;
        boolean removed;

        Column(int x, int z, int sections) {
            this.x = x;
            this.z = z;
            this.dirty = new long[(sections + 63) >> 6];
        }
    }

    private final ClientLevel level;
    private final int generation;
    private final Consumer<CapturedSection> sink;
    private final int minSection;
    private final int sections;
    private final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<Column> waiting = new ArrayDeque<>();
    @SuppressWarnings("unchecked")
    private final ArrayList<Column>[] rings = new ArrayList[RINGS];
    private int ringX = Integer.MIN_VALUE;
    private int ringZ;
    private int readyColumns;

    /** Sections waiting to be copied. */
    int pendingSections;
    long sectionsCaptured;
    /** Dirty sections of unloading chunks not copied because the frame's unload allowance was spent. */
    long sectionsLost;
    private long captureNanos;
    private long unloadNanosThisFrame;
    /** Main-thread time spent outside {@link #frame} since the last frame (copies on unload), counted into the next. */
    private long outsideNanos;

    // The frame statistics, over windows of about a second
    private long windowStart;
    private long windowNanos;
    private long windowMax;
    private int windowFrames;
    double frameMicrosAverage;
    double frameMicrosMax;
    double sectionMicros;

    // The join: from the start until the backlog first empties
    private long joinStart;
    private long joinSections;
    private int joinFrames;
    private long joinNanos;
    private long joinMax;

    FarCapture(ClientLevel level, int generation, Consumer<CapturedSection> sink) {
        this.level = level;
        this.generation = generation;
        this.sink = sink;
        this.minSection = level.getMinSection();
        this.sections = level.getSectionsCount();
        for (int i = 0; i < RINGS; i++) {
            rings[i] = new ArrayList<>();
        }
        joinStart = System.nanoTime();
    }

    ClientLevel level() {
        return level;
    }

    /** A section's blocks or light changed. */
    void markSection(int x, int y, int z) {
        int index = y - minSection;
        if (index < 0 || index >= sections) {
            return;
        }
        Column column = column(x, z);
        mark(column, index);
        schedule(column, System.nanoTime());
    }

    /** A chunk loaded: all its sections. */
    void markChunk(int x, int z) {
        Column column = column(x, z);
        for (int i = 0; i < sections; i++) {
            mark(column, i);
        }
        schedule(column, System.nanoTime());
    }

    private Column column(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        Column column = columns.get(key);
        if (column == null) {
            column = new Column(x, z, sections);
            column.firstMark = System.nanoTime();
            columns.put(key, column);
        }
        return column;
    }

    private void mark(Column column, int index) {
        long bit = 1L << (index & 63);
        if ((column.dirty[index >> 6] & bit) == 0) {
            column.dirty[index >> 6] |= bit;
            column.dirtyCount++;
            pendingSections++;
        }
    }

    private void schedule(Column column, long now) {
        column.due = Math.min(now + DELAY, column.firstMark + MAX_DELAY);
        if (!column.waiting && !column.ready) {
            column.waiting = true;
            column.queuedDue = column.due;
            waiting.addLast(column);
        }
    }

    /** A chunk unloads: its dirty sections are copied now, the last copy the client will see (within the frame's allowance). */
    void chunkUnloaded(LevelChunk chunk) {
        Column column = columns.remove(ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z));
        if (column == null) {
            return;
        }
        column.removed = true;
        long start = System.nanoTime();
        for (int i = 0; i < sections; i++) {
            if ((column.dirty[i >> 6] & 1L << (i & 63)) != 0) {
                if (unloadNanosThisFrame + System.nanoTime() - start < UNLOAD_ALLOWANCE) {
                    capture(chunk, i);
                } else {
                    sectionsLost++;
                }
            }
        }
        pendingSections -= column.dirtyCount;
        column.dirtyCount = 0;
        long nanos = System.nanoTime() - start;
        unloadNanosThisFrame += nanos;
        outsideNanos += nanos;
    }

    /** Copies due sections nearest the camera first until the budget is spent (called once a frame). */
    void frame(int cameraSectionX, int cameraSectionY, int cameraSectionZ, long budgetNanos) {
        long start = System.nanoTime();
        unloadNanosThisFrame = 0;
        int capturedBefore = (int) sectionsCaptured;
        if (cameraSectionX != ringX || cameraSectionZ != ringZ) {
            rebucket(cameraSectionX, cameraSectionZ);
        }
        while (!waiting.isEmpty() && waiting.peekFirst().queuedDue <= start) {
            Column column = waiting.pollFirst();
            column.waiting = false;
            if (column.removed) {
                continue;
            }
            if (column.due > start) {
                // Marked again since it was queued: back in line with its new due time
                column.waiting = true;
                column.queuedDue = column.due;
                waiting.addLast(column);
                continue;
            }
            addReady(column);
        }
        // Copies at unload since the last frame came out of this frame's budget
        serve(cameraSectionY, start, budgetNanos - outsideNanos);
        long nanos = System.nanoTime() - start + outsideNanos;
        outsideNanos = 0;
        account(nanos, (int) sectionsCaptured - capturedBefore, start);
    }

    private void serve(int cameraSectionY, long start, long budgetNanos) {
        if (readyColumns == 0) {
            return;
        }
        int cameraIndex = Math.max(0, Math.min(sections - 1, cameraSectionY - minSection));
        for (int d = 0; d < RINGS && readyColumns > 0; d++) {
            ArrayList<Column> ring = rings[d];
            while (!ring.isEmpty()) {
                if (System.nanoTime() - start >= budgetNanos) {
                    return;
                }
                Column column = ring.get(ring.size() - 1);
                if (column.removed || column.due > start) {
                    ring.remove(ring.size() - 1);
                    readyColumns--;
                    column.ready = false;
                    if (!column.removed) {
                        // Marked again after it became due: wait for its light to settle
                        column.waiting = true;
                        column.queuedDue = column.due;
                        waiting.addLast(column);
                    }
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunk(column.x, column.z, false);
                if (chunk == null) {
                    // Gone without an unload event (the render distance shrank): nothing left to copy
                    drop(ring, column);
                    continue;
                }
                // A later change waits its delay from now, not from the first mark of this round
                column.firstMark = start;
                // Sections nearest the camera's height first
                for (int step = 0; step < 2 * sections && column.dirtyCount > 0; step++) {
                    int i = cameraIndex + ((step & 1) == 0 ? step >> 1 : -(step + 1 >> 1));
                    if (i < 0 || i >= sections || (column.dirty[i >> 6] & 1L << (i & 63)) == 0) {
                        continue;
                    }
                    if (System.nanoTime() - start >= budgetNanos) {
                        return;
                    }
                    column.dirty[i >> 6] &= ~(1L << (i & 63));
                    column.dirtyCount--;
                    pendingSections--;
                    capture(chunk, i);
                }
                if (column.dirtyCount == 0) {
                    drop(ring, column);
                }
            }
        }
    }

    private void drop(ArrayList<Column> ring, Column column) {
        ring.remove(ring.size() - 1);
        readyColumns--;
        column.ready = false;
        column.removed = true;
        pendingSections -= column.dirtyCount;
        column.dirtyCount = 0;
        columns.remove(ChunkPos.asLong(column.x, column.z));
    }

    private void capture(LevelChunk chunk, int index) {
        long start = System.nanoTime();
        sink.accept(CapturedSection.capture(level, chunk, index, generation));
        long nanos = System.nanoTime() - start;
        captureNanos += nanos;
        sectionsCaptured++;
        sectionMicros = sectionMicros == 0 ? nanos / 1.0e3 : sectionMicros * 0.99 + nanos / 1.0e3 * 0.01;
    }

    private void addReady(Column column) {
        int d = Math.min(RINGS - 1, Math.max(Math.abs(column.x - ringX), Math.abs(column.z - ringZ)));
        rings[d].add(column);
        column.ready = true;
        readyColumns++;
    }

    /** The camera entered another chunk column: the ready columns sorted into rings around it again. */
    private void rebucket(int x, int z) {
        ringX = x;
        ringZ = z;
        if (readyColumns == 0) {
            return;
        }
        ArrayList<Column> all = new ArrayList<>(readyColumns);
        for (ArrayList<Column> ring : rings) {
            all.addAll(ring);
            ring.clear();
        }
        readyColumns = 0;
        for (Column column : all) {
            addReady(column);
        }
    }

    private void account(long nanos, int captured, long now) {
        windowNanos += nanos;
        windowMax = Math.max(windowMax, nanos);
        windowFrames++;
        if (now - windowStart >= 1_000_000_000L) {
            frameMicrosAverage = windowNanos / 1.0e3 / Math.max(1, windowFrames);
            frameMicrosMax = windowMax / 1.0e3;
            windowStart = now;
            windowNanos = 0;
            windowMax = 0;
            windowFrames = 0;
        }
        if (joinStart != 0) {
            joinFrames++;
            joinNanos += nanos;
            joinMax = Math.max(joinMax, nanos);
            joinSections += captured;
            if (pendingSections == 0 && joinSections > 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion far store: captured {} sections in {} ms over {} frames, {} ms of main-thread time (at most {} us in a frame, {} us a section; {} lost at unload)",
                        joinSections, (now - joinStart) / 1_000_000, joinFrames, String.format("%.1f", joinNanos / 1.0e6), joinMax / 1000,
                        String.format("%.1f", sectionMicros), sectionsLost);
                joinStart = 0;
            }
        }
    }

    /** Microseconds of main-thread time per section copied, over everything so far. */
    double averageSectionMicros() {
        return sectionsCaptured == 0 ? 0 : captureNanos / 1.0e3 / sectionsCaptured;
    }
}
