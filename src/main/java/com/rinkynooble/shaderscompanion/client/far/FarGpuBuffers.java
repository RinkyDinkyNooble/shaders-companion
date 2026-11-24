package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.Config;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.Map;
import java.util.TreeMap;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;

/**
 * The far terrain's GPU buffers (research/lod/M2-PLAN.md, "GPU"; render thread only), owned by {@link FarRenderer}:
 * <ul>
 * <li><b>Geometry</b>: one buffer of uints holding every uploaded mesh as its quads (2 uints a quad, as
 * {@link NodeMesh#quads}) followed by its palette (1 uint an entry), in one allocation per mesh from a first-fit
 * sub-allocator over runs of {@link #UNIT_UINTS} uints. It starts at {@link #FIRST_GEOMETRY_BYTES} and doubles (a copy
 * on the GPU) when an allocation doesn't fit, up to {@code farGpuMb}.</li>
 * <li><b>Node records</b>: {@link #RECORD_INTS} ints per record slot (layout at {@link #record}); a slot whose level
 * is -1 is free. Slots are reused; the selection pass walks slots 0 to {@link #recordHigh}.</li>
 * <li><b>Colour tables</b> ({@link StateColors}): its colours, info and biome colours as three buffers, uploaded
 * whole when its version changes (a few kilobytes).</li>
 * <li>The <b>instance ids</b>: one int per record slot (its index), the draw's per-instance attribute, so a draw
 * command's base instance names its record.</li>
 * </ul>
 * Meshes are uploaded in the order they arrive ({@link #markDirty}), within a byte budget a frame; once a mesh is on
 * the GPU the render thread keeps only its {@link NodeMesh#stub}.
 */
final class FarGpuBuffers {
    static final int RECORD_INTS = 48;
    static final int RECORD_BYTES = RECORD_INTS * 4;
    /** The allocation unit of the geometry buffer: 64 uints, 256 bytes (32 quads). */
    static final int UNIT_UINTS = 64;
    static final long UNIT_BYTES = UNIT_UINTS * 4L;
    static final long FIRST_GEOMETRY_BYTES = 32L << 20;
    static final int FIRST_RECORDS = 4096;
    /** Most bytes uploaded a frame, and most render-thread time spent on uploads (whichever comes first). */
    static final long UPLOAD_BYTES_PER_FRAME = 4L << 20;
    static final long UPLOAD_NANOS_PER_FRAME = 1_500_000L;

    /** One uploaded mesh: its record slot and its geometry run. */
    private record Slot(int record, int unit, int units, int quads) {
    }

    int geometry;
    long geometryBytes;
    int records;
    int recordCapacity;
    int instanceIds;
    /** Grows with every new instance id buffer (a deleted buffer's name can come back for a new one). */
    int instanceIdsGeneration;
    int colours;
    int info;
    int biomes;
    /** Record slots below this may be in use (the selection's invocation count). */
    int recordHigh;
    private long coloursVersion = -1;
    private int[] coloursUploaded;
    private int[] infoUploaded;
    private int[] biomesUploaded;
    /** States and biomes whose colours are uploaded (the shaders draw others grey). */
    int stateCount;
    int biomeCount;

    /** Free geometry runs, start unit to length in units. */
    private final TreeMap<Integer, Integer> free = new TreeMap<>();
    private long unitsUsed;
    private final Long2ObjectOpenHashMap<Slot> slots = new Long2ObjectOpenHashMap<>();
    private final IntArrayList freeRecords = new IntArrayList();
    /** Nodes whose mesh on the GPU differs from the render thread's, in arrival order. */
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    private final int[] record = new int[RECORD_INTS];
    /**
     * Frames to wait before uploading again after the geometry buffer grew: a write into the new buffer while the GPU
     * still copies the old one into it waited 10 to 40 ms for the copy (measured), a few frames later it doesn't.
     */
    private int pauseFrames;
    private static final int PAUSE_AFTER_GROWTH = 3;
    /** {@link #put}: the buffer grew, try the mesh again after the pause. */
    private static final long RETRY = -2;

    // Statistics
    long quadsOnGpu;
    long uploadedBytes;
    long uploads;
    /** Meshes that didn't fit in farGpuMb (not drawn). */
    long noRoom;
    long uploadNanos;
    long uploadFrames;
    long uploadMaxNanos;
    /** When the first and the last upload since {@link #resetUploadClock} happened (System.nanoTime, 0 before). */
    long firstUploadAt;
    long lastUploadAt;
    long bytesSinceClock;
    /** Bytes uploaded in each of the last seconds (for the status line's rate). */
    private long secondStart;
    private long secondBytes;
    long lastSecondBytes;

