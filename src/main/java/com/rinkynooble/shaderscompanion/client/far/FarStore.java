package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.Config;
import com.rinkynooble.shaderscompanion.client.FarTextures;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
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
 * loads. Behind the {@code farStore} setting, off by default: every hook here returns at once while it is off. The main
 * thread only marks sections and copies them within {@code farStoreBudget} microseconds a frame ({@link FarCapture});
 * one worker thread does the rest ({@link FarWorker}, {@link FarNodes}). The store lives on disk in {@code <game
 * dir>/shaderscompanion/lod/<world>/<dimension>/} ({@link FarFolders}, {@link FarDisk}); a world or dimension change,
 * leaving the world or turning the setting off closes it (every change written), and the game's end waits for that. In
 * singleplayer, {@code /shaderscompanion lod import} adds the chunks the world has saved ({@link FarImport}), and with
 * {@code farAutoImport} the store reads them by itself: on joining the ones new or changed since it last read them, then
 * each one the integrated server saves ({@link #chunkSaved}).
 * <p>
 * The far terrain drawn from it is {@link FarTerrain}'s (its frame part runs from {@link #frame}); the dev checks' hooks
 * are {@link FarChecks}.
 */
public final class FarStore {
    /** Capturing for {@link #capture}'s level (main thread). The hooks check only this. */
    static boolean active;
    static FarCapture capture;
    static volatile FarWorker worker;
    static int generation;
    private static int cacheMb;
    static volatile String failure;
    /** The import's progress line in the chat: when it was last sent. */
    private static long lastImportChat;
    /**
     * The auto import (farAutoImport): the dimension whose saved chunks the store reads as the integrated server saves
     * them, and the store's generation, set on the main thread and read by the server thread's save hook; null while
     * none runs.
     */
    private static volatile net.minecraft.resources.ResourceKey<Level> autoDimension;
    private static volatile int autoGeneration;

    /** The running store's world key and dimension folder, and its server's key part in multiplayer (main thread). */
    private static String startedKey;
    private static Path startedFolder;
    private static String startedServer;

    // Dev checks (src/verify): the store on for a run and its cache size, without touching the config file
    static boolean forcedOn;
    static int forcedCacheMb;

    private FarStore() {
    }

    static boolean wanted() {
        return failure == null && (forcedOn || Config.FAR_STORE.get());
    }

    /** Called from the frame hook before the world is rendered: starts and stops the store, and copies due sections. */
    public static void frame(Camera camera) {
        // Whether the shader pack reads the far terrain (two counters, no GL)
        FarTextures.frame();
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
            // In multiplayer the world's key waits for the server's hello (its world id), a few seconds at most
            String key = null;
            if (mc.getSingleplayerServer() == null) {
                FarLink.Identity identity = FarLink.identity(mc);
                if (identity.waiting()) {
                    if (capture != null && capture.level() != level) {
                        stop();
                    }
                    return;
                }
                key = FarFolders.serverKey(mc.getCurrentServer(), identity.worldId());
            }
            if (capture == null || capture.level() != level || key != null && !key.equals(startedKey)) {
                stop();
                start(mc, level, camera, key);
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
            FarTerrain.frame(mc, camera);
            chat(mc);
        } catch (Throwable t) {
            failure = t.toString();
            ShadersCompanion.LOGGER.error("Shaders Companion far store stopped after an error", t);
            stop();
        }
    }

    private static void start(Minecraft mc, ClientLevel level, Camera camera, String serverKey) {
        if (worker == null) {
            worker = new FarWorker();
        }
        generation++;
        int gen = generation;
        cacheMb = forcedCacheMb > 0 ? forcedCacheMb : Config.FAR_STORE_CACHE_MB.get();
        String world = serverKey != null ? serverKey : worldKey(mc);
        String dimension = FarFolders.dimensionFolder(level.dimension().location());
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        boolean caveFill = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
        int mb = cacheMb;
        FarBlockFilter filter = FarBlockFilter.fromConfig();
        Path lodRoot = FarFolders.lodRoot(mc.gameDirectory.toPath());
        Path storeRoot = FarFolders.child(lodRoot, world);
        Path folder = FarFolders.child(storeRoot, dimension);
        startedKey = world;
        startedFolder = folder;
        startedServer = mc.getSingleplayerServer() == null ? FarFolders.serverBase(mc.getCurrentServer()) : null;
        // Singleplayer: the store must be this world's, not a deleted one's of the same name (FarWorldId)
        Path saveRoot = mc.getSingleplayerServer() == null ? null : mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        // Sections of the store before are let go by their generation; the old store closes first (the queue's order)
        worker.messages.clear();
        worker.post(w -> w.replaceStore(() -> {
            if (saveRoot != null) {
                FarWorldId.claim(lodRoot, storeRoot, saveRoot);
            }
            return new FarNodes(gen, world, dimension, minY, maxY, caveFill, mb, filter, folder);
        }));
        // Then the stores within farStoreDiskMb, and those of deleted worlds gone (FarCleanup), once this one is open
        Path savesDir = savesDir(mc);
        long diskCap = Config.FAR_STORE_DISK_MB.get() * 1048576L;
        worker.post(w -> {
            if (w.store() != null && w.store().generation == gen) {
                FarCleanup.run(lodRoot, world, savesDir, diskCap);
            }
        });
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
        startAutoImport(mc, level, cx, cz, gen);
    }

    /**
     * The auto import (singleplayer, farAutoImport): the world's saved chunks within the far range that are new or
     * changed since the store last read them, then every complete chunk the integrated server saves in this dimension
     * ({@link #chunkSaved}); read on the worker while little else waits (FarWorker).
     */
    private static void startAutoImport(Minecraft mc, ClientLevel level, int cx, int cz, int gen) {
        autoDimension = null;
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || !Config.FAR_AUTO_IMPORT.get()) {
            return;
        }
        ServerLevel serverLevel = server.getLevel(level.dimension());
        if (serverLevel == null) {
            return;
        }
        FarImport auto = new FarImport(serverLevel, cx, cz, FarTerrain.farRange() / 16 + 1, gen, true);
        autoGeneration = gen;
        autoDimension = level.dimension();
        worker.post(w -> w.startAutoImport(auto));
    }

    /**
     * The integrated server is saving a chunk (its thread, {@code ChunkDataEvent.Save}): a complete chunk of the
     * dimension the auto import runs for goes to the worker, which reads it through the server's chunk storage later.
     */
    public static void chunkSaved(ServerLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        net.minecraft.resources.ResourceKey<Level> dimension = autoDimension;
        FarWorker w = worker;
        if (dimension == null || w == null || level.dimension() != dimension || chunk.getStatus() != net.minecraft.world.level.chunk.ChunkStatus.FULL
                || level.getServer() != Minecraft.getInstance().getSingleplayerServer()) {
            return;
        }
        long pos = chunk.getPos().toLong();
        int gen = autoGeneration;
        w.post(x -> x.autoSaved(pos, gen));
    }

    /**
     * Stops capturing; the worker stores what was already copied, then closes the store (every change written, on the
     * worker: nothing waits here).
     */
    private static void stop() {
        FarTerrain.stopped();
        if (!active && capture == null) {
            return;
        }
        active = false;
        capture = null;
        startedKey = null;
        autoDimension = null;
        generation++;
        if (worker != null) {
            worker.post(w -> w.replaceStore(() -> null));
        }
        ShadersCompanion.LOGGER.info("Shaders Companion far store: stopped");
    }

    /**
     * Whether the store runs for this level under this world key (main thread): the server's far terrain is taken only
     * then (not into a store about to be replaced because the server's hello came after the store started).
     */
    static boolean runningFor(ClientLevel level, String key) {
        return active && level != null && capture != null && capture.level() == level && key != null && key.equals(startedKey);
    }

    /**
     * Starts taking the server's far terrain into the running store (main thread, {@link FarLink}): the worker measures
     * what this server's other stores take on disk, then answers offers with a {@link FarFeed}.
     */
    static void startFeed(int epoch, com.rinkynooble.shaderscompanion.far.ColumnWire.Expect expect, String server) {
        FarWorker w = worker;
        Path folder = startedFolder;
        if (w == null || folder == null) {
            return;
        }
        Path lodRoot = FarFolders.lodRoot(Minecraft.getInstance().gameDirectory.toPath());
        long cap = Config.FAR_SERVER_DISK_MB.get() * 1048576L;
        int gen = generation;
        w.post(x -> {
            if (x.store() == null || x.store().generation != gen) {
                return;
            }
            x.feed = new FarFeed(epoch, expect, cap, FarFolders.serverBytesElsewhere(lodRoot, server, folder), folder);
        });
    }

    /** Stops taking the server's far terrain (the player turned it down; main thread). */
    static void stopFeed() {
        FarWorker w = worker;
        if (w != null) {
            w.post(x -> x.feed = null);
        }
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

    /** The game's saves folder, or null when it can't be told (FarCleanup then keeps every singleplayer store). */
    private static Path savesDir(Minecraft mc) {
        try {
            return mc.getLevelSource().getBaseDir().toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The store's folder name for this world ({@link FarFolders}): the singleplayer save's, or the server's. */
    static String worldKey(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) {
            return FarFolders.singleplayerKey(mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
        }
        return FarFolders.serverKey(mc.getCurrentServer(), null);
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
        if (w.autoWaiting > 0) {
            line.append(String.format(", auto import: %d saved chunks to read (%d read)", w.autoWaiting, w.autoComplete));
        } else if (w.autoWaiting == 0) {
            line.append(String.format(", auto import: up to date (%d read)", w.autoComplete));
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
        FarImport next = new FarImport(level, mc.player.chunkPosition().x, mc.player.chunkPosition().z, radius, generation, false);
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
}
