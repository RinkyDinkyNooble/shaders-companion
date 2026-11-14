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
 * Each frame a few rays from the camera through a grid over the screen and a margin around it find the reflecting
 * surfaces in and near view (water and blocks drawn in the translucent layer: stained and tinted glass, ice). Every
 * Minecraft surface is axis-aligned, so a surface's plane is an axis, a side and a coordinate. Each grid cell remembers
 * the plane its last ray hit, and the plane with the most area, weighted towards where the view will be a moment from
 * now (the screen's centre, moved along the camera's turn), is mirrored, so a surface the camera turns towards is
 * mirrored before it comes into view. Another plane takes over after it has covered more for half a second, sooner
 * the more it covers, or at once when the current one leaves the view.
 * <p>
 * The view is drawn like the main view, with the world mirrored in the plane and everything on the far side of it cut
 * away, so a point on the plane lands on the same texel as on screen. Its matrix is written to the info texture, so a
 * pack doesn't depend on that (see API.md). Only the part of the screen the plane covers (with a margin) is drawn, and
 * a mirror that covers little of the screen is drawn at a lower resolution (mirrorLod).
 */
final class MirrorView {
    private static final int GRID_X = 20;
    private static final int GRID_Y = 12;
    private static final int CELLS = GRID_X * GRID_Y;
    /** The grid reaches this far past the screen's edges (as a tangent, 1 at the edge), for surfaces about to come into view. */
    private static final float REACH = 1.3F;
    /** Grid cells visited in a scattered order: a stride with no common factor with the cell count. */
    private static final int CELL_STRIDE = 37;
    private static final int RAYS_PER_FRAME = 30;
    /** How far ahead the camera's turn is followed when weighting the grid. */
    private static final float LOOKAHEAD_SECONDS = 0.2F;
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
    /** ...for this long, or for FAST_SWITCH_DELAY_NANOS when it covers FAST_SWITCH_RATIO times as much. */
    private static final long SWITCH_DELAY_NANOS = 500_000_000L;
    private static final float FAST_SWITCH_RATIO = 2.0F;
    private static final long FAST_SWITCH_DELAY_NANOS = 60_000_000L;
    /** The camera must be at least this far in front of the plane. */
    private static final double MIN_HEIGHT = 0.05;
    /** Geometry closer than this to the plane, on its far side or on it (the mirror itself), is cut from the view. */
    private static final float CLIP_EPSILON = 0.01F;
    /** The view's field of view is this much wider than the screen's (as a tangent), so slightly bent reflection rays at the screen's edge stay in it. */
    private static final float MARGIN = 1.1F;
    private static final long NO_PLANE = Long.MIN_VALUE;
    /** The drawn part of the screen reaches this many grid cells past the cells whose rays hit the plane. */
    private static final float RECT_MARGIN_CELLS = 1.5F;
    /** When the plane covers less, the drawn part shrinks this far towards it each frame (it grows at once). */
    private static final float RECT_SHRINK = 0.1F;
    /** mirrorLod: the plane's share of the weighted screen below which the view drops to 75% and 50% resolution. */
    private static final float LOD_75_BELOW = 0.35F;
    private static final float LOD_50_BELOW = 0.15F;
    /** A lower level waits this long before it is used, a higher one LOD_UP_NANOS. */
    private static final long LOD_DOWN_NANOS = 1_000_000_000L;
    private static final long LOD_UP_NANOS = 200_000_000L;
    private static final RandomSource RANDOM = RandomSource.create(42L);

    static String status = "no reflecting surface in view";
    static long cpuNanos;
    static int width;
    static int height;
    /** Times the mirror moved straight from one plane to another (for the debug overlay). */
    static int switches;

