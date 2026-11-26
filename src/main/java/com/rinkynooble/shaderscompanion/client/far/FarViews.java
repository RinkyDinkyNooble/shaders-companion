package com.rinkynooble.shaderscompanion.client.far;

import com.mojang.blaze3d.platform.GlStateManager;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.Config;
import com.rinkynooble.shaderscompanion.client.Gl;
import java.util.Arrays;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL46C;

/**
 * The far terrain in the mod's own views (research/lod/M4-PLAN.md, "M4"; API.md, "What is drawn in the cube" and "Mirror
 * view"; render thread only). Each cube face and mirror plane drawn this frame gets the far terrain the main view's ring
 * rule picks from the camera (the views draw what is resident; they choose no levels of their own), culled to the
 * view's frustum and, for a mirror, its cut plane, with the face directions the view's eye sees: {@link #select} runs
 * the far selection compute pass ({@code far_select.csh}) with the view's planes. {@code ViewRenderer} then draws the
 * opaque commands after the chunk terrain's opaque surfaces and the translucent ones after its water and glass
 * ({@link #draw}), where the stencil says the chunk terrain drew nothing, into the views' fields with their logarithmic
 * depth ({@code far_terrain.vsh} with VIEW, {@code far_view.fsh}). A mirror's cut plane is a clip distance; far water
 * within a voxel above a level mirror plane is cut with it (it is the mirror's own water, raised to its voxel's top).
 * <p>
 * Each view of a frame has its own slice of one command buffer (two regions, opaque and translucent) and its own
 * counters, so no selection writes commands a draw of an earlier view may still read. A region holds {@link #share}
 * commands a node record at first; when a view selected more (its counters, read back a few frames later, say so) the
 * regions grow to every range of every record. Every GL binding the far work touches is read in {@link #beginFrame}
 * and put back in {@link #endFrame} ({@link FarRenderer.Saved}, and the first clip distance).
 */
public final class FarViews {
    private static final int COMMAND_BYTES = 20;
    /** Each view's counters (opaque and translucent commands, the rest unused, nodes, quads) take this much of their buffer. */
    private static final int COUNTER_STRIDE = 256;
    /** Most views in a frame: six cube faces and four mirror planes. */
    private static final int MAX_VIEWS = 10;
    private static final int RING = 4;
    /** Per view: selection start and end, opaque draw start and end, translucent draw start and end. */
    private static final int STAMPS = 6;
    private static final int HISTORY = 240;
    /** A region's commands a record at first, and after a view selected more (every range of a record: NodeMesh.RANGES). */
    private static final int FIRST_SHARE = 3;

    private static int program;
    private static int[] uniforms;
    private static final String[] UNIFORMS = {"u_ViewProjection", "u_CameraBlock", "u_CameraFrac", "u_StateCount", "u_BiomeCount", "u_Translucent",
            "u_PlantShade", "u_PlantFade", "u_ClipPlane", "u_WaterState", "u_WaterCut", "u_ChunkRadius", "u_Origin", "u_LogDepth", "u_Near"};

    /** The shader pack reads the cube or the mirror view (CubeView, each frame): the far store keeps the far terrain for them. */
    private static boolean viewsRead;
    private static String problem;

    // Buffers
    private static int commands;
    private static int counts;
    private static int slices;
    /** Commands a region holds, a multiple of 64 (a slice's start then suits any storage buffer offset alignment up to 256 bytes). */
    private static int capacity;
    private static int share = FIRST_SHARE;
    private static boolean overflowLogged;
    private static final int[] readback = new int[RING];
    private static final long[] fences = new long[RING];
    private static final int[] readbackViews = new int[RING];
    private static final int[] queries = new int[RING * MAX_VIEWS * STAMPS];
    /** Per ring slot and view: the stamps issued (a bit each) and whether the view was a mirror plane. */
    private static final int[][] issued = new int[RING][MAX_VIEWS];
    private static final boolean[][] mirrorView = new boolean[RING][MAX_VIEWS];
    private static final boolean[] stampsPending = new boolean[RING];
    private static final int[] stampViews = new int[RING];
    private static int ring;

