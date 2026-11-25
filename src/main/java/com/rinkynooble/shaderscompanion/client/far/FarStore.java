package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.Config;
import com.rinkynooble.shaderscompanion.client.FarTextures;
import com.rinkynooble.shaderscompanion.client.Lods;
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
 * <p>
 * Far terrain (M2, M3): while it is drawn ({@link #drawMode}: for the shader pack, which binds its textures, or for the
 * debug view {@code farTerrainDebug}; research/lod/M3-PLAN.md, "Coexistence"), or meshing is on alone ({@code far mesh
 * on}, not saved; or the dev checks), the worker keeps the resident set ({@link FarResidency}) from the camera posted
 * here at most 4 times a second, the mesher thread meshes it ({@link FarMesher}), this frame hook works out the colour
 * table ({@link StateColors}) and takes the finished meshes. While it is drawn they go to the GPU ({@link FarGpuBuffers},
 * within a byte budget a frame; the render thread then keeps only their counts) and {@link FarRenderer} draws them; with
 * meshing alone they are only held and counted (M2a). It is drawn with the store running, {@code farTerrain} not off and,
 * with {@code auto}, neither Distant Horizons drawing far terrain nor a Voxy mod loaded, in a dimension with sky light.
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

    // Far terrain meshing (M2a): the switch (not saved), the last view posted, and the meshes the render thread holds
    private static boolean meshing;
    private static int forcedFarRange;
    private static FarResidency.View postedView;
    private static long postedAt;
    private static FarResidency outboxOf;
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<NodeMesh> meshes = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static long meshQuads;
    private static long meshBytes;
    private static long meshFrameNanos;
    private static long meshFrameMaxNanos;
    private static long meshFrames;
    private static long lastTerrainNanos;

    // Far terrain on the GPU (M2b): far terrain forced on by the dev checks, whether meshes go to the GPU, the frame's view
    private static boolean forcedDebug;
    private static boolean gpuOn;
    private static FarResidency.View frameView;

    // What the far terrain is drawn for this frame (M3), worked out by the frame hook (render thread)
    /** Not drawn. */
    public static final int MODE_NONE = 0;
    /** For the shader pack, which binds far_info: jittered for its TAA. */
    public static final int MODE_PACK = 1;
    /** For the shader pack, which binds far_info_still: not jittered. */
    public static final int MODE_PACK_STILL = 2;
    /** For the debug view over the frame (the pack doesn't read the far terrain), or alone. */
    public static final int MODE_OVERLAY = 3;
    private static int drawMode;
    /** Why far terrain isn't drawn here (farTerrain off, Distant Horizons, Voxy, no sky light), or null. */
    private static String blocked;
    /** Distant Horizons' rendering, asked through its API every 30 frames. */
    private static boolean dhDrawing;
    private static int dhCheckIn;
    /** Dev checks: the debug view even while the pack reads the far terrain (-PdevFarOverlay). */
    private static boolean forcedOverlay;

    private FarStore() {
    }

    private static boolean wanted() {
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
            terrainFrame(mc, camera);
            chat(mc);
        } catch (Throwable t) {
            failure = t.toString();
            ShadersCompanion.LOGGER.error("Shaders Companion far store stopped after an error", t);
            stop();
        }
    }

    /** What the far terrain is drawn for this frame: {@link #MODE_NONE}, {@link #MODE_PACK}, {@link #MODE_PACK_STILL} or {@link #MODE_OVERLAY}. */
    public static int drawMode() {
        return drawMode;
    }

    /**
     * Works out this frame's mode (render thread, while the store runs): nothing while far terrain is off or blocked
     * here; for the pack while it binds the info texture (as far_info, jittered, or far_info_still); otherwise the debug
     * view when it is on ({@code farTerrainDebug}, the dev checks, {@code far show alone}, which also wins over the pack).
     */
    private static int decideMode(Minecraft mc) {
        blocked = blockedReason(mc);
        if (!active || failure != null || !Config.ENABLED.get() || blocked != null) {
            return MODE_NONE;
        }
        boolean debugView = forcedDebug || Config.FAR_TERRAIN_DEBUG.get() || FarRenderer.alone;
        if (forcedOverlay || FarRenderer.alone) {
            return debugView ? MODE_OVERLAY : MODE_NONE;
        }
        int demand = FarTextures.demand();
        if (demand != FarTextures.NOT_BOUND) {
            return demand == FarTextures.BOUND_JITTERED ? MODE_PACK : MODE_PACK_STILL;
        }
        return debugView ? MODE_OVERLAY : MODE_NONE;
    }

    /** The farTerrain setting: auto, companion or off (the dev checks turn off into auto). */
    private static String farTerrainSetting() {
        String setting = Config.FAR_TERRAIN.get();
        return forcedDebug && setting.equals("off") ? "auto" : setting;
    }

    /** Why far terrain isn't drawn here, or null (M3-PLAN.md, "Coexistence"). */
    private static String blockedReason(Minecraft mc) {
        String setting = farTerrainSetting();
        if (setting.equals("off")) {
            return "farTerrain is off (/shaderscompanion far on, or far mode auto)";
        }
        if (mc.level != null && !mc.level.dimensionType().hasSkyLight()) {
            return "no sky light in this dimension";
        }
        if (setting.equals("auto")) {
            if (--dhCheckIn <= 0) {
                dhCheckIn = 30;
                dhDrawing = Lods.distantHorizonsDrawing();
            }
            if (dhDrawing) {
                return "Distant Horizons draws far terrain (farTerrain = companion draws the mod's too)";
            }
            String voxy = Lods.voxyLoaded();
            if (voxy != null) {
                return "a Voxy mod (" + voxy + ") is loaded (farTerrain = companion draws the mod's far terrain too)";
            }
        }
        return null;
    }

    /** Meshing runs: the far terrain is drawn (unless it can't draw here) or {@code far mesh on}. */
    private static boolean meshingOn() {
        return meshing || drawMode != MODE_NONE && FarRenderer.problem() == null;
    }

    /** Meshes go to the GPU and the debug view draws them (render thread). */
    static boolean gpuOn() {
        return gpuOn;
    }

    /** This frame's camera and ring settings (render thread; null while nothing meshes). */
    static FarResidency.View frameView() {
        return frameView;
    }

    /** The render thread's far terrain part of the last frame (camera post, colour table, meshes, uploads), nanoseconds. */
    static long terrainFrameNanos() {
        return lastTerrainNanos;
    }

    /**
     * Turns the GPU path on or off (render thread). On: every mesh the render thread holds whole is marked for upload.
     * Off: the GPU's buffers are freed; meshes already uploaded are held only as counts, so a residency that keeps
     * meshing (far mesh on) starts again.
     */
    private static void setGpu(boolean on) {
        if (on == gpuOn) {
            return;
        }
        gpuOn = on;
        if (on) {
            FarGpuBuffers b = FarRenderer.buffers();
            for (NodeMesh mesh : meshes.values()) {
                if (!mesh.stubbed()) {
                    b.markDirty(mesh.key);
                }
            }
            b.resetUploadClock();
            return;
        }
        FarRenderer.release();
        boolean stubs = false;
        for (NodeMesh mesh : meshes.values()) {
            stubs |= mesh.stubbed();
        }
        if (stubs && worker != null) {
            worker.post(FarWorker::restartResidency);
        }
    }

    /** The far terrain's part of the frame: the switch, the camera for the residency, the colour table, the finished meshes. */
    private static void terrainFrame(Minecraft mc, Camera camera) {
        FarWorker w = worker;
        drawMode = decideMode(mc);
        boolean on = meshingOn();
        if (w.meshingWanted != on) {
            w.meshingWanted = on;
            postedView = null;
        }
        if (!on) {
            setGpu(false);
            takeMeshes(null);
            frameView = null;
            lastTerrainNanos = 0;
            return;
        }
        long start = System.nanoTime();
        FarResidency.View view = new FarResidency.View(camera.getPosition().x, camera.getPosition().y, camera.getPosition().z, mc.options.getEffectiveRenderDistance(),
                mc.getWindow().getHeight(), mc.options.fov().get(), Config.FAR_PIXELS_PER_VOXEL.get(), farRange());
        frameView = view;
        if (postedView == null || !view.sameRings(postedView) || view.distance(postedView) >= 32
                || start - postedAt >= 250_000_000L && view.distance(postedView) >= 1) {
            postedView = view;
            postedAt = start;
            w.postView(view);
        }
        StateIds states = w.publishedStates;
        BiomeIds biomes = w.publishedBiomes;
        if (states != null && biomes != null && mc.level != null) {
            StateColors.frame(states, biomes, mc.level);
        }
        setGpu(drawMode != MODE_NONE && FarRenderer.available());
        takeMeshes(w.residency);
        if (gpuOn) {
            FarGpuBuffers b = FarRenderer.buffers();
            b.syncColours();
            b.upload(meshes);
        }
        long nanos = System.nanoTime() - start;
        lastTerrainNanos = nanos;
        meshFrameNanos += nanos;
        meshFrameMaxNanos = Math.max(meshFrameMaxNanos, nanos);
        meshFrames++;
    }

    /** Most render-thread time a frame spent taking meshes from the outbox (a teleport drops thousands at once). */
    private static final long TAKE_NANOS = 300_000L;

    /**
     * The render thread's side of the meshes: takes the residency's outbox in order (a mesh replaces the node's last
     * one, a drop removes it), within {@link #TAKE_NANOS} a frame, and marks the nodes for the GPU's uploads while the
     * debug view is on (FarGpuBuffers.upload, after this, within its byte budget).
     */
    private static void takeMeshes(FarResidency r) {
        if (r != outboxOf) {
            meshes.clear();
            meshQuads = 0;
            meshBytes = 0;
            outboxOf = r;
            if (gpuOn && FarRenderer.hasBuffers()) {
                FarRenderer.buffers().clearAll();
            }
        }
        if (r == null) {
            return;
        }
        long start = System.nanoTime();
        int taken = 0;
        Object item;
        while ((++taken & 63) != 0 || System.nanoTime() - start < TAKE_NANOS) {
            item = r.outbox.poll();
            if (item == null) {
                break;
            }
            NodeMesh old;
            long key;
            if (item instanceof NodeMesh mesh) {
                key = mesh.key;
                old = meshes.put(key, mesh);
                meshQuads += mesh.quadCount;
                meshBytes += mesh.bytes();
            } else {
                key = ((FarResidency.Drop) item).key();
                old = meshes.remove(key);
            }
            if (old != null) {
                meshQuads -= old.quadCount;
                meshBytes -= old.bytes();
            }
            if (gpuOn) {
                FarRenderer.buffers().markDirty(key);
            }
        }
    }

    private static int farRange() {
        return forcedFarRange > 0 ? forcedFarRange : Config.FAR_RANGE.get();
    }

    /** The far terrain's status line ("Far terrain: ..."): what it is drawn for, then the residency and the GPU. */
    public static String terrainStatus() {
        FarWorker w = worker;
        if (!(forcedOn || Config.FAR_STORE.get()) && !forcedDebug) {
            return "off (/shaderscompanion far on turns it on with the far store)";
        }
        if (!active || w == null) {
            return "waiting for the far store (farStore)";
        }
        String path = switch (drawMode) {
            case MODE_PACK -> "for the shader pack, jittered for its TAA";
            case MODE_PACK_STILL -> "for the shader pack, not jittered (it binds far_info_still)";
            case MODE_OVERLAY -> forcedOverlay || FarRenderer.alone ? "debug view" + (FarRenderer.alone ? ", alone" : "")
                    : "debug view: the shader pack doesn't read far terrain";
            default -> !Config.ENABLED.get() ? "off with the rest of the mod's drawing (/shaderscompanion on)"
                    : blocked != null ? "off: " + blocked
                    : FarRenderer.problem() != null ? "off: " + FarRenderer.problem()
                    : "off: the shader pack doesn't read far terrain and the debug view is hidden (/shaderscompanion far show)";
        };
        if (drawMode != MODE_NONE && farTerrainSetting().equals("companion") && (Lods.distantHorizonsDrawing() || Lods.voxyLoaded() != null)) {
            path += " (farTerrain = companion: " + (Lods.voxyLoaded() != null ? "a Voxy mod" : "Distant Horizons") + " draws far terrain too)";
        }
        if (!meshingOn()) {
            return path;
        }
        String residency = w.meshStatus == null ? "starting" : w.meshStatus;
        String held = gpuOn ? FarRenderer.status() : String.format("the render thread holds %d meshes (%d quads, %.1f MB)%s", meshes.size(), meshQuads,
                meshBytes / 1048576.0, FarRenderer.problem() != null ? "; it can't draw: " + FarRenderer.problem() : "");
        return String.format("%s; %s; %s; the far store's frame part %.0f us a frame (max %.0f); %s", path, residency, held,
                meshFrames == 0 ? 0.0 : meshFrameNanos / 1.0e3 / meshFrames, meshFrameMaxNanos / 1.0e3, StateColors.status());
    }

    /** The {@code far mesh on|off} command: meshing on or off for this session. */
    public static String setMeshing(boolean on) {
        meshing = on;
        return on ? "Far terrain meshing on (needs farStore on; /shaderscompanion far show draws it)" : "Far terrain meshing off (the debug view still meshes while it is on)";
    }

    /**
     * The {@code far on} and {@code far off} commands: the store, {@code farTerrain} (auto, or off) and the debug view
     * for packs that don't read the far terrain together, all saved in the config.
     */
    public static String setFarTerrain(boolean on) {
        Config.FAR_STORE.set(on);
        Config.FAR_STORE.save();
        Config.FAR_TERRAIN.set(on ? "auto" : "off");
        Config.FAR_TERRAIN.save();
        Config.FAR_TERRAIN_DEBUG.set(on);
        Config.FAR_TERRAIN_DEBUG.save();
        FarRenderer.alone = false;
        return on ? "Far terrain on: the store fills from the chunks you load (in singleplayer, /shaderscompanion lod import adds the world's saved chunks); "
                + "a shader pack that reads it (ABZA Shader 3) lights it, otherwise the debug view shows it" : "Far terrain off (what is stored stays on disk)";
    }

    /** The {@code far mode auto|companion|off} command: whose far terrain is drawn (saved in the config). */
    public static String setFarTerrainMode(String mode) {
        Config.FAR_TERRAIN.set(mode);
        Config.FAR_TERRAIN.save();
        String needs = !(forcedOn || Config.FAR_STORE.get()) ? "; it needs farStore on (/shaderscompanion far on)" : "";
        return switch (mode) {
            case "companion" -> "Far terrain: the mod's own, even while Distant Horizons draws far terrain or a Voxy mod is loaded" + needs;
            case "off" -> "Far terrain: none of the mod's (the store keeps filling while farStore is on)";
            default -> "Far terrain: the mod's own, unless Distant Horizons draws far terrain or a Voxy mod is loaded" + needs;
        };
    }

    /**
     * The {@code far show [alone]} and {@code far hide} commands: the debug view on (saved in the config) or off. Over
     * the frame it shows only while the shader pack doesn't draw the far terrain itself; alone, it shows always.
     */
    public static String setDebugView(boolean show, boolean alone) {
        Config.FAR_TERRAIN_DEBUG.set(show);
        Config.FAR_TERRAIN_DEBUG.save();
        FarRenderer.alone = alone;
        if (!show) {
            return "Far terrain debug view off";
        }
        String needs = !(forcedOn || Config.FAR_STORE.get()) ? "; it needs farStore on (/shaderscompanion far on)" : "";
        if (!alone && (drawMode == MODE_PACK || drawMode == MODE_PACK_STILL)) {
            return "The shader pack draws the far terrain itself: the debug view would paint over it (where the chunk terrain's depth is the sky, the pack's "
                    + "far terrain is), so it shows only while the pack doesn't; far show alone shows the far terrain alone";
        }
        return (alone ? "Far terrain debug view on, alone (only the far terrain, over a plain sky)" : "Far terrain debug view on (far terrain where the screen shows sky)") + needs;
    }

    /** The {@code far stats} lines, from the worker. */
    public static CompletableFuture<String[]> terrainStats() {
        if (!active) {
            return CompletableFuture.completedFuture(new String[]{"The far store is off (farStore in config/shaderscompanion-client.toml)"});
        }
        FarWorker w = worker;
        return w.ask(store -> {
            FarResidency r = w.residency;
            FarMesher m = w.mesher;
            String[] lines = r == null ? new String[]{"Far terrain meshing is off (/shaderscompanion far mesh on)"} : r.lines();
            if (m != null) {
                lines = java.util.Arrays.copyOf(lines, lines.length + 1);
                lines[lines.length - 1] = String.format("Mesher thread: %.1f s of processor time so far, %d jobs failed; %d blobs decoded, neighbours' face layers %d "
                        + "from its cache, %d decoded", cpuNanos(m.thread) / 1.0e9, m.failures, m.decodes, m.layerHits, m.layerMisses);
            }
            return lines;
        });
    }

    private static long cpuNanos(Thread thread) {
        return java.lang.management.ManagementFactory.getThreadMXBean().getThreadCpuTime(thread.getId());
    }

    /**
     * The {@code far meshmap} command and the dev checks: a level's mesh map over a square (FarMeshMap) and LodMap's
     * map of the same square, both in screenshots/.
     */
    public static CompletableFuture<String[]> meshMap(int level, int minX, int minZ, int size, String name) {
        if (!active) {
            return CompletableFuture.completedFuture(new String[]{"The far store is off (farStore in config/shaderscompanion-client.toml)"});
        }
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
        return worker.ask(store -> {
            if (store == null) {
                return new String[]{"The far store is off"};
            }
            try {
                String mesh = FarMeshMap.write(store, level, minX, minZ, size, size, dir.resolve(name + "-mesh-L" + level + ".png"));
                String lod = LodMap.write(store, level, minX, minZ, size, size, dir.resolve(name + "-lod-L" + level + ".png"));
                return new String[]{"Saved screenshots/" + name + "-mesh-L" + level + ".png: " + mesh, "Saved screenshots/" + name + "-lod-L" + level + ".png: " + lod};
            } catch (java.io.IOException e) {
                return new String[]{"Mesh map failed: " + e};
            }
        });
    }

    /** The mesh map around the camera, radius nodes of that level each way (the command). */
    public static CompletableFuture<String[]> meshMapAroundCamera(int level, int radius) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        int nodeBlocks = Node.SIZE << level;
        int nx = Math.floorDiv(Mth.floor(camera.getPosition().x), nodeBlocks);
        int nz = Math.floorDiv(Mth.floor(camera.getPosition().z), nodeBlocks);
        return meshMap(level, (nx - radius) * nodeBlocks, (nz - radius) * nodeBlocks, (2 * radius + 1) * nodeBlocks, "shaderscompanion");
    }

    // Far terrain dev checks (M2a, M2b)

    /**
     * Dev checks (M2b, M3a): far terrain on for the run, without the config file: for the pack when it binds the far
     * textures, else in the debug view (meshing follows it).
     */
    public static void drawForChecks(boolean on) {
        forcedDebug = on;
    }

    /** Dev checks (M3a): the debug view even while the pack reads the far terrain (-PdevFarOverlay). */
    public static void overlayForChecks(boolean on) {
        forcedOverlay = on;
    }

    /** Dev checks (M2b): every node meshed again from scratch (a new residency), e.g. after the cave filling changed. */
    public static void restartMeshingForChecks() {
        if (worker != null) {
            worker.post(FarWorker::restartResidency);
        }
    }

    /**
     * Dev checks (M2b): the GPU's side: meshes waiting to upload (-1 while the GPU path is off), meshes on the GPU,
     * bytes uploaded since the clock was reset, the first and last upload's time since then (System.nanoTime), meshes
     * that didn't fit; uploads' render-thread nanos, frames with uploads and the most in one frame.
     */
    public static long[] gpuNumbersForChecks(boolean resetClock) {
        if (!gpuOn || !FarRenderer.hasBuffers()) {
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
    public static void meshingForChecks(boolean on) {
        meshing = on;
    }

    /** Dev checks: the far range for the run (0: the config's). */
    public static void farRangeForChecks(int blocks) {
        forcedFarRange = blocks;
    }

    /** Dev checks: the first level whose dark air is filled (FarMesher.fillFromLevel; nodes meshed from now on). */
    public static void caveFillFromForChecks(int level) {
        FarMesher.fillFromLevel = level;
    }

    /** Dev checks: corner shading in the quads (FarMesher.cornerShading; nodes meshed from now on). */
    public static void cornerShadingForChecks(boolean on) {
        FarMesher.cornerShading = on;
    }

    /** Dev checks: the residency's numbers (FarResidency.numbers), or null while it isn't running. */
    public static CompletableFuture<long[]> terrainNumbersForChecks() {
        if (!active) {
            return CompletableFuture.completedFuture(null);
        }
        FarWorker w = worker;
        return w.ask(store -> w.residency == null ? null : w.residency.numbers());
    }

    /** Dev checks: the mesher thread's processor time so far, in nanoseconds (-1 before it exists). */
    public static long mesherCpuNanosForChecks() {
        FarWorker w = worker;
        return w == null || w.mesher == null ? -1 : cpuNanos(w.mesher.thread);
    }

    /**
     * Dev checks: the colour table's numbers (StateColors.numbers), then the render thread's far terrain part of the
     * frame: nanos, max nanos, frames (the counters start again).
     */
    public static long[] colourNumbersForChecks(boolean reset) {
        long[] c = StateColors.numbers();
        long[] out = java.util.Arrays.copyOf(c, c.length + 3);
        out[c.length] = meshFrameNanos;
        out[c.length + 1] = meshFrameMaxNanos;
        out[c.length + 2] = meshFrames;
        if (reset) {
            meshFrameNanos = 0;
            meshFrameMaxNanos = 0;
            meshFrames = 0;
        }
        return out;
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
        drawMode = MODE_NONE;
        setGpu(false);
        takeMeshes(null);
        frameView = null;
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
