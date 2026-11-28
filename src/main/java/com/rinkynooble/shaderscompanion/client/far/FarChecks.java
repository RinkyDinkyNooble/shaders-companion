package com.rinkynooble.shaderscompanion.client.far;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The dev checks' hooks into the far store and the far terrain (src/verify's DevRun): switches for a run that never touch
 * the config file, and numbers read from the worker, the mesher and the render thread. Nothing in the game calls these.
 */
public final class FarChecks {
    private FarChecks() {
    }

    // Far terrain dev checks (M2a, M2b)

    /**
     * Dev checks (M2b, M3a): far terrain on for the run, without the config file: for the pack when it binds the far
     * textures, else in the debug view (meshing follows it).
     */
    public static void draw(boolean on) {
        FarTerrain.forcedDebug = on;
    }

    /** Dev checks (M3a): the debug view even while the pack reads the far terrain (-PdevFarOverlay). */
    public static void overlay(boolean on) {
        FarTerrain.forcedOverlay = on;
    }

    /** Dev checks (M2b): every node meshed again from scratch (a new residency), e.g. after the cave filling changed. */
    public static void restartMeshing() {
        if (FarStore.worker != null) {
            FarStore.worker.post(FarWorker::restartResidency);
        }
    }

    /**
     * Dev checks (M2b): the GPU's side: meshes waiting to upload (-1 while the GPU path is off), meshes on the GPU,
     * bytes uploaded since the clock was reset, the first and last upload's time since then (System.nanoTime), meshes
     * that didn't fit; uploads' render-thread nanos, frames with uploads and the most in one frame.
     */
    public static long[] gpuNumbers(boolean resetClock) {
        if (!FarTerrain.gpuOn() || !FarRenderer.hasBuffers()) {
            return new long[]{-1, 0, 0, 0, 0, 0, 0, 0, 0};
        }
        FarGpuBuffers b = FarRenderer.buffers();
        long[] out = {b.dirtyCount(), b.meshesOnGpu(), b.bytesSinceClock, b.firstUploadAt, b.lastUploadAt, b.noRoom, b.uploadNanos, b.uploadFrames, b.uploadMaxNanos};
        if (resetClock) {
            b.resetUploadClock();
            b.uploadNanos = 0;
            b.uploadFrames = 0;
            b.uploadMaxNanos = 0;
        }
        return out;
    }

    /** Dev checks: meshing on or off for the run. */
    public static void meshing(boolean on) {
        FarTerrain.meshing = on;
    }

    /** Dev checks: the far range for the run (0: the config's). */
    public static void farRange(int blocks) {
        FarTerrain.forcedFarRange = blocks;
    }

    /** Dev checks: the first level whose dark air is filled (FarMesher.fillFromLevel; nodes meshed from now on). */
    public static void caveFillFrom(int level) {
        FarMesher.fillFromLevel = level;
    }

    /**
     * Dev checks: 3.0.1's sky light fixes off, as in 3.0.0: the store's repair (SkyRepair.off; sections stored from now
     * on keep the light as captured) and the mesher's open dark air beside unknown terrain (FarMesher.unfillOff).
     */
    public static void skyRepairOff(boolean off) {
        SkyRepair.off = off;
        FarMesher.unfillOff = off;
    }

    /** Dev checks: vanilla's fog left as it is with the debug view, as before 3.0.1 (FarTerrain.fogMatchOff). */
    public static void fogMatchOff(boolean off) {
        FarTerrain.fogMatchOff = off;
    }

    /** Dev checks: corner shading in the quads (FarMesher.cornerShading; nodes meshed from now on). */
    public static void cornerShading(boolean on) {
        FarMesher.cornerShading = on;
    }

    /** Dev checks: the residency's numbers (FarResidency.numbers), or null while it isn't running. */
    public static CompletableFuture<long[]> terrainNumbers() {
        if (!FarStore.active) {
            return CompletableFuture.completedFuture(null);
        }
        FarWorker w = FarStore.worker;
        return w.ask(store -> w.residency == null ? null : w.residency.numbers());
    }