    /** The plane each grid cell's last ray hit. */
    private static final long[] cellPlanes = new long[CELLS];
    /** Each cell's weight this frame, and the sum of the weights with the view still (the scores' unit). */
    private static final float[] cellWeights = new float[CELLS];
    private static final float STILL_TOTAL;
    /** Where the view's centre will be (in screen halves, may lie off screen), and the last frame's view direction. */
    private static float aheadX;
    private static float aheadY;
    private static final Vector3f lastForward = new Vector3f();
    private static long lastForwardNanos;
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
    /** The drawn part of the screen, in screen halves (-MARGIN to MARGIN), for the plane it was found for. */
    private static float rectMinX = -MARGIN;
    private static float rectMinY = -MARGIN;
    private static float rectMaxX = MARGIN;
    private static float rectMaxY = MARGIN;
    private static long rectPlane = NO_PLANE;
    private static final int[] drawnPixels = new int[4];
    /** mirrorLod: the resolution in use (percent of mirrorScale), a different one waiting, and since when. */
    private static int lodPercent = 100;
    private static int lodWaiting = 100;
    private static long lodWaitingSince;

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
            total += cellWeight(cell, 0.0F, 0.0F);
        }
        STILL_TOTAL = total;
    }

    /** A cell's centre on screen, in screen halves (-REACH to REACH). */
    private static float cellX(int cell) {
        return (((cell % GRID_X) + 0.5F) / GRID_X * 2.0F - 1.0F) * REACH;
    }

    private static float cellY(int cell) {
        return (((cell / GRID_X) + 0.5F) / GRID_Y * 2.0F - 1.0F) * REACH;
    }

    /**
     * A bell curve around the point the view is heading for, and a floor of EDGE_WEIGHT on screen, so a larger surface
     * at the side still counts and one off screen counts only while the view turns towards it.
     */
    private static float cellWeight(int cell, float centreX, float centreY) {
        float x = cellX(cell);
        float y = cellY(cell);
        float dx = x - centreX;
        float dy = y - centreY;
        float onScreen = Math.abs(x) < 1.0F && Math.abs(y) < 1.0F ? EDGE_WEIGHT : 0.0F;
        return onScreen + (1.0F - EDGE_WEIGHT) * (float) Math.exp(-(dx * dx + dy * dy) / (CENTRE_SPREAD * CENTRE_SPREAD));
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
            followTurn(view, projection);
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
            if (!active) {
                MirrorShadows.clear();
            }
            cpuNanos = System.nanoTime() - start;
        }
    }

    private static void forget() {
        Arrays.fill(cellPlanes, NO_PLANE);
        lastForwardNanos = 0L;
        scores.clear();
        plane = NO_PLANE;
        challenger = NO_PLANE;
    }

    /**
     * Sets this frame's cell weights around the point on screen the view's centre will reach LOOKAHEAD_SECONDS from now
     * at the speed it turned since the last frame (the screen's centre while it holds still).
     */
    private static void followTurn(Matrix4f view, Matrix4f projection) {
        Matrix4f toWorld = new Matrix4f(view).invert();
        Vector3f forward = toWorld.transformDirection(new Vector3f(0.0F, 0.0F, -1.0F)).normalize();
        long now = System.nanoTime();
        float targetX = 0.0F;
        float targetY = 0.0F;
        double seconds = (now - lastForwardNanos) / 1.0E9;
        if (lastForwardNanos != 0L && seconds > 0.0 && seconds < 0.25) {
            // The direction the turn reaches: this frame's, moved on by the last frame's change, scaled to the lookahead
            Vector3f ahead = new Vector3f(forward).sub(lastForward).mul((float) (LOOKAHEAD_SECONDS / seconds)).add(forward);
            Vector3f inView = view.transformDirection(ahead, new Vector3f());
            if (inView.z < -1.0E-3F) {
                targetX = -inView.x / inView.z * projection.m00();
                targetY = -inView.y / inView.z * projection.m11();
            } else {
                // Turning more than a quarter turn ahead: towards the side it turns to
                float length = (float) Math.sqrt(inView.x * inView.x + inView.y * inView.y);
                if (length > 1.0E-6F) {
                    targetX = inView.x / length * REACH;
                    targetY = inView.y / length * REACH;
                }
            }
            float limit = REACH - 0.15F;
            targetX = Math.max(-limit, Math.min(limit, targetX));
            targetY = Math.max(-limit, Math.min(limit, targetY));
        }
        lastForward.set(forward);
        lastForwardNanos = now;
        // Eased so a single uneven frame moves the weights only half way
        aheadX += (targetX - aheadX) * 0.5F;
        aheadY += (targetY - aheadY) * 0.5F;
        for (int cell = 0; cell < CELLS; cell++) {
            cellWeights[cell] = cellWeight(cell, aheadX, aheadY) / STILL_TOTAL;
        }
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
            float x = (((cell % GRID_X) + jx) / GRID_X * 2.0F - 1.0F) * REACH;
            float y = (((cell / GRID_X) + jy) / GRID_Y * 2.0F - 1.0F) * REACH;
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
                scores.merge(cellPlanes[cell], cellWeights[cell] * SMOOTHING, Float::sum);
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
            } else if (now - challengerSince >= (bestScore > currentScore * FAST_SWITCH_RATIO ? FAST_SWITCH_DELAY_NANOS : SWITCH_DELAY_NANOS)) {
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
        updateRect();
        // Culled to the drawn part: the view's clip space cropped to it (the view is MARGIN wider than the screen)
        float x0 = rectMinX / MARGIN;
        float x1 = rectMaxX / MARGIN;
        float y0 = rectMinY / MARGIN;
        float y1 = rectMaxY / MARGIN;
        Matrix4f crop = new Matrix4f().scaling(2.0F / (x1 - x0), 2.0F / (y1 - y0), 1.0F).translate(-(x0 + x1) * 0.5F, -(y0 + y1) * 0.5F, 0.0F);
        frustum.set(crop.mul(packMatrix));
        maxDistanceSq = maxDistance * maxDistance;
        if (Config.MIRROR_SHADOWS.get()) {
            MirrorShadows.set(packMatrix, normal, (float) heightAbove);
        } else {
            MirrorShadows.clear();
        }

        var target = mc.getMainRenderTarget();
        int scale = Config.MIRROR_SCALE.get() * chooseLod() / 100;
        width = Math.max(16, target.width * scale / 100);
        height = Math.max(16, target.height * scale / 100);
        drawnPixels[0] = Math.max(0, (int) Math.floor((x0 * 0.5F + 0.5F) * width));
        drawnPixels[1] = Math.max(0, (int) Math.floor((y0 * 0.5F + 0.5F) * height));
        drawnPixels[2] = Math.min(width, (int) Math.ceil((x1 * 0.5F + 0.5F) * width)) - drawnPixels[0];
        drawnPixels[3] = Math.min(height, (int) Math.ceil((y1 * 0.5F + 0.5F) * height)) - drawnPixels[1];
    }

    /**
     * The part of the screen the plane covers: the grid cells whose last ray hit it, widened by RECT_MARGIN_CELLS. It
     * grows at once and shrinks a little each frame, so the rays' changing spots don't make it flicker.
     */
    private static void updateRect() {
        float cellWidth = 2.0F * REACH / GRID_X;
        float cellHeight = 2.0F * REACH / GRID_Y;
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int cell = 0; cell < CELLS; cell++) {
            if (cellPlanes[cell] == plane) {
                minX = Math.min(minX, cellX(cell));
                minY = Math.min(minY, cellY(cell));
                maxX = Math.max(maxX, cellX(cell));
                maxY = Math.max(maxY, cellY(cell));
            }
        }
        if (minX > maxX) {
            minX = minY = -MARGIN;
            maxX = maxY = MARGIN;
        } else {
            float marginX = (0.5F + RECT_MARGIN_CELLS) * cellWidth;
            float marginY = (0.5F + RECT_MARGIN_CELLS) * cellHeight;
            // Reaching the screen's edge, it takes in the view's margin past it too
            minX = minX - marginX <= -1.0F ? -MARGIN : minX - marginX;
            minY = minY - marginY <= -1.0F ? -MARGIN : minY - marginY;
            maxX = maxX + marginX >= 1.0F ? MARGIN : maxX + marginX;
            maxY = maxY + marginY >= 1.0F ? MARGIN : maxY + marginY;
        }
        if (rectPlane != plane) {
            rectPlane = plane;
            rectMinX = minX;
            rectMinY = minY;
            rectMaxX = maxX;
            rectMaxY = maxY;
            return;
        }
        rectMinX = minX < rectMinX ? minX : rectMinX + (minX - rectMinX) * RECT_SHRINK;
        rectMinY = minY < rectMinY ? minY : rectMinY + (minY - rectMinY) * RECT_SHRINK;
        rectMaxX = maxX > rectMaxX ? maxX : rectMaxX + (maxX - rectMaxX) * RECT_SHRINK;
        rectMaxY = maxY > rectMaxY ? maxY : rectMaxY + (maxY - rectMaxY) * RECT_SHRINK;
    }

    /** mirrorLod: the resolution for the plane's share of the screen (percent), changed only once it has held. */
    private static int chooseLod() {
        int wanted = !Config.MIRROR_LOD.get() || shownShare >= LOD_75_BELOW ? 100 : shownShare >= LOD_50_BELOW ? 75 : 50;
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

    /** The pixels of the view that are drawn (x, y, width, height); the rest of it says "not drawn". */
    static int[] drawnPixels() {
        return drawnPixels;
    }

    private static String describePlane() {
        String name = axis == 0 ? "x" : axis == 1 ? "y" : "z";
        String next = rival == NO_PLANE ? "" : String.format(", next %s = %.3f %.2f", "xyz".charAt(keyAxis(rival)), keyCoordinate(rival), rivalShare);
        int drawnPercent = Math.round(100.0F * drawnPixels[2] * drawnPixels[3] / Math.max(1, width * height));
        return String.format("mirroring %s = %.3f (%s side, share %.2f%s), %d x %d px (%d%% of it drawn), %d switches, %d shadow casters kept for it", name, coordinate,
                side > 0 ? "+" : "-", shownShare, next, width, height, drawnPercent, switches, MirrorShadows.keptLastFrame);
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
        upload();
    }

    /** Whether the info texture says a mirror view may be there (so {@link #unlink} would write to it). */
    static boolean linked() {
        return linked;
    }

    /** Writes the info texture and puts back the texture that was bound: this mod's textures never stay bound. */
    private static void upload() {
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        Gl.bindTexture(CubeTextures.MIRROR_INFO.glId());
        int[] pixelBuffers = CompanionTexture.preparePixelTransfer();
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, CubeTextures.MIRROR_INFO_WIDTH, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, infoBuffer);
        CompanionTexture.restorePixelBuffers(pixelBuffers);
        Gl.bindTextureIfAlive(previous);
    }
}
