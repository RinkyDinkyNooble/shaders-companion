package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.nio.FloatBuffer;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/**
 * The mirror view: the world seen from the camera mirrored in one flat reflecting surface on screen (a water level or a
 * glass wall), at screen resolution, so a shader pack can show sharp reflections on it, the player's own included.
 * {@link MirrorPlaneSearch} picks the surface's plane.
 * <p>
 * The view is drawn like the main view, with the world mirrored in the plane and everything on the far side of it cut
 * away, so a point on the plane lands on the same texel as on screen. Its matrix is written to the info texture, so a
 * pack doesn't depend on that (see API.md). Only the part of the screen the plane covers (with a margin) is drawn
 * ({@link MirrorArea}), and a mirror that covers little of the screen is drawn at a lower resolution (mirrorLod).
 */
final class MirrorView {
    /** Geometry closer than this to the plane, on its far side or on it (the mirror itself), is cut from the view. */
    private static final float CLIP_EPSILON = 0.01F;
    /** The view's field of view is this much wider than the screen's (as a tangent), so slightly bent reflection rays at the screen's edge stay in it. */
    private static final float MARGIN = 1.1F;
    /** mirrorLod: the plane's share of the weighted screen below which the view drops to 75% and 50% resolution. */
    private static final float LOD_75_BELOW = 0.35F;
    private static final float LOD_50_BELOW = 0.15F;
    /** A lower level waits this long before it is used, a higher one LOD_UP_NANOS. */
    private static final long LOD_DOWN_NANOS = 1_000_000_000L;
    private static final long LOD_UP_NANOS = 200_000_000L;
    /** A newly mirrored plane's reflections fade in from the pack's own over this long (info texel 8). */
    private static final float FADE_IN_SECONDS = 0.15F;

    static String status = "no reflecting surface in view";
    static long cpuNanos;

    /** The view's textures, and the copy of its opaque surfaces taken before water and glass are drawn over them. */
    private static final ViewTarget target = new ViewTarget("mirror", CubeTextures.MIRROR_ALBEDO, CubeTextures.MIRROR_DATA, CubeTextures.MIRROR_DISTANCE);
    private static final ViewTarget behindTarget = new ViewTarget("mirror behind", false, CubeTextures.MIRROR_BEHIND_ALBEDO,
            CubeTextures.MIRROR_BEHIND_DATA, CubeTextures.MIRROR_BEHIND_DISTANCE);
    private static boolean active;
    private static boolean linked;
    private static final MirrorArea area = new MirrorArea(MARGIN);
    /** mirrorLod: the resolution in use (percent of mirrorScale), a different one waiting, and since when. */
    private static int lodPercent = 100;
    private static int lodWaiting = 100;
    private static long lodWaitingSince;

    // This frame's view, valid while active
    private static int width;
    private static int height;
    private static int axis;
    private static int side;
    private static double coordinate;
    private static double heightAbove;
    private static final Vector3f normal = new Vector3f();
    private static final Vector3f origin = new Vector3f();
    private static Vec3 originWorld = Vec3.ZERO;
    private static final Matrix4f drawMatrix = new Matrix4f();
    private static final Matrix4f packMatrix = new Matrix4f();
    private static final FrustumIntersection frustum = new FrustumIntersection();
    private static final FrustumIntersection wholeFrustum = new FrustumIntersection();
    private static float maxDistanceSq;
    private static FloatBuffer infoBuffer;

    private MirrorView() {
    }

    /**
     * Finds this frame's mirror plane and sets up the view for it. Returns false when there is no reflecting surface
     * worth mirroring in view (or the camera is under water).
     *
     * @param view       the main view's rotation (the pose the world is drawn with)
     * @param projection the main view's projection
     */
    static boolean update(Minecraft mc, Camera camera, Matrix4f view, Matrix4f projection, float near, float far, float maxDistance) {
        long start = System.nanoTime();
        try {
            active = false;
            MirrorPlaneSearch.checkLevel(mc.level);
            if (camera.getFluidInCamera() != FogType.NONE) {
                status = "camera under water, in lava or in powder snow";
                MirrorPlaneSearch.forget();
                return false;
            }
            Vec3 cameraPos = camera.getPosition();
            long plane = MirrorPlaneSearch.find(mc, camera, cameraPos, view, projection);
            if (plane == MirrorPlaneSearch.NO_PLANE) {
                status = "no reflecting surface in view";
                return false;
            }
            setUp(mc, plane, cameraPos, view, projection, near, far, maxDistance);
            active = true;
            status = describePlane();
            return true;
        } finally {
            if (!active) {
                MirrorShadows.clear();
            }
            cpuNanos = System.nanoTime() - start;
        }
    }

