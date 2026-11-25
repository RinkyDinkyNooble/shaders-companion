package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.nio.FloatBuffer;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

/**
 * The mirror view: the world seen from the camera mirrored in the flat reflecting surfaces on screen (water levels and
 * glass walls), at screen resolution, so a shader pack can show sharp reflections on them, the player's own included.
 * {@link MirrorPlaneSearch} picks the planes, up to as many as the pack asks for (it binds the info texture as
 * mirror_info_N for N planes, plain mirror_info for one); each has its own view ({@link MirrorPlaneView}) in a tile of
 * the shared textures, side by side, with its own row of the info texture (see API.md).
 */
final class MirrorView {
    /** Most planes a pack can ask for. */
    static final int MAX_PLANES = 4;
    /** The views' field of view is this much wider than the screen's (as a tangent), so slightly bent reflection rays at the screen's edge stay in it. */
    static final float MARGIN = 1.1F;

    static String status = "no reflecting surface in view";
    static long cpuNanos;

    /** The views' textures, and the copy of their opaque surfaces taken before water and glass are drawn over them. */
    private static final ViewTarget target = new ViewTarget("mirror", CubeTextures.MIRROR_ALBEDO, CubeTextures.MIRROR_DATA, CubeTextures.MIRROR_DISTANCE);
    private static final ViewTarget behindTarget = new ViewTarget("mirror behind", false, CubeTextures.MIRROR_BEHIND_ALBEDO,
            CubeTextures.MIRROR_BEHIND_DATA, CubeTextures.MIRROR_BEHIND_DISTANCE);
    private static final MirrorPlaneView[] views = new MirrorPlaneView[MAX_PLANES];
    /** Whether the pack reads mirror_info_N, for N from 2 (index N). */
    private static final TextureDemand[] planeDemands = new TextureDemand[MAX_PLANES + 1];
    private static boolean active;
    private static boolean linked;
    private static FloatBuffer infoBuffer;

    // This frame's slots (the planes the pack asks for) and each tile's size (the screen's at mirrorScale)
    private static int slots = 1;
    private static int tileWidth;
    private static int tileHeight;
    private static int maxTextureSize;

    static {
        for (int slot = 0; slot < MAX_PLANES; slot++) {
            views[slot] = new MirrorPlaneView(slot);
        }
        for (int planes = 2; planes <= MAX_PLANES; planes++) {
            planeDemands[planes] = new TextureDemand(CompanionTexture.mirrorPlanesGroup(planes));
        }
    }

    private MirrorView() {
    }

    /** Counts this frame's requests: how many planes the pack asks for (the most of the names it binds; 1 by default). */
    static int updatePlaneDemand() {
        int planes = 1;
        for (int n = 2; n <= MAX_PLANES; n++) {
            if (planeDemands[n].update()) {
                planes = n;
            }
        }
        return planes;
    }

    /** No shader pack in use: the next request counts as new. */
    static void resetPlaneDemand() {
        for (int n = 2; n <= MAX_PLANES; n++) {
            planeDemands[n].reset();
        }
    }

