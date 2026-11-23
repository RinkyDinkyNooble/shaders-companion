package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.Config;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The far store (research/lod/DESIGN.md, "The voxel store"; M1-PLAN): the mod's own copy of the world for far
 * terrain, in nodes of 32^3 voxels at levels 0 (a voxel a block) to 6 (64 blocks), built from the chunks the client
 * loads. Nothing is drawn from it yet. Behind the {@code farStore} setting, off by default: every hook here returns at
 * once while it is off. The main thread only marks sections and copies them within {@code farStoreBudget}
 * microseconds a frame ({@link FarCapture}); one worker thread does the rest ({@link FarWorker}, {@link FarNodes}).
 * The store lives on disk in {@code <game dir>/shaderscompanion/lod/<world>/<dimension>/} ({@link FarDisk}); a world
 * or dimension change, leaving the world or turning the setting off closes it (every change written), and the game's
 * end waits for that. In singleplayer, {@code /shaderscompanion lod import} adds the chunks the world has saved
 * ({@link FarImport}).
 */
public final class FarStore {
    /** Capturing for {@link #capture}'s level (main thread). The hooks check only this. */
    private static boolean active;
    private static FarCapture capture;
    private static FarWorker worker;
    private static int generation;
    private static int cacheMb;
    private static volatile String failure;
    /** The import's progress line in the chat: when it was last sent. */
    private static long lastImportChat;

    // Dev checks (src/verify): the store on for a run and its cache size, without touching the config file
    private static boolean forcedOn;
    private static int forcedCacheMb;

    private FarStore() {
    }

    private static boolean wanted() {
        return failure == null && (forcedOn || Config.FAR_STORE.get());
    }