    private static void setUp(Minecraft mc, long plane, Vec3 cameraPos, Matrix4f view, Matrix4f projection, float near, float far, float maxDistance) {
        axis = MirrorPlaneSearch.axis(plane);
        side = MirrorPlaneSearch.side(plane);
        coordinate = MirrorPlaneSearch.coordinate(plane);
        heightAbove = MirrorPlaneSearch.heightOf(plane, cameraPos);
        normal.set(axis == 0 ? side : 0, axis == 1 ? side : 0, axis == 2 ? side : 0);
        // The camera mirrored in the plane, relative to the camera
        origin.set(normal).mul((float) (-2.0 * heightAbove));
        originWorld = cameraPos.add(origin.x, origin.y, origin.z);

        float tanY = MARGIN / projection.m11();
        float aspect = projection.m11() / projection.m00();
        Matrix4f mirror = new Matrix4f().scaling(axis == 0 ? -1.0F : 1.0F, axis == 1 ? -1.0F : 1.0F, axis == 2 ? -1.0F : 1.0F);
        // Positions relative to the mirrored camera: mirror them, then look at them as the main view does
        drawMatrix.setPerspective(2.0F * (float) Math.atan(tanY), aspect, near, far).mul(view).mul(mirror);
        packMatrix.set(drawMatrix).translate(-origin.x, -origin.y, -origin.z);
        area.update(plane, MirrorPlaneSearch.lastStep());
        // Culled to the drawn part, shadow casters too (Oculus's shadow pass runs after this, in the same frame)
        Matrix4f cropped = area.crop().mul(packMatrix);
        frustum.set(cropped);
        wholeFrustum.set(packMatrix);
        maxDistanceSq = maxDistance * maxDistance;
        if (Config.MIRROR_SHADOWS.get()) {
            MirrorShadows.set(cropped, normal, (float) heightAbove);
        } else {
            MirrorShadows.clear();
        }

        var target = mc.getMainRenderTarget();
        int scale = Config.MIRROR_SCALE.get() * chooseLod() / 100;
        width = Math.max(16, target.width * scale / 100);
        height = Math.max(16, target.height * scale / 100);
        area.setPixels(width, height);
    }

    /** mirrorLod: the resolution for the plane's share of the screen (percent), changed only once it has held. */
    private static int chooseLod() {
        float share = MirrorPlaneSearch.share();
        int wanted = !Config.MIRROR_LOD.get() || share >= LOD_75_BELOW ? 100 : share >= LOD_50_BELOW ? 75 : 50;
        long now = System.nanoTime();
        if (wanted == lodPercent) {
            lodWaiting = wanted;
        } else if (wanted != lodWaiting) {
            lodWaiting = wanted;
            lodWaitingSince = now;
        } else if (now - lodWaitingSince >= (wanted > lodPercent ? LOD_UP_NANOS : LOD_DOWN_NANOS)) {
            lodPercent = wanted;
        }
        return lodPercent;
    }

    /**
     * Draws this frame's view: the same surfaces as a cube face, from the mirrored camera, with everything on the far
     * side of the plane cut away, in the part of the view the plane covers; the rest says "not drawn". With
     * {@code behind} (the pack reads them), the opaque surfaces are copied before water and glass are drawn over them:
     * what lies behind those, for shading them as water and glass.
     */
    static void draw(Vec3 camera, float maxDistance, float lodDistance, RenderRegionManager regions, boolean behind) {
        if (target.ensureSize(width, height)) {
            ShadersCompanion.LOGGER.info("Shaders Companion: mirror view of {} x {} pixels", width, height);
        }
        if (behind) {
            behindTarget.ensureSize(width, height);
        }
        target.bind();
        GlStateManager._viewport(0, 0, width, height);
        GlStateManager._scissorBox(0, 0, width, height);
        // Outside the part of the screen the mirror covers, the distance says "not drawn" (-1); inside it, 0 is open sky
        target.clear(-1.0F);
        int[] drawn = area.pixels();
        GlStateManager._scissorBox(drawn[0], drawn[1], drawn[2], drawn[3]);
        GL30C.glClearBufferfv(GL11C.GL_COLOR, ViewProgram.DISTANCE_OUTPUT, new float[]{0.0F, 0.0F, 0.0F, 0.0F});

        float[] cameraOffset = {(float) (camera.x - originWorld.x), (float) (camera.y - originWorld.y), (float) (camera.z - originWorld.z)};
        Runnable copyBehind = behind ? () -> target.copyTo(behindTarget, drawn[0], drawn[1], drawn[2], drawn[3]) : null;
        ViewRenderer.drawView(drawMatrix, seen(), frustumSeen(), camera, originWorld, clipPlane(), cameraOffset, maxDistance, lodDistance, regions, true,
                copyBehind);
    }

