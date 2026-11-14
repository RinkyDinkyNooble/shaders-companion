package com.rinkynooble.shaderscompanion.client;

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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Finds the plane {@link MirrorView} mirrors. Each frame a few rays from the camera through a grid over the screen and a
 * margin around it find the reflecting surfaces in and near view (water and blocks drawn in the translucent layer:
 * stained and tinted glass, ice). Every Minecraft surface is axis-aligned, so a surface's plane is an axis, a side and a
 * coordinate, packed into a long (see {@link #key}). Each grid cell remembers the plane its last ray hit, and the plane
 * with the most area, weighted towards where the view will be a moment from now (the screen's centre, moved along the
 * camera's turn), is chosen, so a surface the camera turns towards is mirrored before it comes into view. Another plane
 * takes over after it has covered more for half a second, sooner the more it covers, or at once when the current one
 * leaves the view.
 * <p>
 * The search runs in real time, not per frame: rays per second and smoothing by time, so the mirror is as quick at 40
 * frames a second as at 200 (the rates were tuned at about 260 frames a second).
 * <p>
 * Screen positions here are in screen halves: -1 to 1 across the screen, as a tangent of the view's angle.
 */
final class MirrorPlaneSearch {
    static final long NO_PLANE = Long.MIN_VALUE;

    private static final int GRID_X = 20;
    private static final int GRID_Y = 12;
    private static final int CELLS = GRID_X * GRID_Y;
    /** The grid reaches this far past the screen's edges (as a tangent, 1 at the edge), for surfaces about to come into view. */
    private static final float REACH = 1.3F;
    private static final float CELL_WIDTH = 2.0F * REACH / GRID_X;
    private static final float CELL_HEIGHT = 2.0F * REACH / GRID_Y;
    /** Grid cells visited in a scattered order: a stride with no common factor with the cell count. */
    private static final int CELL_STRIDE = 37;
    /** Rays per second (a sweep of the grid every 31 ms), and the fewest and most per frame. */
    private static final float RAYS_PER_SECOND = 7800.0F;
    private static final int MIN_RAYS_PER_FRAME = 16;
    private static final int MAX_RAYS_PER_FRAME = 64;
    /** How far ahead the camera's turn is followed when weighting the grid. */
    private static final float LOOKAHEAD_SECONDS = 0.2F;
    private static final double RAY_DISTANCE = 64.0;
    /**
     * A cell's weight falls from 1 at the screen's centre like a bell curve of this width (in half screens), down to
     * EDGE_WEIGHT, so the surface you look at wins over a larger one off to the side.
     */
    private static final float CENTRE_SPREAD = 0.35F;
    private static final float EDGE_WEIGHT = 0.1F;
    /** The shares follow the grid's with this time constant, smoothing out the noise of the rays' spots in their cells. */
    private static final float SMOOTHING_SECONDS = 0.017F;
    /** The point the view heads for follows the turn with this time constant, so one uneven frame moves it only part way. */
    private static final float EASING_SECONDS = 0.0055F;
    /** Longest time step a frame counts as (after a pause the search goes on as after a slow frame). */
    private static final float MAX_STEP_SECONDS = 0.1F;
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
    private static final RandomSource RANDOM = RandomSource.create(42L);

    /** Times the choice moved straight from one plane to another (for the status line). */
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
    private static long lastFindNanos;
    private static float lastStep;
    private static long plane = NO_PLANE;
    /** A plane that covers clearly more than the current one, and since when. */
    private static long challenger = NO_PLANE;
    private static long challengerSince;
    // For the status line: the chosen plane's weighted share of the screen, and the next best plane's
    private static float share;
    private static long rival = NO_PLANE;
    private static float rivalShare;

    static {
        Arrays.fill(cellPlanes, NO_PLANE);
        float total = 0.0F;
        for (int cell = 0; cell < CELLS; cell++) {
            total += cellWeight(cell, 0.0F, 0.0F);
        }
        STILL_TOTAL = total;
    }

    private MirrorPlaneSearch() {
    }

    /** Starts over in a new world: block states are looked at again and nothing seen so far counts. */
    static void checkLevel(ClientLevel level) {
        if (level != scoredLevel) {
            scoredLevel = level;
            translucent.clear();
            forget();
        }
    }

    /** Forgets every plane seen, so the next frames start the search from nothing. */
    static void forget() {
        Arrays.fill(cellPlanes, NO_PLANE);
        lastForwardNanos = 0L;
        lastFindNanos = 0L;
        scores.clear();
        plane = NO_PLANE;
        challenger = NO_PLANE;
    }

    /**
     * Casts this frame's rays and returns the plane to mirror, or {@link #NO_PLANE}.
     *
     * @param view       the main view's rotation (the pose the world is drawn with)
     * @param projection the main view's projection
     */
    static long find(Minecraft mc, Camera camera, Vec3 cameraPos, Matrix4f view, Matrix4f projection) {
        float seconds = step();
        followTurn(view, projection, seconds);
        int rays = Math.max(MIN_RAYS_PER_FRAME, Math.min(MAX_RAYS_PER_FRAME, Math.round(RAYS_PER_SECOND * seconds)));
        castRays(mc, camera, cameraPos, view, projection, rays);
        choosePlane(cameraPos, seconds);
        return plane;
    }

    /** The time since the last search, in seconds (a 60th of a second for the first). */
    private static float step() {
        long now = System.nanoTime();
        float seconds = lastFindNanos == 0L ? 1.0F / 60.0F : Math.max(0.0F, Math.min(MAX_STEP_SECONDS, (now - lastFindNanos) / 1.0E9F));
        lastFindNanos = now;
        lastStep = seconds;
        return seconds;
    }

    /** How far a value smoothed with a time constant moves towards its target in a step of this many seconds. */
    static float follow(float seconds, float timeConstant) {
        return 1.0F - (float) Math.exp(-seconds / timeConstant);
    }

    /** The time step of the last search, in seconds. */
    static float lastStep() {
        return lastStep;
    }

    /** The chosen plane's smoothed, weighted share of the screen (0 without one). */
    static float share() {
        return share;
    }

    /** The next best plane and its share, for the status line ("" when there is none). */
    static String describeRival() {
        return rival == NO_PLANE ? "" : String.format(", next %s = %.3f %.2f", "xyz".charAt(axis(rival)), coordinate(rival), rivalShare);
    }

    /**
     * The part of the screen a plane covers: the centres of the grid cells whose last ray hit it, widened by
     * {@code marginCells} cells plus half a cell (to the cells' edges), in screen halves as {minX, minY, maxX, maxY}.
     * False when no cell's last ray hit it.
     */
    static boolean coveredArea(long key, float marginCells, float[] bounds) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int cell = 0; cell < CELLS; cell++) {
            if (cellPlanes[cell] == key) {
                minX = Math.min(minX, cellX(cell));
                minY = Math.min(minY, cellY(cell));
                maxX = Math.max(maxX, cellX(cell));
                maxY = Math.max(maxY, cellY(cell));
            }
        }
        if (minX > maxX) {
            return false;
        }
        float marginX = (0.5F + marginCells) * CELL_WIDTH;
        float marginY = (0.5F + marginCells) * CELL_HEIGHT;
        bounds[0] = minX - marginX;
        bounds[1] = minY - marginY;
        bounds[2] = maxX + marginX;
        bounds[3] = maxY + marginY;
        return true;
    }

    // A plane's parts: coordinate in 1/4096 blocks, axis (0 x, 1 y, 2 z) and side (the direction its front faces)

    private static long key(Direction.Axis planeAxis, int planeSide, double planeCoordinate) {
        return (Math.round(planeCoordinate * 4096.0) << 3) | ((long) planeAxis.ordinal() << 1) | (planeSide > 0 ? 1L : 0L);
    }

    static int axis(long key) {
        return (int) ((key >> 1) & 3L);
    }

    static int side(long key) {
        return (key & 1L) != 0 ? 1 : -1;
    }

    static double coordinate(long key) {
        return (key >> 3) / 4096.0;
    }

    /** How far the camera is in front of a plane; at or below 0 when it is behind it. */
    static double heightOf(long key, Vec3 cameraPos) {
        int a = axis(key);
        double c = a == 0 ? cameraPos.x : a == 1 ? cameraPos.y : cameraPos.z;
        return side(key) * (c - coordinate(key));
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

    /**
     * Sets this frame's cell weights around the point on screen the view's centre will reach LOOKAHEAD_SECONDS from now
     * at the speed it turned since the last frame (the screen's centre while it holds still).
     */
    private static void followTurn(Matrix4f view, Matrix4f projection, float stepSeconds) {
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
        // Eased so a single uneven frame moves the weights only part way
        float easing = follow(stepSeconds, EASING_SECONDS);
        aheadX += (targetX - aheadX) * easing;
        aheadY += (targetY - aheadY) * easing;
        for (int cell = 0; cell < CELLS; cell++) {
            cellWeights[cell] = cellWeight(cell, aheadX, aheadY) / STILL_TOTAL;
        }
    }

    private static void castRays(Minecraft mc, Camera camera, Vec3 cameraPos, Matrix4f view, Matrix4f projection, int rays) {
        Entity entity = camera.getEntity() != null ? camera.getEntity() : mc.player;
        if (entity == null) {
            return;
        }
        Matrix4f toWorld = new Matrix4f(view).invert();
        float tanX = 1.0F / projection.m00();
        float tanY = 1.0F / projection.m11();
        Vector4f direction = new Vector4f();
        for (int r = 0; r < rays; r++) {
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

    private static void choosePlane(Vec3 cameraPos, float seconds) {
        float smoothing = follow(seconds, SMOOTHING_SECONDS);
        scores.replaceAll((key, value) -> value * (1.0F - smoothing));
        for (int cell = 0; cell < CELLS; cell++) {
            if (cellPlanes[cell] != NO_PLANE) {
                scores.merge(cellPlanes[cell], cellWeights[cell] * smoothing, Float::sum);
            }
        }
        scores.values().removeIf(value -> value < 1.0E-4F);
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
        share = plane == NO_PLANE ? 0.0F : scores.getOrDefault(plane, 0.0F);
        rival = NO_PLANE;
        rivalShare = 0.0F;
        for (Map.Entry<Long, Float> entry : scores.entrySet()) {
            if (entry.getKey() != plane && entry.getValue() > rivalShare) {
                rival = entry.getKey();
                rivalShare = entry.getValue();
            }
        }
    }
}