    /** Called from the frame hook before the world is rendered: starts and stops the store, and copies due sections. */
    public static void frame(Camera camera) {
        if (!active && !wanted()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null || !wanted()) {
                stop();
                return;
            }
            if (capture == null || capture.level() != level) {
                stop();
                start(mc, level, camera);
            }
            int wantedMb = forcedCacheMb > 0 ? forcedCacheMb : Config.FAR_STORE_CACHE_MB.get();
            if (wantedMb != cacheMb) {
                cacheMb = wantedMb;
                worker.post(w -> {
                    if (w.store() != null) {
                        w.store().setCacheMb(wantedMb);
                    }
                });
            }
            // Sections the worker wants copied again (a voxel next to them opened: HiddenVoxels), then the frame's copies
            java.util.Queue<Long> again = worker.recaptures;
            if (again != null) {
                Long pos;
                while ((pos = again.poll()) != null) {
                    capture.markSection(SectionPos.x(pos), SectionPos.y(pos), SectionPos.z(pos));
                }
            }
            capture.frame(Mth.floor(camera.getPosition().x) >> 4, Mth.floor(camera.getPosition().y) >> 4, Mth.floor(camera.getPosition().z) >> 4,
                    Config.FAR_STORE_BUDGET.get() * 1000L);
            chat(mc);
        } catch (Throwable t) {
            failure = t.toString();
            ShadersCompanion.LOGGER.error("Shaders Companion far store stopped after an error", t);
            stop();
        }
    }

    private static void start(Minecraft mc, ClientLevel level, Camera camera) {
        if (worker == null) {
            worker = new FarWorker();
        }
        generation++;
        int gen = generation;
        cacheMb = forcedCacheMb > 0 ? forcedCacheMb : Config.FAR_STORE_CACHE_MB.get();
        String world = worldKey(mc);
        String dimension = level.dimension().location().getNamespace() + "_" + level.dimension().location().getPath();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int mb = cacheMb;
        Path folder = mc.gameDirectory.toPath().resolve("shaderscompanion").resolve("lod").resolve(world).resolve(dimension);
        // Sections of the store before are let go by their generation; the old store closes first (the queue's order)
        worker.messages.clear();
        worker.post(w -> w.replaceStore(() -> new FarNodes(gen, world, dimension, minY, maxY, mb, folder)));
        FarWorker sink = worker;
        capture = new FarCapture(level, gen, sink::post);
        active = true;
        // The chunks already loaded (the store was off, or the world arrived before the first frame)
        int cx = Mth.floor(camera.getPosition().x) >> 4;
        int cz = Mth.floor(camera.getPosition().z) >> 4;
        int radius = mc.options.getEffectiveRenderDistance() + 3;
        int loaded = 0;
        for (int z = cz - radius; z <= cz + radius; z++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (level.getChunkSource().getChunk(x, z, false) != null) {
                    capture.markChunk(x, z);
                    loaded++;
                }
            }
        }
        ShadersCompanion.LOGGER.info("Shaders Companion far store: started for {} {} ({} chunks already loaded, cache {} MB)", world, dimension, loaded, mb);
    }

    /**
     * Stops capturing; the worker stores what was already copied, then closes the store (every change written, on the
     * worker: nothing waits here).
     */
    private static void stop() {
        if (!active && capture == null) {
            return;
        }
        active = false;
        capture = null;
        generation++;
        if (worker != null) {
            worker.post(w -> w.replaceStore(() -> null));
        }
        ShadersCompanion.LOGGER.info("Shaders Companion far store: stopped");
    }

    /** Sends the worker's chat lines (an import's start and end) and the import's progress every 10 seconds. */
    private static void chat(Minecraft mc) {
        FarWorker w = worker;
        String line;
        while ((line = w.messages.poll()) != null) {
            mc.gui.getChat().addMessage(Component.literal(line));
        }
        FarImport.Progress progress = w.importProgress;
        long now = System.nanoTime();
        if (progress != null && progress.end() == null && now - lastImportChat >= 10_000_000_000L) {
            lastImportChat = now;
            mc.gui.getChat().addMessage(Component.literal(String.format("Far store import: %d of %d chunks, %.0f a second", progress.done(), progress.total(),
                    progress.perSecond())));
        }
    }

    /**
     * The store's folder name for this world: the singleplayer save's folder prefixed "sp-", or "mp-" and the server's
     * address with unsafe characters replaced.
     */
    static String worldKey(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "sp-" + safe(root.getFileName() == null ? "world" : root.getFileName().toString());
        }
        ServerData server = mc.getCurrentServer();
        return "mp-" + safe(server == null ? "unknown" : server.ip);
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // Hooks (main thread); each returns at once while the store is off

    public static void chunkLoaded(Level level, int x, int z) {
        if (active && level == capture.level()) {
            capture.markChunk(x, z);
        }
    }

    public static void chunkUnloaded(Level level, LevelChunk chunk) {
        if (active && level == capture.level()) {
            capture.chunkUnloaded(chunk);
        }
    }

    public static void blockChanged(Level level, BlockPos pos) {
        if (active && level == capture.level()) {
            capture.markSection(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        }
    }

    public static void sectionChanged(Level level, int x, int y, int z) {
        if (active && level == capture.level()) {
            capture.markSection(x, y, z);
        }
    }

    /** Light changed in a section of the client's level (the client has one light engine at a time). */
    public static void lightChanged(int x, int y, int z) {
        if (active) {
            capture.markSection(x, y, z);
        }
    }

    /** The client's level is unloading (leaving the world, or another dimension): the store stops with it. */
    public static void forgetWorld() {
        if (active) {
            stop();
        }
    }

    static void workerFailed(Throwable t) {
        failure = "the worker: " + t;
    }

    /** The status line ("Far store: ..."). */
    public static String status() {
        if (failure != null) {
            return "stopped after an error: " + failure;
        }
        if (!active) {
            return wanted() ? "waiting for a world" : "off";
        }
        FarWorker w = worker;
        StringBuilder line = new StringBuilder(String.format("%d sections stored (this session %d new, %d changed, %d unchanged), %d waiting, "
                        + "main thread %.0f us a frame (max %.0f), worker %.0f us a section", w.sectionsStored, w.sessionNew, w.sessionChanged, w.sessionUnchanged,
                capture.pendingSections + w.queued(), capture.frameMicrosAverage, capture.frameMicrosMax, w.ingestMicros));
        if (w.rebuildsWaiting > 0) {
            line.append(String.format(", %d coarser nodes to rebuild (%.0f us each)", w.rebuildsWaiting, w.rebuildMicros));
        }
        line.append(String.format(", %d nodes in memory (%d compact, %d MB)", w.nodesInMemory, w.compactInMemory, w.bytesInMemory >> 20));
        if (w.nodesDropped > 0) {
            line.append(String.format(", %d dropped from memory", w.nodesDropped));
        }
        if (capture.sectionsLost > 0) {
            line.append(String.format(", %d sections not copied at unload", capture.sectionsLost));
        }
        FarImport.Progress progress = w.importProgress;
        if (progress != null && progress.end() == null) {
            line.append(String.format(", importing: %d of %d chunks, %.0f a second", progress.done(), progress.total(), progress.perSecond()));
        }
        if (w.diskError != null) {
            return line.append(", no disk (").append(w.diskError).append(")").toString();
        }
        return line.append(String.format(", %d MB on disk in %d region files", w.diskBytes >> 20, w.regionFiles)).toString();
    }

    // Commands and dev checks

    /**
     * Writes the map of a level over a square of blocks to screenshots/&lt;file&gt; on the worker (the {@code lod map}
     * command and the dev checks); the future gives the line for the chat.
     */
    public static CompletableFuture<String> map(int level, int minX, int minZ, int widthX, int widthZ, String file) {
        if (!active) {
            return CompletableFuture.completedFuture("The far store is off (farStore in config/shaderscompanion-client.toml)");
        }
        Path path = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve(file);
        return worker.ask(store -> {
            if (store == null) {
                return "The far store is off";
            }
            try {
                return "Saved screenshots/" + file + ": " + LodMap.write(store, level, minX, minZ, widthX, widthZ, path);
            } catch (java.io.IOException e) {
                return "Saving screenshots/" + file + " failed: " + e;
            }
        });
    }

    /** The map of a level around the camera, radius nodes of that level each way from the camera's node (the command). */
    public static CompletableFuture<String> mapAroundCamera(int level, int radius) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        int nodeBlocks = Node.SIZE << level;
        int nx = Math.floorDiv(Mth.floor(camera.getPosition().x), nodeBlocks);
        int nz = Math.floorDiv(Mth.floor(camera.getPosition().z), nodeBlocks);
        return map(level, (nx - radius) * nodeBlocks, (nz - radius) * nodeBlocks, (2 * radius + 1) * nodeBlocks, (2 * radius + 1) * nodeBlocks,
                "shaderscompanion-lod-L" + level + ".png");
    }

    /** The {@code lod stats} lines, from the worker. */
    public static CompletableFuture<String[]> stats() {
        if (!active) {
            return CompletableFuture.completedFuture(new String[]{"The far store is off (farStore in config/shaderscompanion-client.toml)"});
        }
        double mainMicros = capture.averageSectionMicros();
        long captured = capture.sectionsCaptured;
        return worker.ask(store -> store == null ? new String[]{"The far store is off"} : FarStats.lines(store, captured, mainMicros));
    }

    /**
     * Starts an import of the chunks the integrated server has saved, in a square of {@code radius} chunks around the
     * player (the {@code lod import} command); the line says whether it started (its progress follows in the chat).
     */
    public static String startImport(int radius) {
        if (!active) {
            return "The far store is off (farStore in config/shaderscompanion-client.toml)";
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            return "The far store's import works in singleplayer only (it reads the world's saved chunks)";
        }
        ServerLevel level = server.getLevel(capture.level().dimension());
        if (level == null) {
            return "The far store's import found no saved dimension " + capture.level().dimension().location();
        }
        FarImport next = new FarImport(level, mc.player.chunkPosition().x, mc.player.chunkPosition().z, radius, generation);
        lastImportChat = System.nanoTime();
        worker.post(w -> w.startImport(next));
        return "Far store import of the saved chunks within " + next.radius + " chunks of you: starting";
    }

    /** Stops the import (the {@code lod import stop} command). */
    public static String stopImport() {
        if (!active) {
            return "The far store is off";
        }
        worker.post(FarWorker::stopImport);
        return "Far store import: stopping";
    }

    /** Dev checks: the compression measurement over the stored nodes (FarStats.compression), on the worker. */
    public static CompletableFuture<String[]> compressionForChecks() {
        if (!active) {
            return CompletableFuture.completedFuture(new String[]{"The far store is off"});
        }
        return worker.ask(store -> {
            try {
                return store == null ? new String[]{"The far store is off"} : FarStats.compression(store);
            } catch (java.io.IOException e) {
                return new String[]{"Compression check failed: " + e};
            }
        });
    }

    /** Dev checks: null before any import; while one runs, "N of M"; once it has ended, its summary line. */
    public static String importForChecks() {
        FarImport.Progress progress = worker == null ? null : worker.importProgress;
        return progress == null ? null : progress.end() != null ? progress.end() : progress.done() + " of " + progress.total();
    }

    /**
     * Dev checks (M1c): the stored sections of the loaded chunks within {@code radius} chunks of a chunk, section
     * heights {@code minY} to {@code maxY}, against the client's world (FarStats.compareWithWorld). The world is read
     * here on the main thread (a hitch, for a check), the comparison runs on the worker.
     */
    public static CompletableFuture<String[]> compareWithWorldForChecks(int chunkX, int chunkZ, int radius, int minY, int maxY) {
        if (!active) {
            return CompletableFuture.completedFuture(new String[]{"The far store is off"});
        }
        ClientLevel level = capture.level();
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
        return worker.ask(store -> store == null ? new String[]{"The far store is off"} : FarStats.compareWithWorld(store, positions, states, exposed));
    }

    /** Dev checks (M1c): every 4th stored level-0 node against a store written without hidden voxels (FarStats.compareWithRaw). */
    public static CompletableFuture<String[]> compareWithRawStoreForChecks(Path rawFolder, int[] skipChunks) {
        if (!active) {
            return CompletableFuture.completedFuture(new String[]{"The far store is off"});
        }
        return worker.ask(store -> {
            try {
                return store == null ? new String[]{"The far store is off"} : FarStats.compareWithRaw(store, rawFolder, skipChunks);
            } catch (java.io.IOException e) {
                return new String[]{"Comparing with " + rawFolder + " failed: " + e};
            }
        });
    }

    /** Dev checks (M1c): false never marks a section stale when a voxel next to it opens (the tunnel check's negative control). */
    public static void markStaleForChecks(boolean on) {
        FarNodes.markStale = on;
    }

    /** Dev checks (M1c): false keeps the coarser levels' exposure test at node borders to neighbours in memory (M1a, M1b). */
    public static void diskBordersForChecks(boolean on) {
        FarNodes.diskBorders = on;
    }

    /** Dev checks (M1c): false stores sections as captured, without replacing their hidden voxels (HiddenVoxels). */
    public static void hideVoxelsForChecks(boolean hide) {
        FarNodes.hideVoxels = hide;
    }

    /** Dev checks: log what differs in the next {@code count} changed sections. */
    public static void logChangesForChecks(int count) {
        FarNodes.logChanges = count;
    }

    /** Dev checks: the store on for this run with this cache size (0: the config's), without saving the config. */
    public static void forceForChecks(boolean on, int cacheMbOrZero) {
        forcedOn = on;
        forcedCacheMb = cacheMbOrZero;
    }

    /**
     * Dev checks: the join's work is done: nothing queued for the worker, and at most a few sections waiting to be
     * copied and a few nodes to be rebuilt (a world always has a block or two changing: grass spreading, water
     * flowing, each change cascading up the levels 5 seconds a level).
     */
    public static boolean settledForChecks() {
        FarWorker w = worker;
        return active && capture.pendingSections <= 8 && w.queued() == 0 && w.rebuildsWaiting <= 12;
    }

    /** Dev checks: the main thread's time a frame, average and max over the last second, in microseconds. */
    public static double[] frameMicrosForChecks() {
        return active ? new double[]{capture.frameMicrosAverage, capture.frameMicrosMax, capture.pendingSections} : new double[3];
    }

    /**
     * Dev checks (M1c): starts recording every frame's main-thread time (true), or stops and returns the frames recorded
     * since, in nanoseconds (false; null when the store isn't running), with the sections copied meanwhile as the last
     * value.
     */
    public static long[] recordFramesForChecks(boolean start) {
        if (!active) {
            return null;
        }
        if (start) {
            capture.recorded = new long[4096];
            capture.recordedFrames = 0;
            capture.recordedLoads = new long[65536];
            capture.recordedLoadCount = 0;
            capture.recordedFrom = capture.sectionsCaptured;
            capture.recordedLostFrom = capture.sectionsLost;
            return null;
        }
        long[] frames = capture.recorded == null ? new long[0] : java.util.Arrays.copyOf(capture.recorded, capture.recordedFrames);
        long[] out = java.util.Arrays.copyOf(frames, frames.length + 2);
        out[frames.length] = capture.sectionsCaptured - capture.recordedFrom;
        out[frames.length + 1] = capture.sectionsLost - capture.recordedLostFrom;
        capture.recorded = null;
        return out;
    }

    /**
     * Dev checks (M1c): the time from each chunk's load to its first copy while frames were recorded, in nanoseconds
     * (call after {@link #recordFramesForChecks}(false)); the recording ends.
     */
    public static long[] recordedLoadsForChecks() {
        if (!active || capture.recordedLoads == null) {
            return new long[0];
        }
        long[] loads = java.util.Arrays.copyOf(capture.recordedLoads, capture.recordedLoadCount);
        capture.recordedLoads = null;
        return loads;
    }

    /** Dev checks (M1c): the processor time the worker thread has used so far, in nanoseconds (-1 without a worker). */
    public static long workerCpuNanosForChecks() {
        FarWorker w = worker;
        return w == null ? -1 : java.lang.management.ManagementFactory.getThreadMXBean().getThreadCpuTime(w.thread.getId());
    }

    /** Dev checks (M1c): the worker's counters now ({@link FarStats#counters}); null when the store is off. */
    public static CompletableFuture<long[]> countersForChecks() {
        if (!active) {
            return CompletableFuture.completedFuture(null);
        }
        return worker.ask(store -> store == null ? null : FarStats.counters(store));
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
