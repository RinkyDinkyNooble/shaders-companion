package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.far.FarViews;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Runs the mod's views each frame: six cube faces around the camera, and the mirror view ({@link MirrorView}) when the
 * screen shows a reflecting surface, drawn by {@link ViewRenderer} as surface data (see API.md). Runs once per frame
 * before the world is rendered, outside Oculus's world pass, and restores every GL state it touches. Also collects the
 * entities the views draw and writes the cube's info texture.
 */
public final class CubeView {
    // Face directions: forward and up; right = forward x up. Face i sits at column i % 3, row i / 3 of the atlas.
    static final int[][] FORWARD = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    static final int[][] UP = {{0, 1, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, 1, 0}};
    static final int[][] RIGHT = new int[6][];
    static final String[] FACE_NAMES = {"+X", "-X", "+Y", "-Y", "+Z", "-Z"};

    static final float NEAR = 0.05F;

    /** CPU time per frame for drawing entities and block entities into the cube, nearest first. */
    private static final long ENTITY_BUDGET_NANOS = 1_000_000L;
    private static final long BLOCK_ENTITY_BUDGET_NANOS = 500_000L;
    private static final int DREW_CUBE = 1;
    private static final int DREW_MIRROR = 2;

    static {
        for (int i = 0; i < 6; i++) {
            int[] f = FORWARD[i];
            int[] u = UP[i];
            RIGHT[i] = new int[]{f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0]};
        }
    }

    // Status for the debug overlay and the status command.
    static String status = "waiting for the first frame";
    static int faceSize;
    static int frameCounter;
    static long cpuNanos;
    /** CPU time for the local player before this frame and the mobs and block entities collected after the last one. */
    static long playerNanos;
    static long collectNanos;
    static long mirrorNanos;
    static int sectionsQueued;
    static int entitiesDrawn;
    static int blockEntitiesDrawn;
    static int entitiesReused;
    static boolean mirrorPlayerDrawn;
    static boolean hookCalled;

    private static boolean failed;
    private static boolean linked;
    private static int nextFace;
    private static final double[][] faceOrigin = new double[6][3];
    private static final int[] faceFrame = {-1, -1, -1, -1, -1, -1};
    private static float lastFar;
    private static float lastMaxDistance;
    /** Whether the pack reads the cube, the mirror view, and the mirror view's surfaces behind water and glass. */
    private static final TextureDemand cubeDemand = new TextureDemand(CompanionTexture.CUBE);
    private static final TextureDemand mirrorDemand = new TextureDemand(CompanionTexture.MIRROR);
    private static final TextureDemand behindDemand = new TextureDemand(CompanionTexture.MIRROR_BEHIND);
    /** Mobs and block entities, collected after the world is drawn for the next frame's views. */
    private static final EntityCapture entities = new EntityCapture();
    /** The camera those vertices are relative to, and the frame hook's count when they were collected. */
    private static Vec3 entityCamera = Vec3.ZERO;
    private static long entityHookFrame = -2;
    private static Object entityLevel;
    /** The local player in first person, collected before the world is drawn for this frame's mirror view. */
    private static final EntityCapture playerEntities = new EntityCapture();
    private static long hookFrame;
    /** Set while this frame drew views, so entities are collected after it for the next one. */
    private static boolean collectAfterFrame;
    private static boolean cubeWanted;

    private static final ViewTarget cubeTarget = new ViewTarget("cube", CubeTextures.ALBEDO, CubeTextures.DATA, CubeTextures.DISTANCE, CubeTextures.PREVIEW);
    private static FloatBuffer infoBuffer;

    private CubeView() {
    }

    /**
     * Called from the frame hook, on the render thread, before the world is rendered.
     *
     * @param view the main view's rotation, the pose the world is about to be drawn with
     */
    public static void renderFrame(Camera camera, float partialTick, Matrix4f view) {
        hookCalled = true;
        hookFrame++;
        collectAfterFrame = false;
        // Whether the pack reads the views, for the far store's next frame (set again below while it does)
        FarViews.viewsRead(false);
        if (failed) {
            return;
        }
        int drew = 0;
        try {
            drew = renderFrameUnguarded(camera, partialTick, view);
        } catch (Throwable t) {
            failed = true;
            status = "stopped after an error: " + t;
            ShadersCompanion.LOGGER.error("Shaders Companion stopped drawing after an error", t);
        }
        boolean cubeGone = (drew & DREW_CUBE) == 0;
        boolean mirrorGone = (drew & DREW_MIRROR) == 0;
        if (cubeGone && linked || mirrorGone && MirrorView.linked()) {
            // Marking a view as gone writes its info texture: with the GL state saved, like the drawing
            GlState saved = GlState.capture();
            try {
                if (cubeGone) {
                    unlink();
                }
                if (mirrorGone) {
                    MirrorView.unlink();
                }
            } finally {
                saved.restore();
            }
        } else if (mirrorGone) {
            MirrorView.unlink();
        }
        collectAfterFrame = drew != 0 && !failed;
        cubeWanted = (drew & DREW_CUBE) != 0;
    }

    /**
     * Called from the frame hook after the world is rendered: collects the mobs and block entities for the next
     * frame's views. Drawing them here, after the game's own draw, leaves entity renderers that keep per-frame
     * animation state as they are without this mod.
     */
    public static void collectEntitiesAfterFrame(Camera camera, float partialTick) {
        if (!collectAfterFrame || failed) {
            collectNanos = 0L;
            return;
        }
        collectAfterFrame = false;
        Minecraft mc = Minecraft.getInstance();
        GlState saved = null;
        long start = System.nanoTime();
        try {
            SodiumWorldRenderer worldRenderer = SodiumWorldRenderer.instanceNullable();
            RenderSectionManager sections = worldRenderer == null ? null : EmbeddiumAccess.sectionManager(worldRenderer);
            if (mc.level == null || sections == null) {
                return;
            }
            saved = GlState.capture();
            // The faces the next frame draws, and this frame's whole mirror view (the next one's is close to it, and
            // its drawn part may be larger)
            int faces = cubeWanted ? Config.FACES_PER_FRAME.get() : 0;
            BoxTest seen = MirrorView.active() ? MirrorView.seenWhole() : BoxTest.NONE;
            for (int i = 0; i < faces; i++) {
                int face = (nextFace + i) % 6;
                seen = seen.or((minX, minY, minZ, maxX, maxY, maxZ) -> boxInFace(face, minX, minY, minZ, maxX, maxY, maxZ));
            }
            collectEntities(mc, camera, partialTick, seen, EmbeddiumAccess.regions(sections));
            entityCamera = camera.getPosition();
            entityHookFrame = hookFrame;
            entityLevel = mc.level;
        } catch (Throwable t) {
            failed = true;
            status = "stopped after an error: " + t;
            ShadersCompanion.LOGGER.error("Shaders Companion stopped drawing after an error", t);
        } finally {
            if (saved != null) {
                saved.restore();
            }
            collectNanos = System.nanoTime() - start;
        }
    }

    /**
     * Draws this frame's faces and mirror view. Returns DREW_CUBE when the cube's info was written (otherwise the pack
     * must not use the cube) and DREW_MIRROR when the mirror view's was.
     */
    private static int renderFrameUnguarded(Camera camera, float partialTick, Matrix4f view) throws IllegalAccessException {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !Config.ENABLED.get()) {
            status = mc.level == null ? "no world loaded" : "turned off (/shaderscompanion on, or enabled in the config)";
            return 0;
        }
        if (!IrisApi.getInstance().isShaderPackInUse()) {
            status = "no shader pack in use";
            cubeDemand.reset();
            mirrorDemand.reset();
            behindDemand.reset();
            MirrorView.resetPlaneDemand();
            return 0;
        }
        // A pack that doesn't read the cube (or the mirror view) never asks for its textures: it isn't drawn then
        boolean wantCube = cubeDemand.update();
        boolean mirrorRead = mirrorDemand.update();
        behindDemand.update();
        int mirrorPlanes = MirrorView.updatePlaneDemand();
        boolean wantMirror = mirrorRead && Config.MIRROR.get();
        MirrorView.status = !Config.MIRROR.get() ? "turned off in the config"
                : !mirrorRead ? "the shader pack doesn't read the mirror view" : MirrorView.status;
        if (!wantCube && !wantMirror) {
            status = "the shader pack doesn't read the cube";
            return 0;
        }
        FarViews.viewsRead(true);
        SodiumWorldRenderer worldRenderer = SodiumWorldRenderer.instanceNullable();
        RenderSectionManager sections = worldRenderer == null ? null : EmbeddiumAccess.sectionManager(worldRenderer);
        if (sections == null) {
            status = "Embeddium has no world renderer yet";
            return 0;
        }
        ChunkRenderer chunkRenderer = EmbeddiumAccess.chunkRenderer(sections);
        String formatProblem = ViewRenderer.checkVertexType(chunkRenderer.getVertexType());
        if (formatProblem != null) {
            status = formatProblem;
            return 0;
        }

        GlState saved = null;
        try {
            Vec3 cameraPos = camera.getPosition();
            int size = Config.FACE_SIZE.get();
            saved = GlState.capture();
            long start = System.nanoTime();
            ensureResources(size);
            GpuTimer.begin();

            float maxDistance = Config.MAX_DISTANCE.get() > 0 ? Config.MAX_DISTANCE.get() : mc.options.getEffectiveRenderDistance() * 16.0F;
            RenderRegionManager regions = EmbeddiumAccess.regions(sections);
            requestBuilds(sections, regions, cameraPos, maxDistance);
            int faces = wantCube ? Config.FACES_PER_FRAME.get() : 0;

            // The far terrain in the views reaches the far range (FarViews); the far plane is far enough for the range's
            // corners and for the full build height straight up or down
            int farRange = FarViews.range();
            float far = Math.max(maxDistance, farRange) * 1.75F + 512.0F;
            lastFar = far;
            lastMaxDistance = maxDistance;
            boolean mirror = wantMirror && MirrorView.update(mc, camera, view, new Matrix4f(RenderSystem.getProjectionMatrix()), NEAR, far, maxDistance,
                    mirrorPlanes);
            long entityStart = System.nanoTime();
            collectLocalPlayer(mc, camera, partialTick, mirror ? MirrorView.seen() : BoxTest.NONE);
            // The mobs and block entities collected after the last frame, when that was the frame before this one
            boolean current = entityHookFrame == hookFrame - 1 && entityLevel == mc.level;
            if (!current) {
                ViewRenderer.clearEntities();
                entitiesDrawn = 0;
                blockEntitiesDrawn = 0;
                entitiesReused = 0;
            }
            ViewRenderer.setEntityOffset((float) (entityCamera.x - cameraPos.x), (float) (entityCamera.y - cameraPos.y), (float) (entityCamera.z - cameraPos.z));
            playerNanos = System.nanoTime() - entityStart;

            cubeTarget.bind();
            ViewRenderer.beginFrame(NEAR, far);
            boolean farViews = FarViews.beginFrame(faces + (mirror ? MirrorView.activePlanes() : 0));
            long mirrorStart;
            try {
                for (int i = 0; i < faces; i++) {
                    int face = nextFace;
                    nextFace = (nextFace + 1) % 6;
                    drawFace(face, size, cameraPos, maxDistance, far, regions, farViews);
                    faceOrigin[face][0] = cameraPos.x;
                    faceOrigin[face][1] = cameraPos.y;
                    faceOrigin[face][2] = cameraPos.z;
                    faceFrame[face] = frameCounter;
                }
                mirrorStart = System.nanoTime();
                if (mirror) {
                    MirrorView.draw(cameraPos, regions, behindDemand.wanted(), farViews);
                }
            } finally {
                if (farViews) {
                    FarViews.endFrame();
                }
            }
            mirrorNanos = System.nanoTime() - mirrorStart;
            GpuTimer.end();
            cpuNanos = System.nanoTime() - start;
            frameCounter++;
            int drew = 0;
            // The far terrain's range in the views' info (0 when they hold none this frame)
            float farInfoRange = FarViews.drewThisFrame() ? (float) farRange : 0.0F;
            if (wantCube) {
                writeInfo(cameraPos, farInfoRange);
                linked = true;
                drew |= DREW_CUBE;
            }
            if (wantMirror) {
                MirrorView.writeInfo(frameCounter, NEAR, far, maxDistance, farInfoRange);
                drew |= DREW_MIRROR;
            }
            status = wantCube ? "drawing" : "drawing the mirror view only";
            return drew;
        } catch (Throwable t) {
            GpuTimer.end();
            throw t;
        } finally {
            if (saved != null) {
                saved.restore();
            }
        }
    }

    static float lastMaxDistance() {
        return lastMaxDistance;
    }

    /** The current face size in pixels (0 before the first frame). */
    public static int faceSizeForChecks() {
        return faceSize;
    }

    /** The latest GPU time of the mod's drawing in nanoseconds (-1 before the first), for the dev run's timings. */
    public static long gpuNanosForChecks() {
        return GpuTimer.nanos;
    }

    /** The CPU time of the entities of the latest frame in nanoseconds, for the dev run's timings. */
    public static long entityNanosForChecks() {
        return playerNanos + collectNanos;
    }

    /**
     * Lets go of everything that refers to the world being unloaded (its entities, block entities and the world
     * itself), so it isn't kept while the game is in its menus.
     */
    public static void forgetWorld() {
        entities.forget();
        playerEntities.forget();
        ViewRenderer.clearEntities();
        ViewRenderer.clearPlayer();
        entityLevel = null;
        MirrorPlaneSearch.checkLevel(null);
    }

    /** The entity captures' render types with vertex memory, and that memory, for the status line. */
    static String entityMemory() {
        return String.format("%d render types, %.1f MB", entities.sinkCount() + playerEntities.sinkCount(),
                (entities.sinkBytes() + playerEntities.sinkBytes()) / 1048576.0);
    }

    /** Frames drawn since the game started. */
    public static int framesDrawn() {
        return frameCounter;
    }

    /**
     * Marks the cube unusable after a frame without drawing (turned off, no world, an error): the info texture's face
     * size goes to 0, which packs read as "not linked", and every face counts as never drawn until it is redrawn.
     */
    private static void unlink() {
        if (!linked) {
            return;
        }
        linked = false;
        for (int i = 0; i < 6; i++) {
            faceFrame[i] = -1;
        }
        infoBuffer.clear();
        infoBuffer.put(CubeTextures.MAGIC).put(CubeTextures.PROTOCOL).put(0.0F).put(0.0F);
        for (int i = 1; i < CubeTextures.INFO_WIDTH; i++) {
            infoBuffer.put(0.0F).put(0.0F).put(0.0F).put(-1.0F);
        }
        infoBuffer.flip();
        CubeTextures.INFO.writeRow(infoBuffer, CubeTextures.INFO_WIDTH);
    }

    private static void ensureResources(int size) {
        ViewRenderer.ensureResources();
        if (infoBuffer == null) {
            infoBuffer = MemoryUtil.memAllocFloat(CubeTextures.INFO_WIDTH * 4);
        }
        if (cubeTarget.ensureSize(size * 3, size * 2)) {
            CubeTextures.INFO.allocate(CubeTextures.INFO_WIDTH, 1);
            faceSize = size;
            for (int i = 0; i < 6; i++) {
                faceFrame[i] = -1;
            }
            ShadersCompanion.LOGGER.info("Shaders Companion: cube faces of {} pixels ({} x {} atlas)", size, size * 3, size * 2);
        }
    }

    /** Collects the local player in first person for this frame's mirror view (boxes relative to the camera) and uploads it. */
    private static void collectLocalPlayer(Minecraft mc, Camera camera, float partialTick, BoxTest mirrorSeen) {
        ViewRenderer.clearPlayer();
        mirrorPlayerDrawn = false;
        playerEntities.begin(mc.level, false);
        if (mirrorSeen == BoxTest.NONE || !Config.ENTITIES.get()) {
            return;
        }
        playerEntities.collectLocalPlayer(mc, camera, partialTick, mirrorSeen);
        mirrorPlayerDrawn = playerEntities.mirrorPlayerDrawn;
        ViewRenderer.setPlayer(playerEntities);
    }

    /**
     * Collects the mobs and block entities the given views can see (boxes relative to the camera) and uploads them
     * once; the next frame's views then draw them.
     */
    private static void collectEntities(Minecraft mc, Camera camera, float partialTick, BoxTest seen, RenderRegionManager regions) {
        ViewRenderer.clearEntities();
        entitiesDrawn = 0;
        blockEntitiesDrawn = 0;
        entitiesReused = 0;
        entities.begin(mc.level, Config.ENTITY_LOD.get());
        if (seen == BoxTest.NONE) {
            return;
        }
        if (Config.ENTITIES.get()) {
            entities.collect(mc, camera, partialTick, seen, Config.ENTITY_DISTANCE.get(), Config.MAX_ENTITIES.get(), ENTITY_BUDGET_NANOS);
        }
        if (Config.BLOCK_ENTITIES.get()) {
            entities.collectBlockEntities(mc, camera, partialTick, seen, regions.getLoadedRegions(),
                    Config.BLOCK_ENTITY_DISTANCE.get(), Config.MAX_BLOCK_ENTITIES.get(), BLOCK_ENTITY_BUDGET_NANOS);
        }
        entitiesDrawn = entities.entitiesDrawn;
        blockEntitiesDrawn = entities.blockEntitiesDrawn;
        entitiesReused = entities.reused;
        ViewRenderer.setEntities(entities);
    }

    private static void drawFace(int face, int size, Vec3 camera, float maxDistance, float far, RenderRegionManager regions, boolean farViews) {
        int x = (face % 3) * size;
        int y = (face / 3) * size;
        GlStateManager._viewport(x, y, size, size);
        GlStateManager._scissorBox(x, y, size, size);
        cubeTarget.clear(0.0F);

        int[] f = FORWARD[face];
        int[] u = UP[face];
        Matrix4f viewProjection = new Matrix4f()
                .setPerspective((float) Math.toRadians(90.0), 1.0F, NEAR, far)
                .lookAt(0.0F, 0.0F, 0.0F, f[0], f[1], f[2], u[0], u[1], u[2]);
        double maxDistanceSq = (double) maxDistance * maxDistance;
        BoxTest seen = (minX, minY, minZ, maxX, maxY, maxZ) -> boxVisible(face, minX, minY, minZ, maxX, maxY, maxZ, maxDistanceSq);
        // The face's origin is the camera: its own matrix culls the far terrain, with no cut plane
        ViewRenderer.drawView(viewProjection, seen, farViews ? new ViewRenderer.Far(null, null, false) : null, camera, camera, ViewRenderer.NO_CLIP,
                ViewRenderer.NO_OFFSET, regions, false, null);
    }

    /**
     * Embeddium builds only the sections its main view or Oculus's shadow pass has seen, so terrain behind the player
     * may have no mesh. Queues the unbuilt sections within the cube's range for building, the way Embeddium's own
     * visibility pass does (same queues, same size limits); this frame's chunk update then submits them.
     */
    private static void requestBuilds(RenderSectionManager sections, RenderRegionManager regions, Vec3 camera, float maxDistance)
            throws IllegalAccessException {
        Map<ChunkUpdateType, ArrayDeque<RenderSection>> queues = EmbeddiumAccess.rebuildLists(sections);
        if (queues == null) {
            return;
        }
        double maxDistanceSq = (double) maxDistance * maxDistance;
        int queued = 0;
        for (RenderRegion region : regions.getLoadedRegions()) {
            double rx = region.getOriginX() - camera.x;
            double ry = region.getOriginY() - camera.y;
            double rz = region.getOriginZ() - camera.z;
            if (nearestDistanceSq(rx, ry, rz, rx + 128.0, ry + 64.0, rz + 128.0) > maxDistanceSq) {
                continue;
            }
            for (int index = 0; index < RenderRegion.REGION_SIZE; index++) {
                RenderSection section = region.getSection(index);
                if (section == null || section.isDisposed() || section.getBuildCancellationToken() != null) {
                    continue;
                }
                ChunkUpdateType type = section.getPendingUpdate();
                if (type != ChunkUpdateType.INITIAL_BUILD && type != ChunkUpdateType.REBUILD) {
                    continue;
                }
                double sx = (section.getChunkX() << 4) - camera.x;
                double sy = (section.getChunkY() << 4) - camera.y;
                double sz = (section.getChunkZ() << 4) - camera.z;
                if (nearestDistanceSq(sx, sy, sz, sx + 16.0, sy + 16.0, sz + 16.0) > maxDistanceSq) {
                    continue;
                }
                ArrayDeque<RenderSection> queue = queues.get(type);
                if (queue != null && queue.size() < type.getMaximumQueueSize() && !queue.contains(section)) {
                    queue.add(section);
                    queued++;
                }
            }
        }
        sectionsQueued = queued;
    }

    /** Squared horizontal distance from the origin to a box: the range is a column, like Embeddium's render distance. */
    static double nearestDistanceSq(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.max(minX, -maxX), 0.0);
        double dz = Math.max(Math.max(minZ, -maxZ), 0.0);
        return dx * dx + dz * dz;
    }

    /** Whether a box (relative to the capture origin) can be seen by a cube face: inside its four side planes and within range. */
    static boolean boxVisible(int face, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double maxDistanceSq) {
        return nearestDistanceSq(minX, minY, minZ, maxX, maxY, maxZ) <= maxDistanceSq && boxInFace(face, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Whether a box (relative to the capture origin) is inside a cube face's four side planes. */
    static boolean boxInFace(int face, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int[] f = FORWARD[face];
        int[] r = RIGHT[face];
        int[] u = UP[face];
        return inside(f[0] - r[0], f[1] - r[1], f[2] - r[2], minX, minY, minZ, maxX, maxY, maxZ)
                && inside(f[0] + r[0], f[1] + r[1], f[2] + r[2], minX, minY, minZ, maxX, maxY, maxZ)
                && inside(f[0] - u[0], f[1] - u[1], f[2] - u[2], minX, minY, minZ, maxX, maxY, maxZ)
                && inside(f[0] + u[0], f[1] + u[1], f[2] + u[2], minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean inside(int nx, int ny, int nz, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double px = nx >= 0 ? maxX : minX;
        double py = ny >= 0 ? maxY : minY;
        double pz = nz >= 0 ? maxZ : minZ;
        return nx * px + ny * py + nz * pz >= 0.0;
    }

    /**
     * Writes the info texture: header, each face's capture origin relative to this frame's camera, and the projection
     * with the ranges (the far terrain's 0 when the faces drawn this frame hold none).
     */
    private static void writeInfo(Vec3 camera, float farInfoRange) {
        infoBuffer.clear();
        infoBuffer.put(CubeTextures.MAGIC).put(CubeTextures.PROTOCOL).put(faceSize).put(frameCounter % 4096);
        for (int face = 0; face < 6; face++) {
            if (faceFrame[face] < 0) {
                infoBuffer.put(0.0F).put(0.0F).put(0.0F).put(-1.0F);
            } else {
                infoBuffer.put((float) (faceOrigin[face][0] - camera.x))
                        .put((float) (faceOrigin[face][1] - camera.y))
                        .put((float) (faceOrigin[face][2] - camera.z))
                        .put(frameCounter - 1 - faceFrame[face]);
            }
        }
        infoBuffer.put(NEAR).put(lastFar).put(lastMaxDistance).put(farInfoRange);
        infoBuffer.flip();
        CubeTextures.INFO.writeRow(infoBuffer, CubeTextures.INFO_WIDTH);
    }
}
