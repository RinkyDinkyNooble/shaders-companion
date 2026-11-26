package com.rinkynooble.shaderscompanion.client;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/**
 * The lattice's detail (API.md, "Lattice", detail): each block's drawn shape at full resolution, for contact shading
 * finer than the quarter-block shapes. Per block state, the distance from points in the block to the nearest surface
 * Minecraft draws for it (its model's quads, or its outline boxes for blocks drawn otherwise), sampled at 16 x 16 x 16
 * points and kept in a slot of an atlas; blocks with the same shape share a slot. Full cubes and blocks that shade
 * nothing (air, plants and other blocks without collision, translucent blocks) take no slot. Worked out once per state;
 * the distances are baked a little each frame, and a slot reads as far from everything until it is baked.
 */
final class LatticeDetail {
    static final int MAGIC = 4244;
    static final int PROTOCOL = 1;
    static final String DISTANCE = "lattice_detail_distance";
    /** A block's detail: nothing that shades, a full cube, or an atlas slot from {@link #FIRST_SLOT}. */
    static final int NONE = 0;
    static final int FULL = 1;
    private static final int FIRST_SLOT = 2;
    /**
     * Flag on a detail: the openness field holds the shape as it is (a solid block whose faces all lie on quarter
     * blocks: full cubes, slabs, stairs), so a pack's contact shade needn't add it again.
     */
    static final int FIELD_EXACT = 1 << 27;
    /** Flag on a block: it or one of its 26 neighbours has a detail the openness field doesn't hold as it is. */
    static final int NEAR_FINE = 1 << 26;
    static final int SLOT_BITS = NEAR_FINE - 1;
    /** Samples per block along each axis. */
    static final int RES = 16;
    /** Distances are stored up to this many blocks. */
    static final float RANGE = 0.5F;
    /** The atlas: 16 x 16 slots per layer, layers stacked along z. */
    private static final int ROW = 16;
    private static final int LAYER_SLOTS = ROW * ROW;
    private static final int MAX_LAYERS = 64;
    /** Processor time a frame for baking, in nanoseconds. */
    private static final long BAKE_NANOS = 300_000L;
    private static final float EPS = 1e-4F;
    private static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private static int atlas;
    private static int layers;
    private static int nextSlot = FIRST_SLOT;
    private static final Map<ShapeKey, Integer> slots = new HashMap<>();
    /** Baked distances per slot (index slot - FIRST_SLOT), kept to fill a larger atlas. */
    private static final List<byte[]> baked = new ArrayList<>();
    private static final ArrayDeque<Bake> queue = new ArrayDeque<>();
    private static final RandomSource random = RandomSource.create();
    private static ByteBuffer upload;
    private static long bakeNanos;

    private LatticeDetail() {
    }

    static int atlas() {
        return atlas;
    }

    static int slotCount() {
        return nextSlot - FIRST_SLOT;
    }

    /** Forgets every shape (models were reloaded, or a new start). */
    static void reset() {
        slots.clear();
        baked.clear();
        queue.clear();
        nextSlot = FIRST_SLOT;
        if (atlas != 0) {
            clearLayers(0, layers);
        }
    }

    /**
     * A block state's detail: {@link #NONE}, {@link #FULL} or its atlas slot, with {@link #FIELD_EXACT} (render
     * thread). Worked out from the model Minecraft draws for it, so the distances start at the surfaces a pack shades.
     */
    static int describe(BlockState state, ClientLevel level, BlockPos pos, boolean collides) {
        if (!collides || state.isAir()) {
            return NONE;
        }
        float[][] quads;
        try {
            if (state.isSolidRender(level, pos)) {
                return FULL | FIELD_EXACT;
            }
            if (state.hasOffsetFunction()) {
                // Drawn somewhere else in every block (bamboo, dripstone): no shape to match
                return NONE;
            }
            quads = state.getRenderShape() == RenderShape.MODEL ? modelQuads(state, level, pos) : null;
            if (quads == null) {
                quads = boxQuads(state.getShape(level, pos, CollisionContext.empty()));
            }
        } catch (RuntimeException e) {
            return state.canOcclude() ? FULL | FIELD_EXACT : NONE;
        }
        if (quads.length == 0) {
            return NONE;
        }
        // The field lets light through blocks that don't hide their neighbours (glass, leaves, much furniture)
        int exact = state.canOcclude() && onQuarters(quads) ? FIELD_EXACT : 0;
        if (fullCube(quads)) {
            return FULL | exact;
        }
        ShapeKey key = new ShapeKey(quads);
        Integer slot = slots.get(key);
        if (slot == null) {
            if (nextSlot - FIRST_SLOT >= MAX_LAYERS * LAYER_SLOTS) {
                return NONE;
            }
            slot = nextSlot++;
            slots.put(key, slot);
            baked.add(null);
            queue.add(new Bake(slot, quads));
            // Room for it now, so it reads as far from everything until it is baked
            ensureLayers(slot - FIRST_SLOT + 1);
        }
        return slot | exact;
    }

