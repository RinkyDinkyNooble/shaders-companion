package com.rinkynooble.shaderscompanion.client.far;

import com.mojang.blaze3d.shaders.FogShape;
import com.rinkynooble.shaderscompanion.client.Config;
import com.rinkynooble.shaderscompanion.client.FarTextures;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.client.event.ViewportEvent;

/**
 * The far terrain's drawing state, worked out each frame by the render thread while the far store runs
 * ({@link FarStore#frame} calls {@link #frame}): what it is drawn for ({@link #drawMode}: for the shader pack, which
 * binds its textures, or for the debug view {@code farTerrainDebug}; research/lod/M3-PLAN.md, "Coexistence"), the camera
 * posted to the worker's resident set ({@link FarResidency}) at most 4 times a second, the colour table
 * ({@link StateColors}), and the finished meshes taken from the residency's outbox and, while the far terrain is drawn,
 * sent to the GPU ({@link FarGpuBuffers}, within a byte budget a frame) for {@link FarRenderer} and {@link FarViews}.
 * With meshing alone ({@code far mesh on}, the dev checks) the meshes are only held and counted (M2a). Also the commands
 * that switch it. It is drawn with the store running, {@code farTerrain} not off and, with {@code auto}, neither Distant
 * Horizons drawing far terrain nor a Voxy mod loaded, in every dimension.
 */
public final class FarTerrain {
    // Far terrain meshing (M2a): the switch (not saved), the last view posted, and the meshes the render thread holds
    static boolean meshing;
    static int forcedFarRange;
    private static FarResidency.View postedView;
    private static long postedAt;
    private static FarResidency outboxOf;
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<NodeMesh> meshes = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static long meshQuads;
    private static long meshBytes;
    static long meshFrameNanos;
    static long meshFrameMaxNanos;
    static long meshFrames;
    private static long lastTerrainNanos;

    // Far terrain on the GPU (M2b): far terrain forced on by the dev checks, whether meshes go to the GPU, the frame's view
    static boolean forcedDebug;
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
    /** Why far terrain isn't drawn here (farTerrain off, Distant Horizons, Voxy), or null. */
    private static String blocked;
    /** Distant Horizons' rendering, asked through its API every 30 frames. */
    private static boolean dhDrawing;
    private static int dhCheckIn;
    /** Dev checks: the debug view even while the pack reads the far terrain (-PdevFarOverlay). */
    static boolean forcedOverlay;
    /**
     * The far terrain is drawn into the mod's own views this frame (M4, FarViews): far terrain on, farTerrainInViews on and
     * the shader pack reading the cube or the mirror view; also while Distant Horizons or a Voxy mod draws the screen's
     * far terrain, since nothing else draws far terrain into the views.
     */
    private static boolean viewsOn;

