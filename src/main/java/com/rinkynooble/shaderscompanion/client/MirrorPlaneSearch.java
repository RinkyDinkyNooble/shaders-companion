package com.rinkynooble.shaderscompanion.client;

import java.util.ArrayDeque;
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
 * Finds the planes {@link MirrorView} mirrors. Each frame a few rays from the camera through a grid over the screen and
 * a margin around it find the reflecting surfaces in and near view (water and blocks drawn in the translucent layer:
 * stained and tinted glass, ice). Every Minecraft surface is axis-aligned, so a surface's plane is an axis, a side and a
 * coordinate, packed into a long (see {@link #key}). Each grid cell remembers the plane its last ray hit, and the planes
 * with the most area, weighted towards where the view will be a moment from now (the screen's centre, moved along the
 * camera's turn), are chosen, one per slot (as many slots as the shader pack asks for), so a surface the camera turns
 * towards is mirrored before it comes into view. A plane keeps its slot while it is chosen. An empty slot takes the
 * best plane left at once; with every slot taken, another plane takes the weakest one's slot (comparing shares smoothed
 * over 1.5 seconds, see STEADY_SECONDS) after it has covered more
 * for half a second, sooner the more it covers. A plane that leaves the view frees its slot.
 * <p>
 * The search runs in real time, not per frame: rays per second and smoothing by time (the rates were tuned at about
 * 260 frames a second). The rays per frame are capped at MAX_RAYS_PER_FRAME to bound the processor time of a slow
 * frame, so below about 120 frames a second a sweep of the grid takes longer (48 ms instead of 31 at 80 frames a
 * second); the holds (half a second) are long against either.
 * <p>
 * Rays are traced block by block for RAY_DISTANCE blocks, against the blocks' collision shapes: plants (grass, flowers,
 * petals) let them through to the water behind, as the screen shows it between their blades, where their outline boxes
 * stopped most rays into a strip of water seen past a grassy bank. A ray going down that reaches nothing in that
 * distance is followed on to the water levels seen so far and the sea level, checked for solid blocks along the way, so a lake far
 * below (seen from a tower) still counts. One that leaves the chunk terrain's range going down without meeting
 * anything may meet a lower water level's Distant Horizons LODs past it: its cell counts towards the drawn part of the
 * view for those levels and keeps a mirrored one from being let go, and at half weight towards the share of those that
 * rays hit too (it may as well be land).
 * <p>
 * Screen positions here are in screen halves: -1 to 1 across the screen, as a tangent of the view's angle.
 */
final class MirrorPlaneSearch {
    static final long NO_PLANE = Long.MIN_VALUE;
    /**
     * What {@link #farWater} returns for a ray that leaves the terrain's range going down without meeting anything (no
     * plane's key: axis 3).
     */
    private static final long PAST_TERRAIN = 3L << 1;

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
    /** Farthest a ray is followed to a water level, and the spacing of its checks for solid blocks on the way. */
    private static final double FAR_WATER_DISTANCE = 512.0;
    private static final double FAR_WATER_STEP = 16.0;
    /** Water levels remembered for far rays (besides the sea level), the most recent first. */
    private static final int WATER_LEVELS = 6;
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
    /**
     * A cell whose ray leaves the terrain's range above a water level that rays hit counts this much towards the level's
     * share: its Distant Horizons water may go on there, or land.
     */
    private static final float PAST_TERRAIN_WEIGHT = 0.5F;
    /** Share of the weighted screen a plane must cover to be mirrored; a chosen one stays down to half of it. */
    private static final float MIN_SCORE = 0.01F;
    /**
     * Switches between chosen planes compare shares smoothed this long. A small surface covers a few cells, and a ray
     * landing on the other side of its edge changes its share by a large part from one sweep to the next: on the fast
     * shares, three glass surfaces of 2 to 4 % of the screen traded the one slot about once a second with the camera
     * still (the dev client's store scene, 2026-11-21), each time switching the reflections on them; smoothed over half
     * a second, still once every 12 seconds.
     */
    private static final float STEADY_SECONDS = 1.5F;
    /**
     * With every slot taken, another plane takes over when its steady share is this much more than the weakest chosen
     * one's and at least SWITCH_MARGIN more of the weighted screen, for SWITCH_DELAY_NANOS...
     */
    private static final float SWITCH_RATIO = 1.2F;
    private static final float SWITCH_MARGIN = 0.01F;
    private static final long SWITCH_DELAY_NANOS = 500_000_000L;
    /**
     * ...or when its share now is FAST_SWITCH_RATIO times the weakest one's (steady, or now where lower) and
     * FAST_SWITCH_MARGIN more of the weighted screen, for FAST_SWITCH_DELAY_NANOS: a large surface the camera turns to,
     * which the steady shares would follow only slowly. Small surfaces never qualify, however their shares jump (the
     * store scene's peak at 5 %).
     */
    private static final float FAST_SWITCH_RATIO = 2.0F;
    private static final float FAST_SWITCH_MARGIN = 0.08F;
    private static final long FAST_SWITCH_DELAY_NANOS = 60_000_000L;
    /**
     * A chosen plane is let go (below half of MIN_SCORE) only once no ray has hit it for this long: a small or far
     * surface covers part of a cell, so some sweeps' rays miss it, and dropping it at once turned the mirror off for
     * single frames (the whole surface flickered between the mirror and the cube).
     */
    private static final long DROP_HOLD_NANOS = 500_000_000L;
    /** A cell's hit keeps counting towards the drawn part of the view for this long after its last ray. */
    private static final long AREA_HOLD_NANOS = DROP_HOLD_NANOS;
    /**
     * Rays that leave the terrain's range above the mirrored water level keep it from being let go for this long after
     * a ray last hit its water (so it isn't drawn on for nothing over far land).
     */
    private static final long PAST_TERRAIN_HOLD_NANOS = 5_000_000_000L;
    /** The camera must be at least this far in front of the plane. */
    private static final double MIN_HEIGHT = 0.05;
    private static final RandomSource RANDOM = RandomSource.create(42L);

    /** Times a slot moved straight from one plane to another (for the status line). */
    static int switches;

    /** The plane each grid cell's last ray hit. */
    private static final long[] cellPlanes = new long[CELLS];
    /** The last plane each cell's rays hit (a miss never replaces it), and when. */
    private static final long[] cellHeldPlanes = new long[CELLS];
    private static final long[] cellHeldNanos = new long[CELLS];
    /**
     * When each cell's rays last left the terrain's range going down (0 never), the height they left it at, and whether
     * its last ray did.
     */
    private static final long[] cellPastTerrainNanos = new long[CELLS];
    private static final double[] cellPastTerrainY = new double[CELLS];
    private static final boolean[] cellPastTerrainNow = new boolean[CELLS];
    /** The water levels rays hit, for {@link #choosePlanes} (room for the remembered ones and the sea level). */
    private static final long[] seenWaterLevels = new long[WATER_LEVELS + 1];
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
    /** The same shares smoothed over STEADY_SECONDS, which switches between chosen planes compare. */
    private static final Map<Long, Float> steadyScores = new HashMap<>();
    private static final Map<BlockState, Boolean> translucent = new IdentityHashMap<>();
    /** Set when Minecraft's block models were baked again: a resource pack can change which blocks are translucent. */
    private static volatile boolean modelsChanged;
    private static final ArrayDeque<Double> waterLevels = new ArrayDeque<>();
    private static ClientLevel scoredLevel;
    private static int rayCursor;
    private static long lastFindNanos;
    private static float lastStep;
    /** The planes chosen, one per slot of the mirror view (NO_PLANE for an empty slot); a plane keeps its slot. */
    private static final long[] slotPlanes = new long[MirrorView.MAX_PLANES];
    /**
     * When each slot's plane was chosen, when a ray last counted it as seen, and when a ray last hit it (rays past the
     * terrain over a water level count as seeing it for a while, see PAST_TERRAIN_HOLD_NANOS).
     */
    private static final long[] slotSince = new long[MirrorView.MAX_PLANES];
    private static final long[] slotSeen = new long[MirrorView.MAX_PLANES];
    private static final long[] slotHit = new long[MirrorView.MAX_PLANES];
    /** A plane that covers clearly more than the weakest chosen one while every slot is taken, and since when. */
    private static long challenger = NO_PLANE;
    private static long challengerSince;
    // Each slot's plane's weighted share of the screen, and for the status line the best plane not chosen
    private static final float[] slotShares = new float[MirrorView.MAX_PLANES];
    private static final float[] slotSteadyShares = new float[MirrorView.MAX_PLANES];
    private static long rival = NO_PLANE;
    private static float rivalShare;

    static {
        Arrays.fill(cellPlanes, NO_PLANE);
        Arrays.fill(cellHeldPlanes, NO_PLANE);
        Arrays.fill(slotPlanes, NO_PLANE);
        float total = 0.0F;
        for (int cell = 0; cell < CELLS; cell++) {
            total += cellWeight(cell, 0.0F, 0.0F);
        }
        STILL_TOTAL = total;
    }

    private MirrorPlaneSearch() {
    }

    /**
     * Starts over in a new world: block states are looked at again and nothing seen so far counts. After a resource
     * reload only the block states are looked at again.
     */
    static void checkLevel(ClientLevel level) {
        if (level != scoredLevel) {
            scoredLevel = level;
            translucent.clear();
            forget();
        }
        if (modelsChanged) {
            modelsChanged = false;
            translucent.clear();
        }
    }

    /** Minecraft's block models were baked again (resource reload). */
    static void modelsChanged() {
        modelsChanged = true;
    }

    /** Forgets every plane seen, so the next frames start the search from nothing. */
    static void forget() {
        Arrays.fill(cellPlanes, NO_PLANE);
        Arrays.fill(cellHeldPlanes, NO_PLANE);
        Arrays.fill(cellPastTerrainNanos, 0L);
        Arrays.fill(cellPastTerrainNow, false);
        waterLevels.clear();
        lastForwardNanos = 0L;
        lastFindNanos = 0L;
        scores.clear();
        steadyScores.clear();
        Arrays.fill(slotPlanes, NO_PLANE);
        Arrays.fill(slotShares, 0.0F);
        Arrays.fill(slotSteadyShares, 0.0F);
        challenger = NO_PLANE;
    }

    /**
     * Casts this frame's rays and chooses up to {@code slots} planes to mirror, read with {@link #plane(int)}.
     *
     * @param view       the main view's rotation (the pose the world is drawn with)
     * @param projection the main view's projection
     */
    static void find(Minecraft mc, Camera camera, Vec3 cameraPos, Matrix4f view, Matrix4f projection, int slots) {
        float seconds = step();
        followTurn(view, projection, seconds);
        int rays = Math.max(MIN_RAYS_PER_FRAME, Math.min(MAX_RAYS_PER_FRAME, Math.round(RAYS_PER_SECOND * seconds)));
        castRays(mc, camera, cameraPos, view, projection, rays);
        choosePlanes(cameraPos, seconds, slots);
    }

    /** The plane a slot mirrors, or {@link #NO_PLANE}. */
    static long plane(int slot) {
        return slotPlanes[slot];
    }

    /** The time since the last search, in seconds (a 60th of a second for the first). */
    private static float step() {
        long now = System.nanoTime();
        float seconds = lastFindNanos == 0L ? 1.0F / 60.0F : Math.max(0.0F, Math.min(MAX_STEP_SECONDS, (now - lastFindNanos) / 1.0E9F));
        lastFindNanos = now;
        lastStep = seconds;
        return seconds;
    }

    /** The time step of the last search, in seconds. */
    static float lastStep() {
        return lastStep;
    }

    /** How long a slot's plane has been mirrored, in seconds (0 without one). */
    static float planeAge(int slot) {
        return slotPlanes[slot] == NO_PLANE ? 0.0F : (System.nanoTime() - slotSince[slot]) / 1.0E9F;
    }

    /** A slot's plane's smoothed, weighted share of the screen (0 without one). */
    static float share(int slot) {
        return slotShares[slot];
    }

    /**
     * The larger of a slot's plane's share and its steady share: for choices that may rise quickly but should fall only
     * when the plane really covers less (the share of a small surface jumps from sweep to sweep).
     */
    static float heldShare(int slot) {
        return Math.max(slotShares[slot], slotSteadyShares[slot]);
    }

    /** The next best plane and its share, for the status line ("" when there is none). */
    static String describeRival() {
        return rival == NO_PLANE ? "" : String.format(", next %s = %.3f %.2f", "xyz".charAt(axis(rival)), coordinate(rival), rivalShare);
    }

    /**
     * The part of the screen a plane covers: the centres of the grid cells whose rays hit it within AREA_HOLD_NANOS (for
     * a water level also those whose rays left the terrain's range above it, going down, in that time), widened by
     * {@code marginCells} cells plus half a cell (to the cells' edges), in screen halves as {minX, minY, maxX, maxY}.
     * False when no cell counts in that time.
     */
    static boolean coveredArea(long key, float marginCells, float[] bounds) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        long now = System.nanoTime();
        boolean waterLevel = isWaterLevel(key);
        for (int cell = 0; cell < CELLS; cell++) {
            if (cellPlanes[cell] == key || cellHeldPlanes[cell] == key && now - cellHeldNanos[cell] < AREA_HOLD_NANOS
                    || waterLevel && pastTerrain(cell, key, now)) {
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
        float easing = Smoothing.follow(stepSeconds, EASING_SECONDS);
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
        long now = System.nanoTime();
        double reach = farReach(mc);
        for (int r = 0; r < rays; r++) {
            int sweep = rayCursor / CELLS;
            int cell = (rayCursor % CELLS) * CELL_STRIDE % CELLS;
            rayCursor = (rayCursor + 1) % (CELLS * 1024);
            // A different spot inside the cell on each sweep: the R2 sequence, which spreads consecutive sweeps' spots
            // evenly over the cell, shifted by a hash of the cell. (The spots were (cell * a) ^ (sweep * b) before:
            // their bits climbed slowly from sweep to sweep, every cell's in step, so a small surface's share rose and
            // fell over several seconds and the plane chosen with the camera still changed every ten seconds or so.)
            int hash = mix(cell + 1);
            float jx = fraction((hash & 0xFFFF) / 65536.0F + sweep * 0.75487766F);
            float jy = fraction((hash >>> 16) / 65536.0F + sweep * 0.56984029F);
            float x = (((cell % GRID_X) + jx) / GRID_X * 2.0F - 1.0F) * REACH;
            float y = (((cell / GRID_X) + jy) / GRID_Y * 2.0F - 1.0F) * REACH;
            direction.set(x * tanX, y * tanY, -1.0F, 0.0F).mul(toWorld);
            Vec3 rayDir = new Vec3(direction.x, direction.y, direction.z).normalize();
            Vec3 end = cameraPos.add(rayDir.scale(RAY_DISTANCE));
            BlockHitResult hit = mc.level.clip(new ClipContext(cameraPos, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, entity));
            long found = hit.getType() == HitResult.Type.MISS ? farWater(mc.level, cameraPos, rayDir, reach) : reflectingPlane(mc, hit);
            cellPastTerrainNow[cell] = found == PAST_TERRAIN;
            if (found == PAST_TERRAIN) {
                cellPastTerrainNanos[cell] = now;
                cellPastTerrainY[cell] = cameraPos.y + rayDir.y * reach;
                found = NO_PLANE;
            }
            cellPlanes[cell] = found;
            if (found != NO_PLANE) {
                cellHeldPlanes[cell] = cellPlanes[cell];
                cellHeldNanos[cell] = now;
            }
        }
    }

    /** MurmurHash3's finaliser: every bit of the input affects every bit of the result. */
    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        return h ^ (h >>> 16);
    }

    private static float fraction(float value) {
        return value - (float) Math.floor(value);
    }

    /** How far rays are followed to far water: the chunk terrain's range, at most FAR_WATER_DISTANCE. */
    private static double farReach(Minecraft mc) {
        return Math.min(FAR_WATER_DISTANCE, mc.options.getEffectiveRenderDistance() * 16.0);
    }

    /** Whether a cell's rays left the terrain's range above a water level, going down, within AREA_HOLD_NANOS. */
    private static boolean pastTerrain(int cell, long key, long now) {
        return cellPastTerrainNanos[cell] != 0L && now - cellPastTerrainNanos[cell] < AREA_HOLD_NANOS && cellPastTerrainY[cell] > coordinate(key);
    }

    private static boolean contains(long[] keys, int count, long key) {
        for (int i = 0; i < count; i++) {
            if (keys[i] == key) {
                return true;
            }
        }
        return false;
    }

    private static double seaSurface(ClientLevel level) {
        return level.getSeaLevel() - 1 + 8.0 / 9.0;
    }

    /** Whether a plane is a water level: one a ray hit, or the sea level. */
    private static boolean isWaterLevel(long key) {
        if (axis(key) != 1 || side(key) < 0) {
            return false;
        }
        if (scoredLevel != null && key == key(Direction.Axis.Y, 1, seaSurface(scoredLevel))) {
            return true;
        }
        for (double waterLevel : waterLevels) {
            if (key == key(Direction.Axis.Y, 1, waterLevel)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The plane of a water level a ray going down reaches past RAY_DISTANCE (within reach): the first level below the
     * camera, of the remembered ones and the sea level, where the ray meets water; NO_PLANE when it meets anything solid
     * first (checked every FAR_WATER_STEP blocks) or no water. PAST_TERRAIN when the next level lies past reach and
     * nothing solid is in the way up to it. Rays within about half a degree of level only count for PAST_TERRAIN.
     */
    private static long farWater(ClientLevel level, Vec3 from, Vec3 rayDir, double reach) {
        if (rayDir.y >= 0.0) {
            return NO_PLANE;
        }
        boolean grazing = rayDir.y > -0.01;
        double seaSurface = seaSurface(level);
        double checked = RAY_DISTANCE;
        // The levels in the order the ray reaches them, highest first: each round takes the highest one below the last
        double below = from.y;
        while (true) {
            double next = seaSurface < below ? seaSurface : Double.NEGATIVE_INFINITY;
            for (double waterLevel : waterLevels) {
                if (waterLevel < below && waterLevel > next) {
                    next = waterLevel;
                }
            }
            if (next == Double.NEGATIVE_INFINITY) {
                return NO_PLANE;
            }
            below = next;
            double t = (from.y - next) / -rayDir.y;
            if (t > reach) {
                for (; checked < reach; checked += FAR_WATER_STEP) {
                    if (level.getBlockState(BlockPos.containing(from.add(rayDir.scale(checked)))).canOcclude()) {
                        return NO_PLANE;
                    }
                }
                return PAST_TERRAIN;
            }
            if (grazing) {
                return NO_PLANE;
            }
            if (t <= RAY_DISTANCE) {
                continue;
            }
            for (; checked < t; checked += FAR_WATER_STEP) {
                if (level.getBlockState(BlockPos.containing(from.add(rayDir.scale(checked)))).canOcclude()) {
                    return NO_PLANE;
                }
            }
            BlockPos at = BlockPos.containing(from.x + rayDir.x * t, next - 0.05, from.z + rayDir.z * t);
            if (level.getFluidState(at).is(FluidTags.WATER)) {
                return key(Direction.Axis.Y, 1, next);
            }
            if (level.getBlockState(at).canOcclude()) {
                return NO_PLANE;
            }
            // Neither water nor solid at this level (air over lower ground): on down to the next one
        }
    }

    /** Remembers a water level a ray hit, for far rays. */
    private static void rememberWaterLevel(double surface) {
        if (!waterLevels.isEmpty() && waterLevels.peekFirst() == surface) {
            return;
        }
        waterLevels.remove(surface);
        waterLevels.addFirst(surface);
        if (waterLevels.size() > WATER_LEVELS) {
            waterLevels.removeLast();
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
                rememberWaterLevel(surface);
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

    private static void choosePlanes(Vec3 cameraPos, float seconds, int slots) {
        long now = System.nanoTime();
        float smoothing = Smoothing.follow(seconds, SMOOTHING_SECONDS);
        scores.replaceAll((key, value) -> value * (1.0F - smoothing));
        // The water levels rays hit (within AREA_HOLD_NANOS), whose water may go on past the terrain's range
        int seenLevels = 0;
        for (int cell = 0; cell < CELLS && seenLevels < seenWaterLevels.length; cell++) {
            long key = cellHeldPlanes[cell];
            if (key != NO_PLANE && now - cellHeldNanos[cell] < AREA_HOLD_NANOS && !contains(seenWaterLevels, seenLevels, key) && isWaterLevel(key)) {
                seenWaterLevels[seenLevels++] = key;
            }
        }
        for (int cell = 0; cell < CELLS; cell++) {
            if (cellPlanes[cell] != NO_PLANE) {
                scores.merge(cellPlanes[cell], cellWeights[cell] * smoothing, Float::sum);
            } else if (cellPastTerrainNow[cell]) {
                for (int i = 0; i < seenLevels; i++) {
                    if (cellPastTerrainY[cell] > coordinate(seenWaterLevels[i])) {
                        scores.merge(seenWaterLevels[i], cellWeights[cell] * PAST_TERRAIN_WEIGHT * smoothing, Float::sum);
                    }
                }
            }
        }
        scores.values().removeIf(value -> value < 1.0E-4F);
        float steadying = Smoothing.follow(seconds, STEADY_SECONDS);
        steadyScores.replaceAll((key, value) -> value + (scores.getOrDefault(key, 0.0F) - value) * steadying);
        for (Map.Entry<Long, Float> entry : scores.entrySet()) {
            steadyScores.putIfAbsent(entry.getKey(), entry.getValue() * steadying);
        }
        steadyScores.values().removeIf(value -> value < 1.0E-4F);
        // Slots past the ones in use (the pack asks for fewer planes now) are let go
        for (int slot = slots; slot < slotPlanes.length; slot++) {
            slotPlanes[slot] = NO_PLANE;
        }
        for (int slot = 0; slot < slots; slot++) {
            long plane = slotPlanes[slot];
            if (plane == NO_PLANE) {
                continue;
            }
            // A water level is seen too where rays leave the terrain's range above it (its Distant Horizons water may
            // go on), for PAST_TERRAIN_HOLD_NANOS after a ray last hit it
            boolean holdsPastTerrain = now - slotHit[slot] < PAST_TERRAIN_HOLD_NANOS && isWaterLevel(plane);
            for (int cell = 0; cell < CELLS; cell++) {
                if (cellPlanes[cell] == plane) {
                    slotSeen[slot] = now;
                    slotHit[slot] = now;
                    break;
                }
                if (holdsPastTerrain && pastTerrain(cell, plane, now)) {
                    slotSeen[slot] = now;
                }
            }
            float score = scores.getOrDefault(plane, 0.0F);
            if (heightOf(plane, cameraPos) <= MIN_HEIGHT || score < MIN_SCORE * 0.5F && now - slotSeen[slot] > DROP_HOLD_NANOS) {
                slotPlanes[slot] = NO_PLANE;
            }
        }
        // Empty slots take the planes that cover the most at once
        long best = bestUnchosen(cameraPos);
        for (int slot = 0; slot < slots && best != NO_PLANE; slot++) {
            if (slotPlanes[slot] == NO_PLANE) {
                choose(slot, best, now);
                challenger = NO_PLANE;
                best = bestUnchosen(cameraPos);
            }
        }
        // With every slot taken, another plane takes the weakest one's slot (with one slot: the current plane's) once it
        // has covered clearly more for a while on the steady shares, or at once when it covers much more of the screen
        // now (a large surface the camera turns to)
        long contender = NO_PLANE;
        long delay = 0L;
        int weakest = 0;
        if (best != NO_PLANE) {
            for (int slot = 1; slot < slots; slot++) {
                if (steadyScores.getOrDefault(slotPlanes[slot], 0.0F) < steadyScores.getOrDefault(slotPlanes[weakest], 0.0F)) {
                    weakest = slot;
                }
            }
            float weakestScore = steadyScores.getOrDefault(slotPlanes[weakest], 0.0F);
            // Against the weakest one's share now where that is lower: a plane the camera has turned away from yields
            // at once instead of when its steady share has faded
            float weakestNow = Math.min(weakestScore, scores.getOrDefault(slotPlanes[weakest], 0.0F));
            float fastScore = scores.getOrDefault(best, 0.0F);
            if (fastScore > weakestNow * FAST_SWITCH_RATIO && fastScore - weakestNow > FAST_SWITCH_MARGIN) {
                contender = best;
                delay = FAST_SWITCH_DELAY_NANOS;
            } else {
                long steadiest = steadiestUnchosen(cameraPos);
                float steadyScore = steadyScores.getOrDefault(steadiest, 0.0F);
                if (steadiest != NO_PLANE && steadyScore > weakestScore * SWITCH_RATIO && steadyScore - weakestScore > SWITCH_MARGIN) {
                    contender = steadiest;
                    delay = SWITCH_DELAY_NANOS;
                }
            }
        }
        if (contender == NO_PLANE) {
            challenger = NO_PLANE;
        } else if (challenger != contender) {
            challenger = contender;
            challengerSince = now;
        } else if (now - challengerSince >= delay) {
            choose(weakest, contender, now);
            challenger = NO_PLANE;
            switches++;
        }
        rival = NO_PLANE;
        rivalShare = 0.0F;
        for (Map.Entry<Long, Float> entry : scores.entrySet()) {
            if (entry.getValue() > rivalShare && !chosen(entry.getKey())) {
                rival = entry.getKey();
                rivalShare = entry.getValue();
            }
        }
        for (int slot = 0; slot < slotPlanes.length; slot++) {
            slotShares[slot] = slotPlanes[slot] == NO_PLANE ? 0.0F : scores.getOrDefault(slotPlanes[slot], 0.0F);
            slotSteadyShares[slot] = slotPlanes[slot] == NO_PLANE ? 0.0F : steadyScores.getOrDefault(slotPlanes[slot], 0.0F);
        }
    }

    /** The plane not chosen yet that covers the most, at least MIN_SCORE, with the camera in front of it; or NO_PLANE. */
    private static long bestUnchosen(Vec3 cameraPos) {
        long best = NO_PLANE;
        float bestScore = MIN_SCORE;
        for (Map.Entry<Long, Float> entry : scores.entrySet()) {
            if (entry.getValue() >= bestScore && (best == NO_PLANE || entry.getValue() > bestScore) && !chosen(entry.getKey())
                    && heightOf(entry.getKey(), cameraPos) > MIN_HEIGHT) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        return best;
    }

    /** The plane not chosen yet with the largest steady share, among those that cover at least MIN_SCORE now; or NO_PLANE. */
    private static long steadiestUnchosen(Vec3 cameraPos) {
        long best = NO_PLANE;
        float bestScore = 0.0F;
        for (Map.Entry<Long, Float> entry : steadyScores.entrySet()) {
            if (entry.getValue() > bestScore && scores.getOrDefault(entry.getKey(), 0.0F) >= MIN_SCORE && !chosen(entry.getKey())
                    && heightOf(entry.getKey(), cameraPos) > MIN_HEIGHT) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        return best;
    }

    private static boolean chosen(long key) {
        for (long plane : slotPlanes) {
            if (plane == key) {
                return true;
            }
        }
        return false;
    }

    private static void choose(int slot, long plane, long now) {
        slotPlanes[slot] = plane;
        slotSince[slot] = now;
        slotSeen[slot] = now;
        slotHit[slot] = now;
    }
}