    // This frame
    private static boolean active;
    private static boolean drewThisFrame;
    private static FarRenderer.Saved saved;
    private static boolean clipBefore;
    private static boolean stampThisFrame;
    private static int used;
    private static FarResidency.View view;
    private static int bx;
    private static int by;
    private static int bz;
    private static float fx;
    private static float fy;
    private static float fz;
    private static final float[][] drawMatrices = new float[MAX_VIEWS][16];
    private static final float[][] cuts = new float[MAX_VIEWS][4];
    private static final boolean[] cutOn = new boolean[MAX_VIEWS];
    private static final float[] waterCuts = new float[MAX_VIEWS];
    private static final float[][] eyes = new float[MAX_VIEWS][3];

    // Statistics: per frame read back, nodes and quads of all views; per frame timed, the cube faces' and the mirror
    // planes' GPU time and count, and the selections' GPU time
    private static final long[][] counted = new long[3][HISTORY];
    private static int countedFrames;
    private static final long[][] timed = new long[5][HISTORY];
    private static int timedFrames;
    private static long lastNodes;
    private static long lastQuads;
    private static int lastViews;
    private static long lastCubeNanos = -1;
    private static int lastCubeViews;
    private static long lastMirrorNanos = -1;
    private static int lastMirrorViews;

    private FarViews() {
    }

    /** Compiles the program (from FarRenderer.available, with its programs). */
    static void compile() {
        program = FarRenderer.link("far view", FarRenderer.compile(GL20C.GL_VERTEX_SHADER, "far_terrain.vsh", "#define VIEW\n"),
                FarRenderer.compile(GL20C.GL_FRAGMENT_SHADER, "far_view.fsh", ""));
        uniforms = FarRenderer.uniforms(program, UNIFORMS);
    }

    /** CubeView, each frame: whether the shader pack reads the cube or the mirror view. */
    public static void viewsRead(boolean read) {
        viewsRead = read;
    }

    /** Whether the shader pack read the views last frame (the far store's switch for them). */
    static boolean viewsRead() {
        return viewsRead;
    }

    /** Whether the far terrain can be drawn into this frame's views (the far store has it on for them, on the GPU). */
    public static boolean wanted() {
        return problem == null && FarTerrain.viewsDraw() && FarRenderer.hasBuffers() && FarRenderer.problem() == null;
    }

    /** How far the far terrain reaches from the camera (blocks) while {@link #wanted}, 0 otherwise. */
    public static int range() {
        FarResidency.View v = FarTerrain.frameView();
        return wanted() && v != null ? v.farRange() : 0;
    }

    /**
     * Starts the views' far terrain for a frame of at most {@code maxViews} views, after the views' own GL state is
     * saved: reads finished counters and timestamps, makes room, saves the GL state the far work touches. False when
     * the far terrain isn't drawn into the views this frame; then {@link #select} returns -1.
     */
    public static boolean beginFrame(int maxViews) {
        active = false;
        drewThisFrame = false;
        used = 0;
        FarResidency.View v = FarTerrain.frameView();
        if (!wanted() || v == null || maxViews <= 0) {
            return false;
        }
        saved = FarRenderer.Saved.capture();
        clipBefore = GL11C.glIsEnabled(GL30C.GL_CLIP_DISTANCE0);
        try {
            readBack();
            ensureBuffers(Math.min(maxViews, MAX_VIEWS), FarRenderer.buffers().recordCapacity);
        } catch (RuntimeException e) {
            fail(e);
            restore();
            return false;
        }
        view = v;
        bx = Mth.floor(v.x());
        by = Mth.floor(v.y());
        bz = Mth.floor(v.z());
        fx = (float) (v.x() - bx);
        fy = (float) (v.y() - by);
        fz = (float) (v.z() - bz);
        stampThisFrame = !stampsPending[ring];
        active = true;
        return true;
    }