    /**
     * Finds this frame's mirror planes and sets up a view for each. Returns false when there is no reflecting surface
     * worth mirroring in view (or the camera is under water).
     *
     * @param planes     how many planes the pack asks for
     * @param view       the main view's rotation (the pose the world is drawn with)
     * @param projection the main view's projection
     */
    static boolean update(Minecraft mc, Camera camera, Matrix4f view, Matrix4f projection, float near, float far, float maxDistance, int planes) {
        long start = System.nanoTime();
        try {
            active = false;
            slots = planes;
            for (MirrorPlaneView planeView : views) {
                planeView.deactivate();
            }
            MirrorPlaneSearch.checkLevel(mc.level);
            if (camera.getFluidInCamera() != FogType.NONE) {
                status = "camera under water, in lava or in powder snow";
                MirrorPlaneSearch.forget();
                return false;
            }
            Vec3 cameraPos = camera.getPosition();
            MirrorPlaneSearch.find(mc, camera, cameraPos, view, projection, slots);
            var screen = mc.getMainRenderTarget();
            if (maxTextureSize == 0) {
                maxTextureSize = GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE);
            }
            // The tiles side by side stay within the largest texture the driver allows (four at 5K would not)
            tileWidth = Math.max(16, Math.min(screen.width * Config.MIRROR_SCALE.get() / 100, maxTextureSize / slots));
            tileHeight = Math.max(16, screen.height * Config.MIRROR_SCALE.get() / 100);
            boolean shadows = Config.MIRROR_SHADOWS.get();
            if (shadows) {
                MirrorShadows.begin();
            }
            for (int slot = 0; slot < slots; slot++) {
                long plane = MirrorPlaneSearch.plane(slot);
                if (plane != MirrorPlaneSearch.NO_PLANE) {
                    views[slot].setUp(plane, cameraPos, view, projection, near, far, maxDistance, tileWidth, tileHeight);
                    active = true;
                    if (shadows) {
                        views[slot].addShadowVolume();
                    }
                }
            }
            status = active ? describe() : "no reflecting surface in view";
            return active;
        } finally {
            if (!active || !Config.MIRROR_SHADOWS.get()) {
                MirrorShadows.clear();
            }
            cpuNanos = System.nanoTime() - start;
        }
    }

    /**
     * Draws this frame's views, each into its tile; the rest of every tile says "not drawn". With {@code behind} (the
     * pack reads them), the opaque surfaces are copied before water and glass are drawn over them: what lies behind
     * those, for shading them as water and glass.
     */
    static void draw(Vec3 camera, RenderRegionManager regions, boolean behind, boolean farViews) {
        int width = tileWidth * slots;
        if (target.ensureSize(width, tileHeight)) {
            ShadersCompanion.LOGGER.info("Shaders Companion: mirror view of {} x {} pixels ({} tiles of {} x {})", width, tileHeight, slots, tileWidth, tileHeight);
        }
        if (behind) {
            behindTarget.ensureSize(width, tileHeight);
        }
        target.bind();
        GlStateManager._viewport(0, 0, width, tileHeight);
        GlStateManager._scissorBox(0, 0, width, tileHeight);
        // Outside the parts of the screen the mirrors cover, the distance says "not drawn" (-1)
        target.clear(-1.0F);
        for (int slot = 0; slot < slots; slot++) {
            if (views[slot].active()) {
                views[slot].draw(target, behind ? behindTarget : null, slot * tileWidth, camera, regions, farViews);
            }
        }
    }

    private static String describe() {
        StringBuilder planes = new StringBuilder();
        int count = 0;
        for (int slot = 0; slot < slots; slot++) {
            if (views[slot].active()) {
                planes.append(count++ == 0 ? "" : "; ").append(views[slot].describe());
            }
        }
        return String.format("mirroring %s%s (%d of %d planes), %d switches, %d shadow casters kept for them", planes, MirrorPlaneSearch.describeRival(),
                count, slots, MirrorPlaneSearch.switches, MirrorShadows.keptLastFrame);
    }

    static boolean active() {
        return active;
    }

    /** The planes drawn this frame. */
    static int activePlanes() {
        int count = 0;
        for (int slot = 0; slot < slots; slot++) {
            count += views[slot].active() ? 1 : 0;
        }
        return count;
    }

    /** Boxes relative to the camera that the drawn part of any view can see (see {@link MirrorPlaneView#seen()}). */
    static BoxTest seen() {
        BoxTest seen = BoxTest.NONE;
        for (int slot = 0; slot < slots; slot++) {
            if (views[slot].active()) {
                seen = seen.or(views[slot].seen());
            }
        }
        return seen;
    }

    /**
     * As {@link #seen()} for the whole views, not only their drawn parts: for the entities collected for the next frame,
     * whose drawn parts may be larger (they grow at once while the view turns).
     */
    static BoxTest seenWhole() {
        BoxTest seen = BoxTest.NONE;
        for (int slot = 0; slot < slots; slot++) {
            if (views[slot].active()) {
                seen = seen.or(views[slot].seenWhole());
            }
        }
        return seen;
    }

    /**
     * Writes the info texture, one row per slot: a view's row (see {@link MirrorPlaneView#writeRow}), or a header of
     * size 0 for an empty slot, all relative to this frame's camera.
     */
    static void writeInfo(int frameCounter, float near, float far, float terrainRange, float farRange) {
        if (infoBuffer == null) {
            infoBuffer = MemoryUtil.memAllocFloat(CubeTextures.MIRROR_INFO_WIDTH * 4 * MAX_PLANES);
        }
        infoBuffer.clear();
        for (int slot = 0; slot < MAX_PLANES; slot++) {
            if (active && slot < slots && views[slot].active()) {
                views[slot].writeRow(infoBuffer, frameCounter, near, far, terrainRange, farRange, slot * tileWidth);
            } else {
                putEmptyRow();
            }
        }
        infoBuffer.flip();
        CubeTextures.MIRROR_INFO.writeRows(infoBuffer, CubeTextures.MIRROR_INFO_WIDTH, MAX_PLANES);
        linked = true;
    }

    private static void putEmptyRow() {
        infoBuffer.put(CubeTextures.MAGIC).put(CubeTextures.MIRROR_PROTOCOL).put(0.0F).put(0.0F);
        for (int i = 4; i < CubeTextures.MIRROR_INFO_WIDTH * 4; i++) {
            infoBuffer.put(0.0F);
        }
    }

    /** Marks the mirror view unusable (size 0 in every row) after a frame without drawing. */
    static void unlink() {
        active = false;
        for (MirrorPlaneView planeView : views) {
            planeView.deactivate();
        }
        MirrorShadows.clear();
        if (!linked) {
            return;
        }
        linked = false;
        if (infoBuffer == null) {
            return;
        }
        infoBuffer.clear();
        for (int slot = 0; slot < MAX_PLANES; slot++) {
            putEmptyRow();
        }
        infoBuffer.flip();
        CubeTextures.MIRROR_INFO.writeRows(infoBuffer, CubeTextures.MIRROR_INFO_WIDTH, MAX_PLANES);
    }

    /** Whether the info texture says a mirror view may be there (so {@link #unlink} would write to it). */
    static boolean linked() {
        return linked;
    }
}