    private FarTerrain() {
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
        if (!FarStore.active || FarStore.failure != null || !Config.ENABLED.get() || blocked != null) {
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

    /** The debug view's fog starts at this share of the far range and ends at the far range ({@link #onRenderFog}, far_overlay.fsh). */
    static final float FOG_START = 0.25f;
    /** Dev checks: vanilla's fog left as it is with the debug view, as before 3.0.1 (FarChecks.fogMatchOff). */
    static volatile boolean fogMatchOff;

    /**
     * Shaders off, with the debug view drawn: vanilla's terrain fog moves out to the far terrain's, a sphere from a
     * quarter of the far range to the far range, the curve far_overlay.fsh fogs the far terrain with. Vanilla's own fog
     * ends at the render distance (a cylinder, as Embeddium draws the chunk terrain), so the chunk terrain's last blocks
     * faded into the fog colour, which the debug view, drawn only where the screen shows sky, couldn't fill: a band of
     * sky between the two (the user, 3.0.0). Left alone under water, in lava or powder snow, with blindness or darkness,
     * in thick fog (the Nether, a boss) and while a shader pack is in use.
     */
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (fogMatchOff || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN || event.getType() != FogType.NONE || drawMode != MODE_OVERLAY || FarRenderer.alone) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || IrisApi.getInstance().isShaderPackInUse()) {
            return;
        }
        Camera camera = event.getCamera();
        if (camera.getEntity() instanceof LivingEntity living && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        BlockPos at = camera.getBlockPosition();
        if (mc.level.effects().isFoggyAt(at.getX(), at.getZ()) || mc.gui.getBossOverlay().shouldCreateWorldFog()) {
            return;
        }
        float range = farRange();
        if (range <= event.getFarPlaneDistance()) {
            return;
        }
        event.setNearPlaneDistance(range * FOG_START);
        event.setFarPlaneDistance(range);
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
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
        if (setting.equals("auto")) {
            if (--dhCheckIn <= 0) {
                dhCheckIn = 30;
                dhDrawing = FarCoexistence.distantHorizonsDrawing();
            }
            if (dhDrawing) {
                return "Distant Horizons draws far terrain (farTerrain = companion draws the mod's too)";
            }
            String voxy = FarCoexistence.voxyLoaded();
            if (voxy != null) {
                return "a Voxy mod (" + voxy + ") is loaded (farTerrain = companion draws the mod's far terrain too)";
            }
        }
        return null;
    }

    /** Meshing runs: the far terrain is drawn on screen or into the views (unless it can't draw here), or {@code far mesh on}. */
    private static boolean meshingOn() {
        return meshing || (drawMode != MODE_NONE || viewsOn) && FarRenderer.problem() == null;
    }

    /** Whether the far terrain goes into the mod's views this frame (see {@link #viewsOn}; render thread, while the store runs). */
    private static boolean viewsWanted() {
        return FarStore.active && FarStore.failure == null && Config.ENABLED.get() && Config.FAR_TERRAIN_IN_VIEWS.get() && !farTerrainSetting().equals("off")
                && FarViews.viewsRead();
    }

    /** The views draw the far terrain this frame: on for them and on the GPU (render thread). */
    static boolean viewsDraw() {
        return viewsOn && gpuOn && frameView != null;
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
        if (stubs && FarStore.worker != null) {
            FarStore.worker.post(FarWorker::restartResidency);
        }
    }

    /** The far terrain's part of the frame: the switch, the camera for the residency, the colour table, the finished meshes. */
    static void frame(Minecraft mc, Camera camera) {
        FarWorker w = FarStore.worker;
        drawMode = decideMode(mc);
        viewsOn = viewsWanted();
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
        // While the pack binds the far shadow map, the nodes inside the chunk terrain stay resident as its shadow casters
        boolean keepInside = FarTextures.shadowWanted() && (drawMode == MODE_PACK || drawMode == MODE_PACK_STILL);
        FarResidency.View view = new FarResidency.View(camera.getPosition().x, camera.getPosition().y, camera.getPosition().z, mc.options.getEffectiveRenderDistance(),
                mc.getWindow().getHeight(), mc.options.fov().get(), Config.FAR_PIXELS_PER_VOXEL.get(), farRange(), keepInside);
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
        setGpu((drawMode != MODE_NONE || viewsOn) && FarRenderer.available());
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

    static int farRange() {
        return forcedFarRange > 0 ? forcedFarRange : Config.FAR_RANGE.get();
    }

    /** The far terrain's status line ("Far terrain: ..."): what it is drawn for, then the residency and the GPU. */
    public static String terrainStatus() {
        FarWorker w = FarStore.worker;
        if (!(FarStore.forcedOn || Config.FAR_STORE.get()) && !forcedDebug) {
            return "off (/shaderscompanion far on turns it on with the far store)";
        }
        if (!FarStore.active || w == null) {
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
        if (drawMode != MODE_NONE && farTerrainSetting().equals("companion") && (FarCoexistence.distantHorizonsDrawing() || FarCoexistence.voxyLoaded() != null)) {
            path += " (farTerrain = companion: " + (FarCoexistence.voxyLoaded() != null ? "a Voxy mod" : "Distant Horizons") + " draws far terrain too)";
        }
        if (viewsOn) {
            path += drawMode == MODE_NONE ? "; drawn into the mod's views only: " + FarViews.status() : "; in the mod's views: " + FarViews.status();
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
     * The {@code far on} and {@code far off} commands: the store and {@code farTerrain} (auto, or off) together, saved in
     * the config; off also turns the debug view off. On leaves the debug view as it is (until 3.0.1 it turned it on too,
     * so with shaders off the far terrain showed in the debug view without being asked for).
     */
    public static String setFarTerrain(boolean on) {
        Config.FAR_STORE.set(on);
        Config.FAR_STORE.save();
        Config.FAR_TERRAIN.set(on ? "auto" : "off");
        Config.FAR_TERRAIN.save();
        if (!on) {
            Config.FAR_TERRAIN_DEBUG.set(false);
            Config.FAR_TERRAIN_DEBUG.save();
        }
        FarRenderer.alone = false;
        return on ? "Far terrain on: the store fills from the chunks you load (in singleplayer also from the world's saved chunks, by itself with farAutoImport, "
                + "or /shaderscompanion lod import); "
                + "a shader pack that reads it (ABZA Shader 3) draws it; /shaderscompanion far show shows it without one, in the debug view" : "Far terrain off (what is stored stays on disk)";
    }

    /** The {@code far mode auto|companion|off} command: whose far terrain is drawn (saved in the config). */
    public static String setFarTerrainMode(String mode) {
        Config.FAR_TERRAIN.set(mode);
        Config.FAR_TERRAIN.save();
        String needs = !(FarStore.forcedOn || Config.FAR_STORE.get()) ? "; it needs farStore on (/shaderscompanion far on)" : "";
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
        String needs = !(FarStore.forcedOn || Config.FAR_STORE.get()) ? "; it needs farStore on (/shaderscompanion far on)" : "";
        if (!alone && (drawMode == MODE_PACK || drawMode == MODE_PACK_STILL)) {
            return "The shader pack draws the far terrain itself: the debug view would paint over it (where the chunk terrain's depth is the sky, the pack's "
                    + "far terrain is), so it shows only while the pack doesn't; far show alone shows the far terrain alone";
        }
        return (alone ? "Far terrain debug view on, alone (only the far terrain, over a plain sky)" : "Far terrain debug view on (far terrain where the screen shows sky)") + needs;
    }

    /** The {@code far stats} lines, from the worker. */
    public static CompletableFuture<String[]> terrainStats() {
        if (!FarStore.active) {
            return CompletableFuture.completedFuture(new String[]{"The far store is off (farStore in config/shaderscompanion-client.toml)"});
        }
        FarWorker w = FarStore.worker;
        return w.ask(store -> {
            FarResidency r = w.residency;
            FarMesher m = w.mesher;
            String[] lines = r == null ? new String[]{"Far terrain meshing is off (/shaderscompanion far mesh on)"} : r.lines();
            if (m != null) {
                lines = java.util.Arrays.copyOf(lines, lines.length + 1);
                lines[lines.length - 1] = String.format("Mesher thread: %.1f s of processor time so far, %d jobs failed; %d blobs decoded, neighbours' face layers %d "
                        + "from its cache, %d decoded; %d dark air voxels left open beside unknown terrain", cpuNanos(m.thread) / 1.0e9, m.failures, m.decodes,
                        m.layerHits, m.layerMisses, m.unfilled);
            }
            return lines;
        });
    }

    static long cpuNanos(Thread thread) {
        return java.lang.management.ManagementFactory.getThreadMXBean().getThreadCpuTime(thread.getId());
    }

    /**
     * The {@code far meshmap} command and the dev checks: a level's mesh map over a square (FarMeshMap) and LodMap's
     * map of the same square, both in screenshots/.
     */
    public static CompletableFuture<String[]> meshMap(int level, int minX, int minZ, int size, String name) {
        if (!FarStore.active) {
            return CompletableFuture.completedFuture(new String[]{"The far store is off (farStore in config/shaderscompanion-client.toml)"});
        }
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
        return FarStore.worker.ask(store -> {
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

    /** The far store stopped: nothing drawn, the GPU's buffers freed, the meshes let go. */
    static void stopped() {
        drawMode = MODE_NONE;
        viewsOn = false;
        setGpu(false);
        takeMeshes(null);
        frameView = null;
    }
}
