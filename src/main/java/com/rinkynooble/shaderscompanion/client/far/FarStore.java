package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.Config;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
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
 * A world or dimension change, leaving the world or turning the setting off drops the store (M1a keeps it in memory
 * only; M1b adds the disk).
 */
public final class FarStore {
    /** Capturing for {@link #capture}'s level (main thread). The hooks check only this. */
    private static boolean active;
    private static FarCapture capture;
    private static FarWorker worker;
    private static int generation;
    private static int cacheMb;
    private static volatile String failure;

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
            capture.frame(Mth.floor(camera.getPosition().x) >> 4, Mth.floor(camera.getPosition().y) >> 4, Mth.floor(camera.getPosition().z) >> 4,
                    Config.FAR_STORE_BUDGET.get() * 1000L);
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
        worker.clear();
        worker.post(w -> w.replaceStore(new FarNodes(gen, world, dimension, minY, maxY, mb)));
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

    /** Stops capturing and lets the store go (M1b: after writing it). */
    private static void stop() {
        if (!active && capture == null) {
            return;
        }
        active = false;
        capture = null;
        generation++;
        if (worker != null) {
            worker.clear();
            worker.post(w -> w.replaceStore(null));
        }
        ShadersCompanion.LOGGER.info("Shaders Companion far store: stopped");
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
        StringBuilder line = new StringBuilder(String.format("%d sections stored, %d waiting, main thread %.0f us a frame (max %.0f), worker %.0f us a section",
                w.sectionsStored, capture.pendingSections + w.queued(), capture.frameMicrosAverage, capture.frameMicrosMax, w.ingestMicros));
        if (w.rebuildsWaiting > 0) {
            line.append(String.format(", %d coarser nodes to rebuild (%.0f us each)", w.rebuildsWaiting, w.rebuildMicros));
        }
        line.append(String.format(", %d nodes in memory (%d MB)", w.nodesInMemory, w.bytesInMemory >> 20));
        if (w.nodesDropped > 0) {
            line.append(String.format(", %d dropped from memory (kept nowhere until the disk comes)", w.nodesDropped));
        }
        if (capture.sectionsLost > 0) {
            line.append(String.format(", %d sections not copied at unload", capture.sectionsLost));
        }
        return line.append(", 0 MB on disk in 0 region files").toString();
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
}