    private static String describePlane() {
        String name = axis == 0 ? "x" : axis == 1 ? "y" : "z";
        return String.format("mirroring %s = %.3f (%s side, share %.2f%s), %d x %d px (%d%% of it drawn), %d switches, %d shadow casters kept for it", name, coordinate,
                side > 0 ? "+" : "-", MirrorPlaneSearch.share(), MirrorPlaneSearch.describeRival(), width, height, area.drawnPercent(width, height),
                MirrorPlaneSearch.switches, MirrorShadows.keptLastFrame);
    }

    static boolean active() {
        return active;
    }

    /**
     * Boxes relative to the camera that the drawn part of the view can see: in its frustum, in front of the plane and
     * within the terrain range.
     */
    static BoxTest seen() {
        return (minX, minY, minZ, maxX, maxY, maxZ) -> CubeView.nearestDistanceSq(minX, minY, minZ, maxX, maxY, maxZ) <= maxDistanceSq
                && inView(frustum, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * As {@link #seen()} for the whole view, not only its drawn part: for the entities collected for the next frame,
     * whose drawn part may be larger (it grows at once while the view turns).
     */
    static BoxTest seenWhole() {
        return (minX, minY, minZ, maxX, maxY, maxZ) -> CubeView.nearestDistanceSq(minX, minY, minZ, maxX, maxY, maxZ) <= maxDistanceSq
                && inView(wholeFrustum, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** As {@link #seen()} without the terrain range, for Distant Horizons' far terrain. */
    static BoxTest frustumSeen() {
        return (minX, minY, minZ, maxX, maxY, maxZ) -> inView(frustum, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean inView(FrustumIntersection test, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double front = heightAbove + (side > 0 ? (axis == 0 ? maxX : axis == 1 ? maxY : maxZ) : -(axis == 0 ? minX : axis == 1 ? minY : minZ));
        return front > CLIP_EPSILON && test.testAab((float) minX, (float) minY, (float) minZ, (float) maxX, (float) maxY, (float) maxZ);
    }

    /** The cut plane for positions relative to the mirrored camera: drawn where dot(xyz, p) + w >= 0. */
    private static float[] clipPlane() {
        return new float[]{normal.x, normal.y, normal.z, (float) -heightAbove - CLIP_EPSILON};
    }

    /**
     * Writes the info texture: header (size 0 when there is no mirror this frame), plane, origin, matrix and ranges, all
     * relative to this frame's camera.
     */
    static void writeInfo(int frameCounter, float near, float far, float terrainRange, float lodRange) {
        if (infoBuffer == null) {
            infoBuffer = MemoryUtil.memAllocFloat(CubeTextures.MIRROR_INFO_WIDTH * 4);
        }
        infoBuffer.clear();
        infoBuffer.put(CubeTextures.MAGIC).put(CubeTextures.MIRROR_PROTOCOL).put(active ? width : 0).put(active ? height : 0);
        if (active) {
            infoBuffer.put(normal.x).put(normal.y).put(normal.z).put((float) heightAbove);
            infoBuffer.put(origin.x).put(origin.y).put(origin.z).put(frameCounter % 4096);
            packMatrix.get(infoBuffer.position(), infoBuffer);
            infoBuffer.position(infoBuffer.position() + 16);
            infoBuffer.put(near).put(far).put(terrainRange).put(lodRange);
            infoBuffer.put(Math.min(1.0F, MirrorPlaneSearch.planeAge() / FADE_IN_SECONDS)).put(0.0F).put(0.0F).put(0.0F);
        } else {
            while (infoBuffer.hasRemaining()) {
                infoBuffer.put(0.0F);
            }
        }
        infoBuffer.flip();
        CubeTextures.MIRROR_INFO.writeRow(infoBuffer, CubeTextures.MIRROR_INFO_WIDTH);
        linked = true;
    }

    /** Marks the mirror view unusable (size 0) after a frame without drawing. */
    static void unlink() {
        active = false;
        MirrorShadows.clear();
        if (!linked) {
            return;
        }
        linked = false;
        if (infoBuffer == null) {
            return;
        }
        infoBuffer.clear();
        infoBuffer.put(CubeTextures.MAGIC).put(CubeTextures.MIRROR_PROTOCOL).put(0.0F).put(0.0F);
        while (infoBuffer.hasRemaining()) {
            infoBuffer.put(0.0F);
        }
        infoBuffer.flip();
        CubeTextures.MIRROR_INFO.writeRow(infoBuffer, CubeTextures.MIRROR_INFO_WIDTH);
    }

    /** Whether the info texture says a mirror view may be there (so {@link #unlink} would write to it). */
    static boolean linked() {
        return linked;
    }
}