    FarGpuBuffers() {
        geometryBytes = Math.min(FIRST_GEOMETRY_BYTES, maxGeometryBytes());
        geometry = newBuffer(geometryBytes);
        free.put(0, (int) (geometryBytes / UNIT_BYTES));
        recordCapacity = FIRST_RECORDS;
        records = newBuffer((long) recordCapacity * RECORD_BYTES);
        clearRecords(0, recordCapacity);
        instanceIds = newInstanceIds(recordCapacity);
        colours = newBuffer(16);
        info = newBuffer(16);
        biomes = newBuffer(16);
    }

    private static long maxGeometryBytes() {
        return (long) Config.FAR_GPU_MB.get() << 20;
    }

    /** A buffer of this size with undefined contents (bound and unbound on GL_COPY_WRITE_BUFFER: the caller saves it). */
    private static int newBuffer(long bytes) {
        int id = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, id);
        GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, bytes, GL15C.GL_DYNAMIC_DRAW);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
        return id;
    }

    private static int newInstanceIds(int count) {
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = i;
        }
        int id = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, id);
        GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, ids, GL15C.GL_STATIC_DRAW);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
        return id;
    }

    private void clearRecords(int from, int to) {
        int[] empty = new int[(to - from) * RECORD_INTS];
        for (int i = 0; i < to - from; i++) {
            empty[i * RECORD_INTS + 3] = -1;
        }
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, records);
        GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, (long) from * RECORD_BYTES, empty);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
    }

    /** Deletes every buffer (the debug view was turned off, or the renderer failed). */
    void delete() {
        for (int id : new int[]{geometry, records, instanceIds, colours, info, biomes}) {
            if (id != 0) {
                GL15C.glDeleteBuffers(id);
            }
        }
        geometry = records = instanceIds = colours = info = biomes = 0;
        slots.clear();
        dirty.clear();
        free.clear();
    }

    /** Every mesh gone (a new residency, or the store stopped): every slot freed. */
    void clearAll() {
        int saved = GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER);
        clearRecords(0, recordCapacity);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, saved);
        slots.clear();
        dirty.clear();
        free.clear();
        free.put(0, (int) (geometryBytes / UNIT_BYTES));
        unitsUsed = 0;
        freeRecords.clear();
        recordHigh = 0;
        quadsOnGpu = 0;
    }

    void markDirty(long key) {
        dirty.add(key);
    }

    int dirtyCount() {
        return dirty.size();
    }

    int meshesOnGpu() {
        return slots.size();
    }

    long geometryBytesUsed() {
        return unitsUsed * UNIT_BYTES;
    }

    /** Video memory of every buffer here, in bytes. */
    long bytesAllocated() {
        return geometryBytes + (long) recordCapacity * (RECORD_BYTES + 4) + (coloursUploaded == null ? 0 : coloursUploaded.length * 4L)
                + (infoUploaded == null ? 0 : infoUploaded.length * 4L) + (biomesUploaded == null ? 0 : biomesUploaded.length * 4L);
    }

    void resetUploadClock() {
        firstUploadAt = 0;
        lastUploadAt = 0;
        bytesSinceClock = 0;
    }

    /**
     * Brings the GPU in step with the render thread's meshes for the dirty nodes, oldest first, within the frame's
     * byte and time budget: a node with a full mesh is uploaded (replacing its old one) and its entry in
     * {@code meshes} becomes a stub; a node no longer in {@code meshes} is freed. GL_COPY_WRITE_BUFFER and
     * GL_COPY_READ_BUFFER are put back.
     */
    void upload(Long2ObjectOpenHashMap<NodeMesh> meshes) {
        long now = System.nanoTime();
        if (now - secondStart >= 1_000_000_000L) {
            lastSecondBytes = secondBytes;
            secondBytes = 0;
            secondStart = now;
        }
        if (pauseFrames > 0) {
            pauseFrames--;
            return;
        }
        if (dirty.isEmpty()) {
            return;
        }
        int savedWrite = GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER);
        int savedRead = GL11C.glGetInteger(GL31C.GL_COPY_READ_BUFFER);
        long bytes = 0;
        int meshesUploaded = 0;
        int recordsBefore = recordCapacity;
        long geometryBefore = geometryBytes;
        try {
            LongIterator it = dirty.iterator();
            while (it.hasNext() && bytes < UPLOAD_BYTES_PER_FRAME && System.nanoTime() - now < UPLOAD_NANOS_PER_FRAME) {
                long key = it.nextLong();
                it.remove();
                NodeMesh mesh = meshes.get(key);
                if (mesh == null || mesh.empty()) {
                    release(key);
                } else if (!mesh.stubbed()) {
                    long b = put(mesh);
                    if (b == RETRY) {
                        // The buffer grew: this mesh first once the pause is over
                        dirty.addAndMoveToFirst(key);
                        break;
                    }
                    if (b >= 0) {
                        bytes += b;
                        meshesUploaded++;
                        meshes.put(key, mesh.stub());
                    }
                }
            }
        } finally {
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, savedWrite != 0 && GL15C.glIsBuffer(savedWrite) ? savedWrite : 0);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, savedRead != 0 && GL15C.glIsBuffer(savedRead) ? savedRead : 0);
        }
        long end = System.nanoTime();
        if (end - now > 4_000_000L) {
            ShadersCompanion.LOGGER.info("Shaders Companion far terrain: a slow upload frame, {} us for {} meshes ({} KB){}{}", (end - now) / 1000, meshesUploaded,
                    bytes >> 10,
                    recordCapacity != recordsBefore ? ", records grown to " + recordCapacity : "", geometryBytes != geometryBefore ? ", geometry grown" : "");
        }
        uploadNanos += end - now;
        uploadFrames++;
        uploadMaxNanos = Math.max(uploadMaxNanos, end - now);
        if (bytes > 0) {
            uploadedBytes += bytes;
            secondBytes += bytes;
            bytesSinceClock += bytes;
            if (firstUploadAt == 0) {
                firstUploadAt = now;
            }
            lastUploadAt = end;
        }
    }

    /**
     * Uploads a mesh into a new run and its record; returns the bytes, -1 when it doesn't fit, {@link #RETRY} when the
     * geometry buffer grew for it (uploads pause for a few frames).
     */
    private long put(NodeMesh mesh) {
        int uints = mesh.quadCount * 2 + mesh.palette.length;
        int units = (uints + UNIT_UINTS - 1) / UNIT_UINTS;
        int unit = allocate(units);
        if (unit == -2) {
            return RETRY;
        }
        if (unit < 0) {
            noRoom++;
            // Not drawn; its old mesh (if any) goes, so nothing stale stays on screen
            release(mesh.key);
            return -1;
        }
        Slot old = slots.get(mesh.key);
        int slot = old != null ? old.record : newRecord();
        if (slot < 0) {
            freeRun(unit, units);
            noRoom++;
            release(mesh.key);
            return -1;
        }
        long offset = (long) unit * UNIT_BYTES;
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, geometry);
        GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, offset, mesh.quads);
        GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, offset + mesh.quadCount * 8L, mesh.palette);
        int quadBase = unit * (UNIT_UINTS / 2);
        writeRecord(slot, mesh, quadBase, unit * UNIT_UINTS + mesh.quadCount * 2);
        if (old != null) {
            freeRun(old.unit, old.units);
            quadsOnGpu -= old.quads;
        }
        slots.put(mesh.key, new Slot(slot, unit, units, mesh.quadCount));
        quadsOnGpu += mesh.quadCount;
        uploads++;
        return uints * 4L + RECORD_BYTES;
    }

    /**
     * A record (ints): 0-2 the node's origin in blocks, 3 its level (-1: a free slot), 4 its first quad in the
     * geometry buffer (in quads), 5 its palette's first uint, 6 and 7 its geometry box in voxels (min, max: x | y << 6
     * | z << 12), 8-19 each range's first quad (absolute, in quads), 20-31 each range's quad count, 32-43 each range's
     * opaque quads (from its start), 44-47 spare.
     */
    private void writeRecord(int slot, NodeMesh mesh, int quadBase, int paletteBase) {
        int[] r = record;
        int blocks = Node.SIZE << mesh.level;
        r[0] = mesh.x * blocks;
        r[1] = mesh.y * blocks;
        r[2] = mesh.z * blocks;
        r[3] = mesh.level;
        r[4] = quadBase;
        r[5] = paletteBase;
        r[6] = mesh.box[0] | mesh.box[1] << 6 | mesh.box[2] << 12;
        r[7] = mesh.box[3] | mesh.box[4] << 6 | mesh.box[5] << 12;
        for (int i = 0; i < NodeMesh.RANGES; i++) {
            r[8 + i] = quadBase + mesh.rangeStart[i];
            r[20 + i] = mesh.rangeCount[i];
            r[32 + i] = mesh.rangeOpaque[i];
        }
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, records);
        GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, (long) slot * RECORD_BYTES, r);
    }

    /** Frees a node's slot and run (its mesh went, or became empty). */
    private void release(long key) {
        Slot s = slots.remove(key);
        if (s == null) {
            return;
        }
        freeRun(s.unit, s.units);
        quadsOnGpu -= s.quads;
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, records);
        GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, (long) s.record * RECORD_BYTES + 12, new int[]{-1});
        freeRecords.add(s.record);
    }

    private int newRecord() {
        if (!freeRecords.isEmpty()) {
            return freeRecords.removeInt(freeRecords.size() - 1);
        }
        if (recordHigh >= recordCapacity) {
            int capacity = recordCapacity * 2;
            int bigger = newBuffer((long) capacity * RECORD_BYTES);
            copy(records, bigger, (long) recordCapacity * RECORD_BYTES);
            GL15C.glDeleteBuffers(records);
            records = bigger;
            int oldCapacity = recordCapacity;
            recordCapacity = capacity;
            clearRecords(oldCapacity, capacity);
            GL15C.glDeleteBuffers(instanceIds);
            instanceIds = newInstanceIds(capacity);
            instanceIdsGeneration++;
        }
        return recordHigh++;
    }

    /**
     * First fit over the free runs. When nothing fits, grows the buffer and returns -2 (try again after the pause), or -1
     * when even farGpuMb is too small.
     */
    private int allocate(int units) {
        for (Map.Entry<Integer, Integer> run : free.entrySet()) {
            if (run.getValue() >= units) {
                int start = run.getKey();
                int length = run.getValue();
                free.remove(start);
                if (length > units) {
                    free.put(start + units, length - units);
                }
                unitsUsed += units;
                return start;
            }
        }
        return grow() ? -2 : -1;
    }

    private void freeRun(int start, int units) {
        unitsUsed -= units;
        int end = start + units;
        Map.Entry<Integer, Integer> before = free.floorEntry(start);
        if (before != null && before.getKey() + before.getValue() == start) {
            start = before.getKey();
            units += before.getValue();
            free.remove(before.getKey());
        }
        Integer after = free.get(end);
        if (after != null) {
            free.remove(end);
            units += after;
        }
        free.put(start, units);
    }

    /** Doubles the geometry buffer (copied on the GPU), up to farGpuMb; false when it is there already. */
    private boolean grow() {
        long max = maxGeometryBytes();
        if (geometryBytes >= max) {
            return false;
        }
        long bigger = Math.min(max, geometryBytes * 2);
        long start = System.nanoTime();
        int buffer = newBuffer(bigger);
        copy(geometry, buffer, geometryBytes);
        GL15C.glDeleteBuffers(geometry);
        geometry = buffer;
        int oldUnits = (int) (geometryBytes / UNIT_BYTES);
        int newUnits = (int) (bigger / UNIT_BYTES);
        geometryBytes = bigger;
        pauseFrames = PAUSE_AFTER_GROWTH;
        freeRun(oldUnits, newUnits - oldUnits);
        unitsUsed += newUnits - oldUnits;
        ShadersCompanion.LOGGER.info("Shaders Companion far terrain: geometry buffer grown to {} MB in {} us (the render thread's part of the copy)", bigger >> 20,
                (System.nanoTime() - start) / 1000);
        return true;
    }

    private static void copy(int from, int to, long bytes) {
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, from);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, to);
        GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, bytes);
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
    }

    /** Uploads the colour tables when StateColors changed (GL_COPY_WRITE_BUFFER put back). */
    void syncColours() {
        long version = StateColors.version();
        int[] c = StateColors.colours();
        int[] in = StateColors.info();
        int[] b = StateColors.biomeColours();
        if (version == coloursVersion && c == coloursUploaded && in == infoUploaded && b == biomesUploaded) {
            return;
        }
        int saved = GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER);
        upload(colours, c);
        upload(info, in);
        upload(biomes, b);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, saved != 0 && GL15C.glIsBuffer(saved) ? saved : 0);
        coloursVersion = version;
        coloursUploaded = c;
        infoUploaded = in;
        biomesUploaded = b;
        stateCount = Math.min(StateColors.resolvedStates(), in.length);
        int biomesDone = 0;
        while (biomesDone * 4 + 3 < b.length && b[biomesDone * 4 + 3] != 0) {
            biomesDone++;
        }
        biomeCount = biomesDone;
    }

    private static void upload(int buffer, int[] data) {
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, buffer);
        if (data.length == 0) {
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, 16, GL15C.GL_DYNAMIC_DRAW);
        } else {
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, data, GL15C.GL_DYNAMIC_DRAW);
        }
    }
}
