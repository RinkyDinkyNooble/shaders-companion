package com.rinkynooble.shaderscompanion.server;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.far.CapturedSection;
import com.rinkynooble.shaderscompanion.far.ColumnWire;
import com.rinkynooble.shaderscompanion.far.SavedChunks;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * The far terrain sharing's thread ("Shaders Companion far share", lowest priority, a daemon): plans each player's
 * offers from the region files' tables (their first 8 KiB, nothing else of them) and packs the columns players want,
 * read through the server's own chunk storage ({@link SavedChunks}). Packed columns are kept in a small cache by
 * column and save time, so players near each other cost one packing. It never touches a level's live chunks or a
 * player: results go into the sessions' queues for the server thread ({@link ShareSession#plans}, {@link ShareSession#ready}).
 */
final class ShareWorker {
    /** Reads of all players in flight, at most. */
    static final int MAX_READS = 32;

    private record Key(ResourceKey<Level> dimension, long pos, int stamp) {
    }

    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean stopped;
    final AtomicInteger reads = new AtomicInteger();
    /** Packed columns, least recently used first (worker thread only). */
    private final LinkedHashMap<Key, byte[]> cache = new LinkedHashMap<>(256, 0.75F, true);
    private volatile long cacheBytes;

    // Statistics
    volatile long packed;
    volatile long cacheHits;
    volatile long packNanos;
    volatile long failures;

    ShareWorker() {
        thread = new Thread(this::run, "Shaders Companion far share");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    void stop() {
        stopped = true;
        thread.interrupt();
    }

    int queued() {
        return tasks.size();
    }

    private void run() {
        while (!stopped) {
            Runnable task;
            try {
                task = tasks.take();
            } catch (InterruptedException e) {
                continue;
            }
            try {
                task.run();
            } catch (Throwable t) {
                failures++;
                ShadersCompanion.LOGGER.warn("Shaders Companion far share: a task failed", t);
            }
        }
    }

    /**
     * Plans a player's offers (the session's epoch at the time): every saved column within the radius (a circle), nearest
     * first, as the region files' tables give them, with their save times.
     */
    void plan(ShareSession session, int epoch, SavedChunks saved, int centerX, int centerZ, int radius) {
        tasks.add(() -> {
            long[][] chunks = {new long[1024]};
            int[][] stamps = {new int[1024]};
            int[] n = {0};
            long r2 = (long) radius * radius;
            it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap times = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            try {
                saved.stamps(centerX, centerZ, radius, (pos, written) -> {
                    long dx = ChunkPos.getX(pos) - centerX;
                    long dz = ChunkPos.getZ(pos) - centerZ;
                    if (dx * dx + dz * dz <= r2) {
                        if (n[0] == chunks[0].length) {
                            chunks[0] = Arrays.copyOf(chunks[0], n[0] * 2);
                        }
                        chunks[0][n[0]++] = pos;
                        times.put(pos, written);
                    }
                });
            } catch (java.io.IOException e) {
                ShadersCompanion.LOGGER.warn("Shaders Companion far share: reading {}'s region files failed: {}", saved.level.dimension().location(), e.toString());
            }
            long[] order = SavedChunks.nearestFirst(chunks[0], n[0], centerX, centerZ, radius);
            int[] orderStamps = new int[order.length];
            for (int i = 0; i < order.length; i++) {
                orderStamps[i] = times.get(order[i]);
            }
            ShadersCompanion.LOGGER.debug("Shaders Companion far share: planned {} saved columns of {} within {} chunks of {}, {}", order.length,
                    saved.level.dimension().location(), radius, centerX, centerZ);
            session.plans.add(new ShareSession.Plan(epoch, centerX, centerZ, order, orderStamps));
        });
    }

    /**
     * Packs a wanted column for a player: from the cache, or read through the server's chunk storage, checked (complete
     * and lit, brought up to the game's version) and packed. Not complete or not saved: an empty column (the client
     * records the time and asks again only once it is saved again).
     */
    void pack(ShareSession session, int epoch, SavedChunks saved, long pos, int stamp, boolean hide) {
        reads.incrementAndGet();
        session.reads.incrementAndGet();
        Key key = new Key(saved.level.dimension(), pos, stamp);
        tasks.add(() -> {
            byte[] hit = cache.get(key);
            if (hit != null) {
                cacheHits++;
                done(session, epoch, pos, stamp, hit);
                return;
            }
            java.util.concurrent.CompletableFuture<java.util.Optional<CompoundTag>> read;
            try {
                read = saved.read(pos);
            } catch (RuntimeException e) {
                done(session, epoch, pos, stamp, new byte[0]);
                return;
            }
            read.whenComplete((tag, error) -> tasks.add(() -> {
                byte[] data = new byte[0];
                try {
                    if (error == null && tag != null && tag.isPresent() && !stopped) {
                        data = packColumn(saved, tag.get(), pos, hide);
                    }
                    if (error == null) {
                        // Kept even when empty (not complete): the next player near it isn't read for it again
                        keep(key, data);
                    }
                } catch (RuntimeException e) {
                    failures++;
                    ShadersCompanion.LOGGER.warn("Shaders Companion far share: packing chunk {}, {} failed: {}", ChunkPos.getX(pos), ChunkPos.getZ(pos), e.toString());
                } finally {
                    done(session, epoch, pos, stamp, data);
                }
            }));
        });
    }

    private byte[] packColumn(SavedChunks saved, CompoundTag tag, long pos, boolean hide) {
        long start = System.nanoTime();
        CompoundTag current = saved.upgraded(tag);
        if (SavedChunks.check(current) != SavedChunks.Status.COMPLETE) {
            return new byte[0];
        }
        int[] errors = new int[1];
        List<CapturedSection> sections = saved.decode(current, ChunkPos.getX(pos), ChunkPos.getZ(pos), 0, errors);
        byte[] data = ColumnWire.encode(sections, saved.minSection, saved.sectionCount, saved.biomeIds, hide);
        if (data.length > com.rinkynooble.shaderscompanion.far.net.FarNet.MAX_COLUMN_BYTES) {
            // Never happens with real chunks (a column packs to a few KB): left out rather than sent
            return new byte[0];
        }
        packed++;
        packNanos += System.nanoTime() - start;
        return data;
    }

    private void done(ShareSession session, int epoch, long pos, int stamp, byte[] data) {
        reads.decrementAndGet();
        session.reads.decrementAndGet();
        session.ready.add(new ShareSession.Ready(epoch, pos, stamp, data));
    }

    private void keep(Key key, byte[] data) {
        long max = ShareConfig.CACHE_MB.get() * 1048576L;
        if (max <= 0) {
            return;
        }
        // An entry costs its bytes and about 64 of its own
        byte[] old = cache.put(key, data);
        cacheBytes += data.length + 64 - (old == null ? 0 : old.length + 64);
        var it = cache.entrySet().iterator();
        while (cacheBytes > max && it.hasNext()) {
            Map.Entry<Key, byte[]> eldest = it.next();
            cacheBytes -= eldest.getValue().length + 64;
            it.remove();
        }
    }

    long cacheBytes() {
        return cacheBytes;
    }
}