    /**
     * Selects the far terrain for one view; returns its slice for {@link #draw}, or -1 when none.
     *
     * @param cull       the matrix the view is culled by, for camera-relative positions (a mirror's: its drawn part)
     * @param drawMatrix the view's matrix for camera-relative positions
     * @param eyeX       the view's eye (its origin) relative to the camera
     * @param cut        a mirror's cut plane for camera-relative positions (drawn where dot(xyz, p) + w >= 0), or null
     * @param levelPlane the cut plane is level (water or a glass floor): far water up to a voxel above it is cut too
     * @param mirror     a mirror plane's view (for the statistics; a cube face otherwise)
     */
    public static int select(Matrix4f cull, Matrix4f drawMatrix, float eyeX, float eyeY, float eyeZ, float[] cut, boolean levelPlane, boolean mirror) {
        if (!active || used >= slices) {
            return -1;
        }
        int s = used;
        try {
            FarGpuBuffers b = FarRenderer.buffers();
            drawMatrix.get(drawMatrices[s]);
            cutOn[s] = cut != null;
            if (cut != null) {
                System.arraycopy(cut, 0, cuts[s], 0, 4);
            }
            waterCuts[s] = cut != null && levelPlane ? 1.0F : 0.0F;
            eyes[s][0] = eyeX;
            eyes[s][1] = eyeY;
            eyes[s][2] = eyeZ;
            issued[ring][s] = 0;
            mirrorView[ring][s] = mirror;
            stamp(s, 0);
            // The view's counters to zero, and its commands where the draw count isn't read from the GPU
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, counts);
            GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, (long) s * COUNTER_STRIDE, new int[8]);
            if (!FarRenderer.indirectCount) {
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, commands);
                GL43C.glClearBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, GL30C.GL_R32UI, sliceOffset(s), sliceBytes(), GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT,
                        (java.nio.ByteBuffer) null);
            }
            Gl.useProgram(FarRenderer.selectProgram);
            int[] u = FarRenderer.selectUniforms;
            GL20C.glUniform3i(u[0], bx, by, bz);
            GL20C.glUniform3f(u[1], fx, fy, fz);
            GL20C.glUniform1f(u[2], (float) view.k());
            GL20C.glUniform1f(u[3], view.farRange());
            GL20C.glUniform1i(u[4], FarRenderer.topLevel(view));
            GL20C.glUniform1f(u[5], FarRenderer.chunkRadius(view.renderDistance()));
            GL20C.glUniform4fv(u[6], FarRenderer.frustumPlanes(cull, cut));
            GL20C.glUniform1i(u[7], b.recordHigh);
            GL20C.glUniform1i(u[8], capacity);
            GL20C.glUniform1i(u[9], 0);
            GL20C.glUniform1f(u[10], 0.0F);
            GL20C.glUniform1i(u[11], 0);
            GL20C.glUniform3f(u[12], eyeX, eyeY, eyeZ);
            GL20C.glUniform1i(u[13], 1);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, b.records);
            GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, 2, commands, sliceOffset(s), sliceBytes());
            GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, 3, counts, (long) s * COUNTER_STRIDE, 32);
            if (b.recordHigh > 0) {
                GL43C.glDispatchCompute((b.recordHigh + 63) / 64, 1, 1);
            }
            GL42C.glMemoryBarrier(GL42C.GL_COMMAND_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            stamp(s, 1);
            used = s + 1;
            return s;
        } catch (RuntimeException e) {
            fail(e);
            return -1;
        }
    }

    /**
     * Draws a view's far terrain, its opaque or its translucent commands, into the bound view target with the view's
     * stencil and depth state as set (the views' program state, vertex array and storage bindings are the caller's to
     * set again).
     *
     * @param logDepth 1 / log2(far / near) of the views' depth
     * @param near     the views' near plane
     */
    public static void draw(int s, boolean translucent, float logDepth, float near) {
        if (!active || s < 0 || s >= used || problem != null) {
            return;
        }
        try {
            FarGpuBuffers b = FarRenderer.buffers();
            stamp(s, translucent ? 4 : 2);
            Gl.useProgram(program);
            int[] t = uniforms;
            double k = view.k();
            GL20C.glUniformMatrix4fv(t[0], false, drawMatrices[s]);
            GL20C.glUniform3i(t[1], bx, by, bz);
            GL20C.glUniform3f(t[2], fx, fy, fz);
            GL20C.glUniform1i(t[3], b.stateCount);
            GL20C.glUniform1i(t[4], b.biomeCount);
            GL20C.glUniform1i(t[5], translucent ? 1 : 0);
            GL20C.glUniform1f(t[6], Float.isNaN(FarRenderer.plantShadeForChecks) ? FarRenderer.PLANT_SHADE : FarRenderer.plantShadeForChecks);
            GL20C.glUniform2f(t[7], (float) (k * 1.25), (float) Math.max(k * 1.25 + 1.0, k * 2.0 - Node.SIZE));
            if (cutOn[s]) {
                GL20C.glUniform4f(t[8], cuts[s][0], cuts[s][1], cuts[s][2], cuts[s][3]);
            } else {
                GL20C.glUniform4f(t[8], 0.0F, 0.0F, 0.0F, 1.0F);
            }
            GL20C.glUniform1i(t[9], StateColors.waterState());
            GL20C.glUniform1f(t[10], waterCuts[s]);
            GL20C.glUniform1f(t[11], FarRenderer.chunkRadius(view.renderDistance()));
            GL20C.glUniform3f(t[12], eyes[s][0], eyes[s][1], eyes[s][2]);
            GL20C.glUniform1f(t[13], logDepth);
            GL20C.glUniform1f(t[14], near);
            if (cutOn[s]) {
                GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0);
            }
            FarRenderer.ensureVao();
            GlStateManager._glBindVertexArray(FarRenderer.vao);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, b.geometry);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, b.records);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, b.colours);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, b.info);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, b.biomes);
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, commands);
            if (FarRenderer.indirectCount) {
                GL15C.glBindBuffer(GL46C.GL_PARAMETER_BUFFER, counts);
            }
            long offset = sliceOffset(s) + (translucent ? (long) capacity * COMMAND_BYTES : 0L);
            long countOffset = (long) s * COUNTER_STRIDE + (translucent ? 4L : 0L);
            if (!FarRenderer.indirectCount) {
                GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, offset, capacity, COMMAND_BYTES);
            } else if (FarRenderer.arbIndirectCount) {
                ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, offset, countOffset, capacity, COMMAND_BYTES);
            } else {
                GL46C.glMultiDrawElementsIndirectCount(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, offset, countOffset, capacity, COMMAND_BYTES);
            }
            if (cutOn[s]) {
                GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0);
            }
            stamp(s, translucent ? 5 : 3);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** Ends the frame's views: the counters copied for reading back, the GL state put back. */
    public static void endFrame() {
        if (!active) {
            return;
        }
        try {
            if (used > 0 && fences[ring] == 0) {
                GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, counts);
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, readback[ring]);
                GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, (long) used * COUNTER_STRIDE);
                fences[ring] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                readbackViews[ring] = used;
            }
            if (stampThisFrame && used > 0) {
                stampsPending[ring] = true;
                stampViews[ring] = used;
            }
            ring = (ring + 1) % RING;
            drewThisFrame = used > 0;
        } catch (RuntimeException e) {
            fail(e);
        } finally {
            restore();
        }
    }

    private static void restore() {
        if (clipBefore) {
            GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0);
        } else {
            GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0);
        }
        saved.restore();
        saved = null;
        active = false;
    }

    /** Whether this frame's views drew far terrain (the info textures' far range). */
    public static boolean drewThisFrame() {
        return drewThisFrame;
    }

    private static void stamp(int s, int which) {
        if (stampThisFrame) {
            GL33C.glQueryCounter(queries[(ring * MAX_VIEWS + s) * STAMPS + which], GL33C.GL_TIMESTAMP);
            issued[ring][s] |= 1 << which;
        }
    }

    private static long sliceOffset(int s) {
        return (long) s * sliceBytes();
    }

    private static long sliceBytes() {
        return 2L * capacity * COMMAND_BYTES;
    }

    private static void fail(RuntimeException e) {
        if (problem == null) {
            problem = "stopped after an error: " + e;
            ShadersCompanion.LOGGER.error("Shaders Companion far terrain in the views: stopped after an error", e);
        }
    }

    /** Room for this many views at the regions' size for this many records; the counters, readbacks and queries once. */
    private static void ensureBuffers(int views, int records) {
        if (counts == 0) {
            counts = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, counts);
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, (long) MAX_VIEWS * COUNTER_STRIDE, GL15C.GL_DYNAMIC_DRAW);
            for (int i = 0; i < RING; i++) {
                readback[i] = GL15C.glGenBuffers();
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, readback[i]);
                GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, (long) MAX_VIEWS * COUNTER_STRIDE, GL15C.GL_STREAM_READ);
            }
            if (queries[0] == 0) {
                GL15C.glGenQueries(queries);
            }
        }
        int wanted = (Math.max(4096, records * share) + 63) / 64 * 64;
        int wantedSlices = Math.max(slices, views);
        if (commands != 0 && wanted == capacity && wantedSlices == slices) {
            return;
        }
        if (commands != 0) {
            GL15C.glDeleteBuffers(commands);
        }
        commands = GL15C.glGenBuffers();
        capacity = wanted;
        slices = wantedSlices;
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, commands);
        GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, slices * sliceBytes(), GL15C.GL_DYNAMIC_DRAW);
    }

    /** Reads finished counters (a view that selected more than its regions hold grows them) and timestamps; never waits. */
    private static void readBack() {
        for (int i = 0; i < RING; i++) {
            if (fences[i] != 0) {
                int status = GL32C.glClientWaitSync(fences[i], 0, 0L);
                if (status == GL32C.GL_ALREADY_SIGNALED || status == GL32C.GL_CONDITION_SATISFIED) {
                    int views = readbackViews[i];
                    int[] c = new int[views * COUNTER_STRIDE / 4];
                    GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, readback[i]);
                    GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER, 0, c);
                    GL32C.glDeleteSync(fences[i]);
                    fences[i] = 0;
                    long nodes = 0;
                    long quads = 0;
                    boolean overflow = false;
                    for (int v = 0; v < views; v++) {
                        int at = v * COUNTER_STRIDE / 4;
                        overflow |= Integer.toUnsignedLong(c[at]) > capacity || Integer.toUnsignedLong(c[at + 1]) > capacity;
                        nodes += Integer.toUnsignedLong(c[at + 4]);
                        quads += Integer.toUnsignedLong(c[at + 5]);
                    }
                    lastNodes = nodes;
                    lastQuads = quads;
                    lastViews = views;
                    int at = countedFrames % HISTORY;
                    counted[0][at] = views;
                    counted[1][at] = nodes;
                    counted[2][at] = quads;
                    countedFrames++;
                    if (overflow && share < NodeMesh.RANGES) {
                        // The regions grow at once (no view draws now): every range of every record
                        share = NodeMesh.RANGES;
                        if (!overflowLogged) {
                            overflowLogged = true;
                            ShadersCompanion.LOGGER.info("Shaders Companion far terrain in the views: a view selected more than {} commands; its regions now hold every range "
                                    + "of every record", capacity);
                        }
                    }
                }
            }
            if (stampsPending[i]) {
                boolean ready = true;
                for (int v = 0; v < stampViews[i] && ready; v++) {
                    int last = 31 - Integer.numberOfLeadingZeros(issued[i][v]);
                    ready = last < 0 || GL15C.glGetQueryObjecti(queries[(i * MAX_VIEWS + v) * STAMPS + last], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0;
                }
                if (ready) {
                    long cube = 0;
                    long mirror = 0;
                    long selection = 0;
                    int cubes = 0;
                    int mirrors = 0;
                    for (int v = 0; v < stampViews[i]; v++) {
                        long select = span(i, v, 0, 1);
                        long nanos = select + span(i, v, 2, 3) + span(i, v, 4, 5);
                        selection += select;
                        if (mirrorView[i][v]) {
                            mirror += nanos;
                            mirrors++;
                        } else {
                            cube += nanos;
                            cubes++;
                        }
                    }
                    stampsPending[i] = false;
                    lastCubeNanos = cube;
                    lastCubeViews = cubes;
                    lastMirrorNanos = mirror;
                    lastMirrorViews = mirrors;
                    int at = timedFrames % HISTORY;
                    timed[0][at] = cube;
                    timed[1][at] = cubes;
                    timed[2][at] = mirror;
                    timed[3][at] = mirrors;
                    timed[4][at] = selection;
                    timedFrames++;
                }
            }
        }
    }

    /** The GPU nanoseconds between two stamps of a view, 0 unless both were issued. */
    private static long span(int slot, int v, int from, int to) {
        int mask = issued[slot][v];
        if ((mask >> from & 1) == 0 || (mask >> to & 1) == 0) {
            return 0L;
        }
        int base = (slot * MAX_VIEWS + v) * STAMPS;
        return GL33C.glGetQueryObjecti64(queries[base + to], GL15C.GL_QUERY_RESULT) - GL33C.glGetQueryObjecti64(queries[base + from], GL15C.GL_QUERY_RESULT);
    }

    /** Frees the buffers (the far terrain's GPU path went off). */
    static void release() {
        if (commands != 0) {
            GL15C.glDeleteBuffers(commands);
            commands = 0;
        }
        if (counts != 0) {
            GL15C.glDeleteBuffers(counts);
            counts = 0;
        }
        for (int i = 0; i < RING; i++) {
            if (fences[i] != 0) {
                GL32C.glDeleteSync(fences[i]);
                fences[i] = 0;
            }
            if (readback[i] != 0) {
                GL15C.glDeleteBuffers(readback[i]);
                readback[i] = 0;
            }
            stampsPending[i] = false;
        }
        slices = 0;
        capacity = 0;
        share = FIRST_SHARE;
        drewThisFrame = false;
        lastViews = 0;
    }

    /** Video memory of the command, counter and readback buffers, in bytes. */
    static long bytes() {
        return (commands != 0 ? slices * sliceBytes() : 0L) + (counts != 0 ? (long) MAX_VIEWS * COUNTER_STRIDE * (RING + 1) : 0L);
    }

    /** The status line's part for the views (FarStore's "Far terrain:" line). */
    static String status() {
        if (problem != null) {
            return "can't draw: " + problem;
        }
        if (!Config.FAR_TERRAIN_IN_VIEWS.get()) {
            return "off (farTerrainInViews)";
        }
        if (!viewsRead) {
            return "the shader pack reads neither the cube nor the mirror view";
        }
        return String.format("%d views a frame, %d nodes and %d quads drawn; GPU %s a cube face (%d drawn), %s a mirror plane (%d drawn); commands %d a region, "
                        + "%d slices, %.1f MB", lastViews, lastNodes, lastQuads, perView(lastCubeNanos, lastCubeViews), lastCubeViews, perView(lastMirrorNanos, lastMirrorViews),
                lastMirrorViews, capacity, slices, bytes() / 1048576.0);
    }

    private static String perView(long nanos, int views) {
        return nanos < 0 || views == 0 ? "n/a" : String.format("%.2f ms", nanos / 1.0e6 / views);
    }

    /**
     * Dev checks, over the frames recorded since the last call (then started again): the medians of the cube faces' GPU
     * nanos a frame, the cube faces a frame, the mirror planes' GPU nanos a frame, the mirror planes a frame, the
     * selections' GPU nanos a frame; the frames timed; the medians of the views, nodes and quads a frame; the frames
     * counted; the command buffer's commands a region.
     */
    public static long[] numbersForChecks() {
        long[] out = new long[12];
        int n = Math.min(timedFrames, HISTORY);
        for (int k = 0; k < 5; k++) {
            out[k] = median(timed[k], n);
        }
        out[5] = n;
        int m = Math.min(countedFrames, HISTORY);
        for (int k = 0; k < 3; k++) {
            out[6 + k] = median(counted[k], m);
        }
        out[9] = m;
        out[10] = capacity;
        out[11] = problem == null ? 0 : 1;
        timedFrames = 0;
        countedFrames = 0;
        return out;
    }

    private static long median(long[] values, int n) {
        if (n == 0) {
            return -1;
        }
        long[] sorted = Arrays.copyOf(values, n);
        Arrays.sort(sorted);
        return sorted[n / 2];
    }
}