    /** Dev checks: the mesher thread's processor time so far, in nanoseconds (-1 before it exists). */
    public static long mesherCpuNanos() {
        FarWorker w = FarStore.worker;
        return w == null || w.mesher == null ? -1 : FarTerrain.cpuNanos(w.mesher.thread);
    }

    /**
     * Dev checks: the colour table's numbers (StateColors.numbers), then the render thread's far terrain part of the
     * frame: nanos, max nanos, frames (the counters start again).
     */
    public static long[] colourNumbers(boolean reset) {
        long[] c = StateColors.numbers();
        long[] out = java.util.Arrays.copyOf(c, c.length + 3);
        out[c.length] = FarTerrain.meshFrameNanos;
        out[c.length + 1] = FarTerrain.meshFrameMaxNanos;
        out[c.length + 2] = FarTerrain.meshFrames;
        if (reset) {
            FarTerrain.meshFrameNanos = 0;
            FarTerrain.meshFrameMaxNanos = 0;
            FarTerrain.meshFrames = 0;
        }
        return out;
    }

    /** Dev checks: the compression measurement over the stored nodes (FarStats.compression), on the worker. */
    public static CompletableFuture<String[]> compression() {
        if (!FarStore.active) {
            return CompletableFuture.completedFuture(new String[]{"The far store is off"});
        }
        return FarStore.worker.ask(store -> {
            try {
                return store == null ? new String[]{"The far store is off"} : FarStats.compression(store);
            } catch (java.io.IOException e) {
                return new String[]{"Compression check failed: " + e};
            }
        });
    }

    /** Dev checks: the auto import's chunks waiting (-1: none runs) and read complete this session; null without a worker. */
    public static int[] autoImport() {
        FarWorker w = FarStore.worker;
        return w == null ? null : new int[]{w.autoWaiting, w.autoComplete};
    }

    /** Dev checks: null before any import; while one runs, "N of M"; once it has ended, its summary line. */
    public static String importProgress() {
        FarImport.Progress progress = FarStore.worker == null ? null : FarStore.worker.importProgress;
        return progress == null ? null : progress.end() != null ? progress.end() : progress.done() + " of " + progress.total();
    }

