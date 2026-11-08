package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

/**
 * The mirror view: the world seen from the camera mirrored in one flat reflecting surface on screen (a water level or a
 * glass wall), at screen resolution, so a shader pack can show sharp reflections on it, the player's own included.
 * <p>
 * Each frame a few rays from the camera through a grid over the screen find the reflecting surfaces in view (water and
 * blocks drawn in the translucent layer: stained and tinted glass, ice). Every Minecraft surface is axis-aligned, so a
 * surface's plane is an axis, a side and a coordinate. Each grid cell remembers the plane its last ray hit, and the
 * plane with the most screen area, weighted towards the centre of the screen, is mirrored. Another plane takes over
 * only after it has clearly covered more for half a second, or at once when the current one leaves the view.
 * <p>
 * The view is drawn like the main view, with the world mirrored in the plane and everything on the far side of it cut
 * away, so a point on the plane lands on the same texel as on screen. Its matrix is written to the info texture, so a
 * pack doesn't depend on that (see API.md).
 */
final class MirrorView {
    private static final int GRID_X = 16;
    private static final int GRID_Y = 9;
    private static final int CELLS = GRID_X * GRID_Y;
    /** Grid cells visited in a scattered order: a stride with no common factor with the cell count. */
    private static final int CELL_STRIDE = 37;
    private static final int RAYS_PER_FRAME = 16;
    private static final double RAY_DISTANCE = 64.0;
    /**
     * A cell's weight falls from 1 at the screen's centre like a bell curve of this width (in half screens), down to
     * EDGE_WEIGHT, so the surface you look at wins over a larger one off to the side.
     */
    private static final float CENTRE_SPREAD = 0.35F;
    private static final float EDGE_WEIGHT = 0.1F;
    /** Each frame's shares move this far towards the grid's, smoothing out the noise of the rays' spots in their cells. */
    private static final float SMOOTHING = 0.2F;
    /** Share of the weighted screen a plane must cover to be mirrored; the current one stays down to half of it. */
    private static final float MIN_SCORE = 0.01F;
    /** Another plane takes over when it covers this much more than the current one... */
    private static final float SWITCH_RATIO = 1.2F;
    /** ...for this long. */
    private static final long SWITCH_DELAY_NANOS = 500_000_000L;
    /** The camera must be at least this far in front of the plane. */
    private static final double MIN_HEIGHT = 0.05;
    /** Geometry closer than this to the plane, on its far side or on it (the mirror itself), is cut from the view. */
    private static final float CLIP_EPSILON = 0.01F;
    /** The view's field of view is this much wider than the screen's (as a tangent), so slightly bent reflection rays at the screen's edge stay in it. */
    private static final float MARGIN = 1.1F;
    private static final long NO_PLANE = Long.MIN_VALUE;
    private static final RandomSource RANDOM = RandomSource.create(42L);

    static String status = "no reflecting surface in view";
    static long cpuNanos;
    static int width;
    static int height;
    /** Times the mirror moved straight from one plane to another (for the debug overlay). */
    static int switches;

    /** The plane each grid cell's last ray hit. */
    private static final long[] cellPlanes = new long[CELLS];
    private static final float[] CELL_WEIGHTS = new float[CELLS];
    /** Each plane's smoothed share of the weighted grid. */
    private static final Map<Long, Float> scores = new HashMap<>();
    private static final Map<BlockState, Boolean> translucent = new IdentityHashMap<>();
    private static ClientLevel scoredLevel;
    private static int rayCursor;
    private static long plane = NO_PLANE;
    /** A plane that covers clearly more than the current one, and since when. */
    private static long challenger = NO_PLANE;
    private static long challengerSince;
    // For the status line: the mirrored plane's weighted share of the screen, and the next best plane's
    private static float shownShare;
    private static long rival = NO_PLANE;
    private static float rivalShare;
    private static boolean active;
    private static boolean linked;

    // This frame's view, valid while active
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
    private static float maxDistanceSq;
    private static FloatBuffer infoBuffer;