    /** Whether a detail adds to what the openness field holds (a contact shade's occluder on any surface). */
    static boolean fine(int detail) {
        return (detail & SLOT_BITS) != NONE && (detail & FIELD_EXACT) == 0;
    }

    /** Whether every corner of the quads lies on the quarter-block grid. */
    private static boolean onQuarters(float[][] quads) {
        for (float[] q : quads) {
            for (float value : q) {
                if (Math.abs(value * 4.0F - Math.round(value * 4.0F)) > 4 * EPS) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The model's quads in block coordinates (each 4 corners, x y z), leaving out translucent parts; null when the model
     * has no render types (the caller falls back to the block's outline boxes).
     */
    private static float[][] modelQuads(BlockState state, ClientLevel level, BlockPos pos) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        ModelData data;
        try {
            data = model.getModelData(level, pos, state, ModelData.EMPTY);
        } catch (RuntimeException e) {
            data = ModelData.EMPTY;
        }
        random.setSeed(42L);
        ChunkRenderTypeSet types = model.getRenderTypes(state, random, data);
        List<float[]> out = new ArrayList<>();
        boolean any = false;
        for (RenderType type : types) {
            any = true;
            if (type == RenderType.translucent()) {
                continue;
            }
            for (Direction side : SIDES) {
                random.setSeed(42L);
                for (BakedQuad quad : model.getQuads(state, side, random, data, type)) {
                    int[] vertices = quad.getVertices();
                    int stride = vertices.length / 4;
                    float[] corners = new float[12];
                    for (int v = 0; v < 4; v++) {
                        for (int a = 0; a < 3; a++) {
                            corners[v * 3 + a] = Float.intBitsToFloat(vertices[v * stride + a]);
                        }
                    }
                    out.add(corners);
                }
            }
        }
        if (!any) {
            return null;
        }
        return out.toArray(new float[0][]);
    }

    /** The faces of a shape's boxes as quads. */
    private static float[][] boxQuads(VoxelShape shape) {
        List<float[]> out = new ArrayList<>();
        shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
            float a = (float) x0, b = (float) y0, c = (float) z0, d = (float) x1, e = (float) y1, f = (float) z1;
            out.add(new float[]{a, b, c, a, e, c, a, e, f, a, b, f});
            out.add(new float[]{d, b, c, d, e, c, d, e, f, d, b, f});
            out.add(new float[]{a, b, c, d, b, c, d, b, f, a, b, f});
            out.add(new float[]{a, e, c, d, e, c, d, e, f, a, e, f});
            out.add(new float[]{a, b, c, d, b, c, d, e, c, a, e, c});
            out.add(new float[]{a, b, f, d, b, f, d, e, f, a, e, f});
        });
        return out.toArray(new float[0][]);
    }

    /** Whether the quads are a full cube's six faces and nothing else. */
    private static boolean fullCube(float[][] quads) {
        double[] area = new double[6];
        for (float[] q : quads) {
            Rect r = Rect.of(q);
            if (r == null || !(Math.abs(r.c) < EPS || Math.abs(r.c - 1) < EPS)) {
                return false;
            }
            area[r.axis * 2 + (r.c > 0.5F ? 1 : 0)] += (Math.min(r.u1, 1) - Math.max(r.u0, 0)) * (Math.min(r.v1, 1) - Math.max(r.v0, 0));
        }
        for (double a : area) {
            if (a < 0.999) {
                return false;
            }
        }
        return true;
    }

    /**
     * Bakes queued shapes for up to {@link #BAKE_NANOS} (at least one layer of samples) and uploads finished ones; the
     * caller restores the 3D texture binding (render thread).
     */
    static void update() {
        if (queue.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        do {
            Bake bake = queue.peek();
            bake.layer();
            if (bake.done()) {
                queue.poll();
                byte[] bytes = bake.bytes();
                baked.set(bake.slot - FIRST_SLOT, bytes);
                uploadSlot(bake.slot, bytes);
            }
        } while (!queue.isEmpty() && System.nanoTime() - start < BAKE_NANOS);
        long nanos = System.nanoTime() - start;
        bakeNanos = bakeNanos == 0 ? nanos : (long) (bakeNanos * 0.9 + nanos * 0.1);
    }

    /**
     * The atlas exists with room for this many slots (a larger one is filled again from {@link #baked}); the caller
     * restores the 3D texture binding.
     */
    static void ensureLayers(int slotsNeeded) {
        int wanted = Math.max(1, (slotsNeeded + LAYER_SLOTS - 1) / LAYER_SLOTS);
        if (atlas != 0 && wanted <= layers) {
            return;
        }
        int newLayers = Math.max(wanted, layers == 0 ? 1 : layers * 2);
        newLayers = Math.min(newLayers, MAX_LAYERS);
        if (atlas == 0) {
            atlas = GL11C.glGenTextures();
        }
        layers = newLayers;
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, atlas);
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL30C.GL_R8, ROW * RES, ROW * RES, layers * RES, 0, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        clearLayers(0, layers);
        for (int i = 0; i < baked.size(); i++) {
            if (baked.get(i) != null) {
                uploadSlot(i + FIRST_SLOT, baked.get(i));
            }
        }
    }

    /** Sets layers to "far from everything" (the caller restores the 3D binding). */
    private static void clearLayers(int from, int to) {
        int layerBytes = ROW * RES * ROW * RES * RES;
        ByteBuffer far = MemoryUtil.memAlloc(layerBytes);
        try (PixelTransfer transfer = PixelTransfer.stateOnly()) {
            MemoryUtil.memSet(far, 0xFF);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, atlas);
            transfer.unpackAlignment(1);
            for (int layer = from; layer < to; layer++) {
                GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, layer * RES, ROW * RES, ROW * RES, RES, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, far);
            }
        } finally {
            MemoryUtil.memFree(far);
        }
    }

    private static void uploadSlot(int slot, byte[] bytes) {
        int i = slot - FIRST_SLOT;
        if (upload == null) {
            upload = MemoryUtil.memAlloc(RES * RES * RES);
        }
        upload.clear();
        upload.put(bytes).flip();
        try (PixelTransfer transfer = PixelTransfer.stateOnly()) {
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, atlas);
            transfer.unpackAlignment(1);
            GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, (i % ROW) * RES, (i / ROW % ROW) * RES, (i / LAYER_SLOTS) * RES, RES, RES, RES,
                    GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, upload);
        }
    }

    /** For the status line. */
    static String status() {
        return String.format("detail %d shapes%s (%.0f us a frame baking)", slotCount(),
                queue.isEmpty() ? "" : ", " + queue.size() + " to bake", bakeNanos / 1.0e3);
    }

    /** A quad that is an axis-aligned rectangle: on the plane {@code axis = c}, spanning u and v (the other axes in order). */
    private record Rect(int axis, float c, float u0, float u1, float v0, float v1) {
        static Rect of(float[] q) {
            for (int axis = 0; axis < 3; axis++) {
                float lo = Math.min(Math.min(q[axis], q[3 + axis]), Math.min(q[6 + axis], q[9 + axis]));
                float hi = Math.max(Math.max(q[axis], q[3 + axis]), Math.max(q[6 + axis], q[9 + axis]));
                if (hi - lo > EPS) {
                    continue;
                }
                int ua = (axis + 1) % 3;
                int va = (axis + 2) % 3;
                if (ua > va) {
                    int t = ua;
                    ua = va;
                    va = t;
                }
                float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
                for (int k = 0; k < 4; k++) {
                    u0 = Math.min(u0, q[k * 3 + ua]);
                    u1 = Math.max(u1, q[k * 3 + ua]);
                    v0 = Math.min(v0, q[k * 3 + va]);
                    v1 = Math.max(v1, q[k * 3 + va]);
                }
                for (int k = 0; k < 4; k++) {
                    float u = q[k * 3 + ua];
                    float v = q[k * 3 + va];
                    if (Math.min(Math.abs(u - u0), Math.abs(u - u1)) > EPS || Math.min(Math.abs(v - v0), Math.abs(v - v1)) > EPS) {
                        return null;
                    }
                }
                return new Rect(axis, (lo + hi) * 0.5F, u0, u1, v0, v1);
            }
            return null;
        }
    }

    /** A shape's quads, compared by their corners in 1/1024 block, in any order. */
    private static final class ShapeKey {
        private final int[] corners;
        private final int hash;

        ShapeKey(float[][] quads) {
            long[] sorted = new long[quads.length * 2];
            int[] all = new int[quads.length * 12];
            for (int i = 0; i < quads.length; i++) {
                long a = 0;
                long b = 0;
                for (int k = 0; k < 12; k++) {
                    int value = Math.round(quads[i][k] * 1024.0F);
                    all[i * 12 + k] = value;
                    if (k < 6) {
                        a = a * 1_000_003L + value;
                    } else {
                        b = b * 1_000_003L + value;
                    }
                }
                sorted[i * 2] = a;
                sorted[i * 2 + 1] = b;
            }
            // Order quads by their two hashes, so the same quads in another order make the same key
            Integer[] order = new Integer[quads.length];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (x, y) -> sorted[x * 2] != sorted[y * 2] ? Long.compare(sorted[x * 2], sorted[y * 2]) : Long.compare(sorted[x * 2 + 1], sorted[y * 2 + 1]));
            corners = new int[all.length];
            for (int i = 0; i < order.length; i++) {
                System.arraycopy(all, order[i] * 12, corners, i * 12, 12);
            }
            hash = Arrays.hashCode(corners);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ShapeKey other && other.hash == hash && Arrays.equals(other.corners, corners);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** One shape's distances, baked a layer (16 x 16 samples) at a time. */
    private static final class Bake {
        final int slot;
        private final List<Rect> rects = new ArrayList<>();
        private final List<float[]> others = new ArrayList<>();
        /** Squared distance per sample, x fastest, then y, then z. */
        private final float[] d2 = new float[RES * RES * RES];
        private int layer;

        Bake(int slot, float[][] quads) {
            this.slot = slot;
            for (float[] q : quads) {
                Rect r = Rect.of(q);
                if (r != null) {
                    rects.add(r);
                } else {
                    others.add(q);
                }
            }
            Arrays.fill(d2, RANGE * RANGE);
        }

        boolean done() {
            return layer >= RES;
        }

        /** Distances for one z layer. */
        void layer() {
            int z = layer++;
            float pz = (z + 0.5F) / RES;
            for (Rect r : rects) {
                // Per axis, how far a sample lies outside the rectangle's extent (or from its plane)
                float[] dx = axisDistances(r, 0);
                float[] dy = axisDistances(r, 1);
                float dz = axisDistance(r, 2, pz);
                float dz2 = dz * dz;
                for (int y = 0; y < RES; y++) {
                    float dyz = dy[y] * dy[y] + dz2;
                    int base = (z * RES + y) * RES;
                    for (int x = 0; x < RES; x++) {
                        float d = dx[x] * dx[x] + dyz;
                        if (d < d2[base + x]) {
                            d2[base + x] = d;
                        }
                    }
                }
            }
            for (float[] q : others) {
                for (int y = 0; y < RES; y++) {
                    for (int x = 0; x < RES; x++) {
                        int i = (z * RES + y) * RES + x;
                        float d = quadDistance2(q, (x + 0.5F) / RES, (y + 0.5F) / RES, pz);
                        if (d < d2[i]) {
                            d2[i] = d;
                        }
                    }
                }
            }
        }

        byte[] bytes() {
            byte[] out = new byte[d2.length];
            for (int i = 0; i < d2.length; i++) {
                out[i] = (byte) Math.round(Math.min((float) Math.sqrt(d2[i]) / RANGE, 1.0F) * 255.0F);
            }
            return out;
        }

        private static float[] axisDistances(Rect r, int axis) {
            float[] out = new float[RES];
            for (int k = 0; k < RES; k++) {
                out[k] = axisDistance(r, axis, (k + 0.5F) / RES);
            }
            return out;
        }

        private static float axisDistance(Rect r, int axis, float p) {
            if (axis == r.axis) {
                return Math.abs(p - r.c);
            }
            boolean first = axis == (r.axis == 0 ? 1 : 0);
            float lo = first ? r.u0 : r.v0;
            float hi = first ? r.u1 : r.v1;
            return Math.max(Math.max(lo - p, p - hi), 0.0F);
        }
    }

    /** Squared distance from a point to a planar quad (two triangles 0 1 2 and 0 2 3). */
    static float quadDistance2(float[] q, float px, float py, float pz) {
        return Math.min(triangleDistance2(q, 0, 1, 2, px, py, pz), triangleDistance2(q, 0, 2, 3, px, py, pz));
    }

    /** Squared distance from a point to a triangle of the quad's corners (closest point by regions). */
    private static float triangleDistance2(float[] q, int ia, int ib, int ic, float px, float py, float pz) {
        float ax = q[ia * 3], ay = q[ia * 3 + 1], az = q[ia * 3 + 2];
        float bx = q[ib * 3], by = q[ib * 3 + 1], bz = q[ib * 3 + 2];
        float cx = q[ic * 3], cy = q[ic * 3 + 1], cz = q[ic * 3 + 2];
        float abx = bx - ax, aby = by - ay, abz = bz - az;
        float acx = cx - ax, acy = cy - ay, acz = cz - az;
        float apx = px - ax, apy = py - ay, apz = pz - az;
        float d1 = abx * apx + aby * apy + abz * apz;
        float d2 = acx * apx + acy * apy + acz * apz;
        float rx, ry, rz;
        if (d1 <= 0 && d2 <= 0) {
            rx = ax;
            ry = ay;
            rz = az;
        } else {
            float bpx = px - bx, bpy = py - by, bpz = pz - bz;
            float d3 = abx * bpx + aby * bpy + abz * bpz;
            float d4 = acx * bpx + acy * bpy + acz * bpz;
            float cpx = px - cx, cpy = py - cy, cpz = pz - cz;
            float d5 = abx * cpx + aby * cpy + abz * cpz;
            float d6 = acx * cpx + acy * cpy + acz * cpz;
            float vc = d1 * d4 - d3 * d2;
            float vb = d5 * d2 - d1 * d6;
            float va = d3 * d6 - d5 * d4;
            if (d3 >= 0 && d4 <= d3) {
                rx = bx;
                ry = by;
                rz = bz;
            } else if (d6 >= 0 && d5 <= d6) {
                rx = cx;
                ry = cy;
                rz = cz;
            } else if (vc <= 0 && d1 >= 0 && d3 <= 0) {
                float v = d1 / (d1 - d3);
                rx = ax + abx * v;
                ry = ay + aby * v;
                rz = az + abz * v;
            } else if (vb <= 0 && d2 >= 0 && d6 <= 0) {
                float w = d2 / (d2 - d6);
                rx = ax + acx * w;
                ry = ay + acy * w;
                rz = az + acz * w;
            } else if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
                float w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
                rx = bx + (cx - bx) * w;
                ry = by + (cy - by) * w;
                rz = bz + (cz - bz) * w;
            } else {
                float denom = 1.0F / (va + vb + vc);
                float v = vb * denom;
                float w = vc * denom;
                rx = ax + abx * v + acx * w;
                ry = ay + aby * v + acy * w;
                rz = az + abz * v + acz * w;
            }
        }
        float ex = px - rx, ey = py - ry, ez = pz - rz;
        return ex * ex + ey * ey + ez * ez;
    }
}