    /**
     * Dev checks (M1c): the stored sections of the loaded chunks within {@code radius} chunks of a chunk, section
     * heights {@code minY} to {@code maxY}, against the client's world (FarStats.compareWithWorld). The world is read
     * here on the main thread (a hitch, for a check), the comparison runs on the worker.
     */
    public static CompletableFuture<String[]> compareWithWorld(int chunkX, int chunkZ, int radius, int minY, int maxY) {
        if (!FarStore.active) {
            return CompletableFuture.completedFuture(new String[]{"The far store is off"});
        }
        ClientLevel level = FarStore.capture.level();
        java.util.List<long[]> positions = new java.util.ArrayList<>();
        java.util.List<net.minecraft.world.level.block.state.BlockState[]> states = new java.util.ArrayList<>();
        java.util.List<long[]> exposed = new java.util.ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cz = chunkZ - radius; cz <= chunkZ + radius; cz++) {
            for (int cx = chunkX - radius; cx <= chunkX + radius; cx++) {
                if (level.getChunkSource().getChunk(cx, cz, false) == null) {
                    continue;
                }
                for (int sy = minY; sy <= maxY; sy++) {
                    net.minecraft.world.level.block.state.BlockState[] s = new net.minecraft.world.level.block.state.BlockState[4096];
                    long[] e = new long[64];
                    int i = 0;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++, i++) {
                                s[i] = level.getBlockState(pos.set(cx * 16 + x, sy * 16 + y, cz * 16 + z));
                            }
                        }
                    }
                    i = 0;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++, i++) {
                                boolean open = false;
                                for (Direction d : Direction.values()) {
                                    int nx = x + d.getStepX();
                                    int ny = y + d.getStepY();
                                    int nz = z + d.getStepZ();
                                    net.minecraft.world.level.block.state.BlockState n = nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16
                                            ? s[nx + 16 * (nz + 16 * ny)] : level.getBlockState(pos.set(cx * 16 + nx, sy * 16 + ny, cz * 16 + nz));
                                    if (!n.isSolidRender(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                                        open = true;
                                        break;
                                    }
                                }
                                if (open) {
                                    e[i >> 6] |= 1L << (i & 63);
                                }
                            }
                        }
                    }
                    positions.add(new long[]{cx, sy, cz});
                    states.add(s);
                    exposed.add(e);
                }
            }
        }
        return FarStore.worker.ask(store -> store == null ? new String[]{"The far store is off"} : FarStats.compareWithWorld(store, positions, states, exposed));
    }

    /** Dev checks (M1c): every 4th stored level-0 node against a store written without hidden voxels (FarStats.compareWithRaw). */
    public static CompletableFuture<String[]> compareWithRawStore(Path rawFolder, int[] skipChunks) {
        if (!FarStore.active) {
            return CompletableFuture.completedFuture(new String[]{"The far store is off"});
        }
        return FarStore.worker.ask(store -> {
            try {
                return store == null ? new String[]{"The far store is off"} : FarStats.compareWithRaw(store, rawFolder, skipChunks);
            } catch (java.io.IOException e) {
                return new String[]{"Comparing with " + rawFolder + " failed: " + e};
            }
        });
    }

    /** Dev checks (M1c): false never marks a section stale when a voxel next to it opens (the tunnel check's negative control). */
    public static void markStale(boolean on) {
        FarNodes.markStale = on;
    }

    /** Dev checks (M1c): false keeps the coarser levels' exposure test at node borders to neighbours in memory (M1a, M1b). */
    public static void diskBorders(boolean on) {
        FarNodes.diskBorders = on;
    }

    /** Dev checks (M1c): false stores sections as captured, without replacing their hidden voxels (HiddenVoxels). */
    public static void hideVoxels(boolean hide) {
        FarNodes.hideVoxels = hide;
    }

    /** Dev checks: log what differs in the next {@code count} changed sections. */
    public static void logChanges(int count) {
        FarNodes.logChanges = count;
    }

    /** Dev checks: the store on for this run with this cache size (0: the config's), without saving the config. */
    public static void force(boolean on, int cacheMbOrZero) {
        FarStore.forcedOn = on;
        FarStore.forcedCacheMb = cacheMbOrZero;
    }

    /**
     * Dev checks: the join's work is done: nothing queued for the worker, and at most a few sections waiting to be
     * copied and a few nodes to be rebuilt (a world always has a block or two changing: grass spreading, water
     * flowing, each change cascading up the levels 5 seconds a level).
     */
    public static boolean settled() {
        FarWorker w = FarStore.worker;
        return FarStore.active && FarStore.capture.pendingSections <= 8 && w.queued() == 0 && w.rebuildsWaiting <= 12;
    }

    /** Dev checks: the main thread's time a frame, average and max over the last second, in microseconds. */
    public static double[] frameMicros() {
        return FarStore.active ? new double[]{FarStore.capture.frameMicrosAverage, FarStore.capture.frameMicrosMax, FarStore.capture.pendingSections} : new double[3];
    }

    /**
     * Dev checks (M1c): starts recording every frame's main-thread time (true), or stops and returns the frames recorded
     * since, in nanoseconds (false; null when the store isn't running), with the sections copied meanwhile as the last
     * value.
     */
    public static long[] recordFrames(boolean start) {
        if (!FarStore.active) {
            return null;
        }
        if (start) {
            FarStore.capture.recorded = new long[4096];
            FarStore.capture.recordedFrames = 0;
            FarStore.capture.recordedLoads = new long[65536];
            FarStore.capture.recordedLoadCount = 0;
            FarStore.capture.recordedFrom = FarStore.capture.sectionsCaptured;
            FarStore.capture.recordedLostFrom = FarStore.capture.sectionsLost;
            return null;
        }
        long[] frames = FarStore.capture.recorded == null ? new long[0] : java.util.Arrays.copyOf(FarStore.capture.recorded, FarStore.capture.recordedFrames);
        long[] out = java.util.Arrays.copyOf(frames, frames.length + 2);
        out[frames.length] = FarStore.capture.sectionsCaptured - FarStore.capture.recordedFrom;
        out[frames.length + 1] = FarStore.capture.sectionsLost - FarStore.capture.recordedLostFrom;
        FarStore.capture.recorded = null;
        return out;
    }

    /**
     * Dev checks (M1c): the time from each chunk's load to its first copy while frames were recorded, in nanoseconds
     * (call after {@link #recordFramesForChecks}(false)); the recording ends.
     */
    public static long[] recordedLoads() {
        if (!FarStore.active || FarStore.capture.recordedLoads == null) {
            return new long[0];
        }
        long[] loads = java.util.Arrays.copyOf(FarStore.capture.recordedLoads, FarStore.capture.recordedLoadCount);
        FarStore.capture.recordedLoads = null;
        return loads;
    }

    /** Dev checks (M1c): the processor time the worker thread has used so far, in nanoseconds (-1 without a worker). */
    public static long workerCpuNanos() {
        FarWorker w = FarStore.worker;
        return w == null ? -1 : java.lang.management.ManagementFactory.getThreadMXBean().getThreadCpuTime(w.thread.getId());
    }

    /** Dev checks (M1c): the worker's counters now ({@link FarStats#counters}); null when the store is off. */
    public static CompletableFuture<long[]> counters() {
        if (!FarStore.active) {
            return CompletableFuture.completedFuture(null);
        }
        return FarStore.worker.ask(store -> store == null ? null : FarStats.counters(store));
    }

    /** Dev checks (M1c): what the worker did between two readings of {@link #countersForChecks}. */
    public static String countersSummary(long[] a, long[] b) {
        if (a == null || b == null) {
            return "no counters";
        }
        long[] d = new long[b.length];
        for (int i = 0; i < d.length; i++) {
            d[i] = b[i] - a[i];
        }
        return String.format("%d sections stored (%d new, %d changed, %d unchanged), %.1f us a section (of which hidden voxels %.1f us, %d sections, "
                        + "%d voxels replaced); %d coarser rebuilds, %.0f us each (%d octant folds, %.0f us each); %d nodes encoded, %.0f us each; %d decoded, %.0f us each "
                        + "(%.0f us of it reading); %d batches, %.0f nodes and %.1f ms each, at most %.1f ms (%.0f us a node); cache %d hits, %d misses (%d read from disk, "
                        + "%d new), %d nodes written",
                d[0], d[2], d[3], d[4], d[0] == 0 ? 0.0 : d[1] / 1.0e3 / d[0], d[0] == 0 ? 0.0 : d[24] / 1.0e3 / d[0], d[25], d[23], d[5], d[5] == 0 ? 0.0 : d[6] / 1.0e3 / d[5], d[7], d[7] == 0 ? 0.0 : d[8] / 1.0e3 / d[7], d[14], d[14] == 0 ? 0.0 : d[15] / 1.0e3 / d[14],
                d[16], d[16] == 0 ? 0.0 : d[17] / 1.0e3 / d[16], d[16] == 0 ? 0.0 : d[18] / 1.0e3 / d[16], d[19], d[19] == 0 ? 0.0 : (double) d[20] / d[19],
                d[19] == 0 ? 0.0 : d[21] / 1.0e6 / d[19], b[22] / 1.0e6, d[20] == 0 ? 0.0 : d[21] / 1.0e3 / d[20], d[9], d[10], d[11], d[12], d[13]);
    }
}