    static {
        Arrays.fill(cellPlanes, NO_PLANE);
        float total = 0.0F;
        for (int cell = 0; cell < CELLS; cell++) {
            float x = ((cell % GRID_X) + 0.5F) / GRID_X * 2.0F - 1.0F;
            float y = ((cell / GRID_X) + 0.5F) / GRID_Y * 2.0F - 1.0F;
            CELL_WEIGHTS[cell] = EDGE_WEIGHT + (1.0F - EDGE_WEIGHT) * (float) Math.exp(-(x * x + y * y) / (CENTRE_SPREAD * CENTRE_SPREAD));
            total += CELL_WEIGHTS[cell];
        }
        for (int cell = 0; cell < CELLS; cell++) {
            CELL_WEIGHTS[cell] /= total;
        }
    }

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
            if (mc.level != scoredLevel) {
                scoredLevel = mc.level;
                translucent.clear();
                forget();
            }
            if (camera.getFluidInCamera() != FogType.NONE) {
                status = "camera under water";
                forget();
                return false;
            }
            Vec3 cameraPos = camera.getPosition();
            castRays(mc, camera, cameraPos, view, projection);
            choosePlane(cameraPos);
            if (plane == NO_PLANE) {
                status = "no reflecting surface in view";
                return false;
            }
            setUp(mc, cameraPos, view, projection, near, far, maxDistance);
            active = true;
            status = describePlane();
            return true;
        } finally {
            cpuNanos = System.nanoTime() - start;
        }
    }

    private static void forget() {
        Arrays.fill(cellPlanes, NO_PLANE);
        scores.clear();
        plane = NO_PLANE;
        challenger = NO_PLANE;
    }

    private static void castRays(Minecraft mc, Camera camera, Vec3 cameraPos, Matrix4f view, Matrix4f projection) {
        Entity entity = camera.getEntity() != null ? camera.getEntity() : mc.player;
        if (entity == null) {
            return;
        }
        Matrix4f toWorld = new Matrix4f(view).invert();
        float tanX = 1.0F / projection.m00();
        float tanY = 1.0F / projection.m11();
        Vector4f direction = new Vector4f();
        for (int r = 0; r < RAYS_PER_FRAME; r++) {
            int sweep = rayCursor / CELLS;
            int cell = (rayCursor % CELLS) * CELL_STRIDE % CELLS;
            rayCursor = (rayCursor + 1) % (CELLS * 1024);
            // A different spot inside the cell on each sweep
            int hash = (cell * 73856093) ^ (sweep * 19349663);
            float jx = ((hash >>> 4) & 255) / 256.0F;
            float jy = ((hash >>> 12) & 255) / 256.0F;
            float x = ((cell % GRID_X) + jx) / GRID_X * 2.0F - 1.0F;
            float y = ((cell / GRID_X) + jy) / GRID_Y * 2.0F - 1.0F;
            direction.set(x * tanX, y * tanY, -1.0F, 0.0F).mul(toWorld);
            Vec3 end = cameraPos.add(new Vec3(direction.x, direction.y, direction.z).normalize().scale(RAY_DISTANCE));
            BlockHitResult hit = mc.level.clip(new ClipContext(cameraPos, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.WATER, entity));
            cellPlanes[cell] = hit.getType() == HitResult.Type.MISS ? NO_PLANE : reflectingPlane(mc, hit);
        }
    }

    /** The plane of a ray's hit when it is on a reflecting surface: a water level or a translucent block's face. */
    private static long reflectingPlane(Minecraft mc, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        Vec3 location = hit.getLocation();
        Direction face = hit.getDirection();
        FluidState fluid = mc.level.getFluidState(pos);
        if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
            double surface = pos.getY() + fluid.getHeight(mc.level, pos);
            if (face == Direction.UP && Math.abs(location.y - surface) < 1.0E-3) {
                return key(Direction.Axis.Y, 1, surface);
            }
        }
        BlockState state = mc.level.getBlockState(pos);
        if (!isTranslucent(mc, state)) {
            return NO_PLANE;
        }
        Direction.Axis faceAxis = face.getAxis();
        return key(faceAxis, face.getAxisDirection().getStep(), location.get(faceAxis));
    }

    private static boolean isTranslucent(Minecraft mc, BlockState state) {
        Boolean known = translucent.get(state);
        if (known == null) {
            boolean value;
            try {
                value = mc.getBlockRenderer().getBlockModel(state).getRenderTypes(state, RANDOM, ModelData.EMPTY).contains(RenderType.translucent());
            } catch (RuntimeException e) {
                value = false;
            }
            known = value;
            translucent.put(state, known);
        }
        return known;
    }

    /** Packs a plane: coordinate in 1/4096 blocks, axis (0 x, 1 y, 2 z) and side (the direction its front faces). */
    private static long key(Direction.Axis planeAxis, int planeSide, double planeCoordinate) {
        return (Math.round(planeCoordinate * 4096.0) << 3) | ((long) planeAxis.ordinal() << 1) | (planeSide > 0 ? 1L : 0L);
    }

    private static int keyAxis(long key) {
        return (int) ((key >> 1) & 3L);
    }

    private static int keySide(long key) {
        return (key & 1L) != 0 ? 1 : -1;
    }

    private static double keyCoordinate(long key) {
        return (key >> 3) / 4096.0;
    }

    /** How far the camera is in front of a plane; at or below 0 when it is behind it. */
    private static double heightOf(long key, Vec3 cameraPos) {
        int a = keyAxis(key);
        double c = a == 0 ? cameraPos.x : a == 1 ? cameraPos.y : cameraPos.z;
        return keySide(key) * (c - keyCoordinate(key));
    }

    private static void choosePlane(Vec3 cameraPos) {
        scores.replaceAll((key, share) -> share * (1.0F - SMOOTHING));
        for (int cell = 0; cell < CELLS; cell++) {
            if (cellPlanes[cell] != NO_PLANE) {
                scores.merge(cellPlanes[cell], CELL_WEIGHTS[cell] * SMOOTHING, Float::sum);
            }
        }
        scores.values().removeIf(share -> share < 1.0E-4F);
        long best = NO_PLANE;
        float bestScore = 0.0F;
        for (Map.Entry<Long, Float> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore && heightOf(entry.getKey(), cameraPos) > MIN_HEIGHT) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        float currentScore = plane == NO_PLANE ? 0.0F : scores.getOrDefault(plane, 0.0F);
        if (plane != NO_PLANE && (heightOf(plane, cameraPos) <= MIN_HEIGHT || currentScore < MIN_SCORE * 0.5F)) {
            plane = NO_PLANE;
            currentScore = 0.0F;
        }
        if (best == NO_PLANE || best == plane || bestScore < MIN_SCORE) {
            challenger = NO_PLANE;
        } else if (plane == NO_PLANE) {
            plane = best;
            challenger = NO_PLANE;
        } else if (bestScore > currentScore * SWITCH_RATIO) {
            long now = System.nanoTime();
            if (challenger != best) {
                challenger = best;
                challengerSince = now;
            } else if (now - challengerSince >= SWITCH_DELAY_NANOS) {
                plane = best;
                challenger = NO_PLANE;
                switches++;
            }
        } else {
            challenger = NO_PLANE;
        }
        shownShare = plane == NO_PLANE ? 0.0F : scores.getOrDefault(plane, 0.0F);
        rival = NO_PLANE;
        rivalShare = 0.0F;
        for (Map.Entry<Long, Float> entry : scores.entrySet()) {
            if (entry.getKey() != plane && entry.getValue() > rivalShare) {
                rival = entry.getKey();
                rivalShare = entry.getValue();
            }
        }
    }

    private static void setUp(Minecraft mc, Vec3 cameraPos, Matrix4f view, Matrix4f projection, float near, float far, float maxDistance) {
        axis = keyAxis(plane);
        side = keySide(plane);
        coordinate = keyCoordinate(plane);
        heightAbove = heightOf(plane, cameraPos);
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
        frustum.set(packMatrix);
        maxDistanceSq = maxDistance * maxDistance;

        var target = mc.getMainRenderTarget();
        int scale = Config.MIRROR_SCALE.get();
        width = Math.max(16, target.width * scale / 100);
        height = Math.max(16, target.height * scale / 100);
    }

    private static String describePlane() {
        String name = axis == 0 ? "x" : axis == 1 ? "y" : "z";
        String next = rival == NO_PLANE ? "" : String.format(", next %s = %.3f %.2f", "xyz".charAt(keyAxis(rival)), keyCoordinate(rival), rivalShare);
        return String.format("mirroring %s = %.3f (%s side, share %.2f%s), %d x %d px, %d switches", name, coordinate, side > 0 ? "+" : "-", shownShare, next, width, height, switches);
    }

    static boolean active() {
        return active;
    }

    /** Boxes relative to the camera that the view can see: in its frustum, in front of the plane and within the terrain range. */
    static BoxTest seen() {
        return (minX, minY, minZ, maxX, maxY, maxZ) -> CubeView.nearestDistanceSq(minX, minY, minZ, maxX, maxY, maxZ) <= maxDistanceSq
                && inView(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** As {@link #seen()} without the terrain range, for Distant Horizons' far terrain. */
    static BoxTest frustumSeen() {
        return MirrorView::inView;
    }

    private static boolean inView(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double front = heightAbove + (side > 0 ? (axis == 0 ? maxX : axis == 1 ? maxY : maxZ) : -(axis == 0 ? minX : axis == 1 ? minY : minZ));
        return front > CLIP_EPSILON && frustum.testAab((float) minX, (float) minY, (float) minZ, (float) maxX, (float) maxY, (float) maxZ);
    }

    /** The mirrored camera in world coordinates: the view's origin. */
    static Vec3 originWorld() {
        return originWorld;
    }

    /** View-projection for positions relative to the mirrored camera. */
    static Matrix4f drawMatrix() {
        return drawMatrix;
    }

    /** The cut plane for positions relative to the mirrored camera: drawn where dot(xyz, p) + w >= 0. */
    static float[] clipPlane() {
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
        } else {
            while (infoBuffer.hasRemaining()) {
                infoBuffer.put(0.0F);
            }
        }
        infoBuffer.flip();
        upload();
        linked = true;
    }

    /** Marks the mirror view unusable (size 0) after a frame without drawing. */
    static void unlink() {
        if (!linked) {
            return;
        }
        linked = false;
        active = false;
        if (infoBuffer == null) {
            return;
        }
        infoBuffer.clear();
        infoBuffer.put(CubeTextures.MAGIC).put(CubeTextures.MIRROR_PROTOCOL).put(0.0F).put(0.0F);
        while (infoBuffer.hasRemaining()) {
            infoBuffer.put(0.0F);
        }
        infoBuffer.flip();
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        upload();
        GlStateManager._bindTexture(previous);
    }

    private static void upload() {
        GlStateManager._bindTexture(CubeTextures.MIRROR_INFO.glId());
        int[] pixelBuffers = CompanionTexture.preparePixelTransfer();
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, CubeTextures.MIRROR_INFO_WIDTH, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, infoBuffer);
        CompanionTexture.restorePixelBuffers(pixelBuffers);
    }
}
