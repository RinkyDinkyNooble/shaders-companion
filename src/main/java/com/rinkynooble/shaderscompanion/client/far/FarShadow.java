package com.rinkynooble.shaderscompanion.client.far;

import com.mojang.blaze3d.platform.GlStateManager;
import com.rinkynooble.shaderscompanion.client.Config;
import com.rinkynooble.shaderscompanion.client.FarTextures;
import com.rinkynooble.shaderscompanion.client.Gl;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.world.phys.Vec3;
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
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GL46C;

/**
 * The far shadow map (research/lod/M4-PLAN.md, "Round B"; API.md, "Far terrain", "The far shadow map"; render thread
 * only): the far terrain drawn depth only from the light into {@code far_shadow}, while the shader pack binds it and the
 * far terrain is drawn for the pack. The light is the shader pack's own: the rotation of Oculus's shadow model view (the
 * sun by day, the moon by night, with the pack's sun path rotation), so far shadows fall where the pack's shadow map
 * puts the near ones.
 * <p>
 * Two square cascades side by side in one texture, each an orthographic view along the light centred on the camera,
 * the centre snapped to the cascade's texel grid in the light's plane (a cascade redrawn after the camera moved shows
 * the same texels, so shadows don't swim):
 * <ul>
 * <li>cascade 0 holds {@link #NEAR_REACH} blocks around the camera (0.56 blocks a texel at 2,048), where the far
 * terrain's voxels are 1 and 2 blocks;</li>
 * <li>cascade 1 the whole far range (4.2 blocks a texel at 4,052 blocks).</li>
 * </ul>
 * Each holds the opaque faces that look away from the light (back faces) of every node the main view's ring rule
 * draws, with no chunk terrain cut: the residency keeps the nodes inside the chunk terrain while the map is wanted, so
 * the hills around the camera shade far terrain too. With back faces a receiver on a lit face is never behind a face of
 * its own block, so there is no acne, and a receiver inside a coarser voxel (a slab, snow, the chunk terrain's real
 * blocks under the far terrain's) is still lit.
 * <p>
 * A cascade is redrawn, one a frame at most, when it has nothing yet, when the camera has moved an eighth of its reach,
 * when the light has turned far enough that a shadow of a {@link #CASTER_HEIGHT}-block caster would move half a texel
 * in it, or when meshes were uploaded and some frames have passed ({@link #UPLOAD_FRAMES}); otherwise it is kept, and
 * the info texture's matrices map this frame's player space into it.
 */
final class FarShadow {
    static final int CASCADES = 2;
    /** Cascade 0's reach around the camera, in blocks (in the light's plane). */
    private static final double NEAR_REACH = 512.0;
    /** A cascade is redrawn once the camera has moved this share of its reach (its extent has that much more). */
    private static final double MOVE_SHARE = 1.0 / 8.0;
    /** The light's turn between redraws: a shadow of a caster this high above its receiver moves at most half a texel. */
    private static final double CASTER_HEIGHT = 64.0;
    /** The light turning more than this (radians) invalidates both cascades at once (the sun and the moon change places). */
    private static final double FLIP = Math.toRadians(10.0);
    /** Frames after which a cascade is redrawn for newly uploaded meshes. */
    private static final int[] UPLOAD_FRAMES = {30, 120};
    private static final int RING = 4;
    /** Per slot: selection start, draw start, draw end. */
    private static final int STAMPS = 3;
    private static final int HISTORY = 240;

    private static int program;
    private static int[] uniforms;
    private static final String[] UNIFORMS = {"u_LightProjection", "u_CameraBlock", "u_CameraFrac"};
    private static int framebuffer;
    /** The framebuffer's depth attachment is the texture at this size (it keeps its GL name; a resize re-specifies it). */
    private static int framebufferSize;
    private static int commands;
    private static int commandRecords;
    private static int counts;
    private static final int[] readback = new int[RING];
    private static final long[] fences = new long[RING];
    private static final int[] queries = new int[RING * STAMPS];
    private static final boolean[] pending = new boolean[RING];
    private static final int[] slotCascade = new int[RING];
    private static int slot;

    // Each cascade as last drawn
    private static final boolean[] valid = new boolean[CASCADES];
    /** The light's rotation rows (world to light space: x, y in the map's plane, z toward the light). */
    private static final double[][] rows = new double[CASCADES][9];
    private static final double[][] cameraAt = new double[CASCADES][3];
    /** The camera's light-space x and y less the cascade's snapped centre, when drawn. */
    private static final double[] offsetX = new double[CASCADES];
    private static final double[] offsetY = new double[CASCADES];
    private static final double[] extent = new double[CASCADES];
    private static final double[] depthHalf = new double[CASCADES];
    private static final long[] drawnFrame = new long[CASCADES];
    private static final long[] uploadsAt = new long[CASCADES];
    private static int mapSize;
    private static int mapRange;
    private static int buffersGeneration = -1;
    private static long frame;

    // Statistics
    static final long[] redraws = new long[CASCADES];
    static final long[] lastNanos = {-1, -1};
    static final long[] lastQuads = new long[CASCADES];
    private static final long[][] history = new long[CASCADES][HISTORY];
    private static final int[] historyCount = new int[CASCADES];
    private static long framesSince;
    private static long redrawFramesSince;
    private static String why = "";

    private FarShadow() {
    }

    /** Compiles the program (from FarRenderer.available, with its programs). */
    static void compile() {
        program = FarRenderer.link("far shadow", FarRenderer.compile(GL20C.GL_VERTEX_SHADER, "far_shadow.vsh"),
                FarRenderer.compile(GL20C.GL_FRAGMENT_SHADER, "far_shadow.fsh"));
        uniforms = FarRenderer.uniforms(program, UNIFORMS);
    }

    /**
     * The frame's part, after the far terrain's own draw (in its saved GL state): nothing unless the pack binds the
     * shadow map; then redraws a cascade if one is due and returns the info texture's texels 4 to 11 for this frame, or
     * null while a cascade has nothing yet (or the light is unknown: no shadow pass has run).
     */
    static float[] frame(Vec3 camera, FarResidency.View v, FarGpuBuffers b, int top) {
        frame++;
        if (!FarTextures.shadowWanted()) {
            release();
            return null;
        }
        Matrix4f modelView = ShadowRenderer.MODELVIEW;
        if (modelView == null) {
            why = "no shadow pass has run yet";
            return null;
        }
        double[] light = {modelView.m00(), modelView.m10(), modelView.m20(), modelView.m01(), modelView.m11(), modelView.m21(), modelView.m02(), modelView.m12(),
                modelView.m22()};
        for (int r = 0; r < 3; r++) {
            double length = Math.sqrt(light[r * 3] * light[r * 3] + light[r * 3 + 1] * light[r * 3 + 1] + light[r * 3 + 2] * light[r * 3 + 2]);
            if (length < 1e-6) {
                why = "the shadow pass's light is degenerate";
                return null;
            }
            for (int i = 0; i < 3; i++) {
                light[r * 3 + i] /= length;
            }
        }
        int size = Config.FAR_SHADOW_SIZE.get();
        int range = v.farRange();
        if (size != mapSize || range != mapRange || b.generation != buffersGeneration) {
            mapSize = size;
            mapRange = range;
            buffersGeneration = b.generation;
            valid[0] = valid[1] = false;
        }
        ensureTarget(size);
        ensureCommands(b);
        framesSince++;
        // The light's elevation sets how far a shadow moves on the ground as it turns
        double sinElevation = Math.max(Math.abs(light[7]), 0.05);
        int due = -1;
        long dueAge = -1;
        for (int c = 0; c < CASCADES; c++) {
            double reach = reach(c, range);
            double texel = 2.0 * reach * (1.0 + MOVE_SHARE) / size;
            boolean redraw;
            String reason;
            if (!valid[c]) {
                redraw = true;
                reason = "new";
            } else {
                double[] r = rows[c];
                double dot = r[6] * light[6] + r[7] * light[7] + r[8] * light[8];
                double turn = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
                if (turn > FLIP) {
                    // The sun and the moon changed places: both anew, nothing shown until they are
                    valid[0] = valid[1] = false;
                    c = -1;
                    continue;
                }
                double dx = camera.x - cameraAt[c][0];
                double dy = camera.y - cameraAt[c][1];
                double dz = camera.z - cameraAt[c][2];
                double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
                boolean uploaded = b.uploads != uploadsAt[c] && frame - drawnFrame[c] >= UPLOAD_FRAMES[c];
                redraw = moved > reach * MOVE_SHARE || turn > 0.5 * texel * sinElevation / CASTER_HEIGHT || uploaded;
                reason = moved > reach * MOVE_SHARE ? "moved" : uploaded && turn <= 0.5 * texel * sinElevation / CASTER_HEIGHT ? "meshes" : "light";
            }
            // Of the cascades due, the one drawn longest ago (a new one first)
            long age = valid[c] ? frame - drawnFrame[c] : Long.MAX_VALUE;
            if (redraw && age > dueAge) {
                due = c;
                dueAge = age;
                why = reason;
            }
        }
        if (due >= 0) {
            draw(due, camera, light, v, b, top, size, range);
            redrawFramesSince++;
        }
        if (!valid[0] || !valid[1]) {
            return null;
        }
        return info(camera, size);
    }

    /** How far around the camera a cascade holds, in blocks (in the light's plane). */
    private static double reach(int cascade, int range) {
        return cascade == 0 ? Math.min(NEAR_REACH, range) : range;
    }

    /**
     * The info texture's texels 4 to 11 (API.md): (1, map size N, depth per block of cascade 0, of cascade 1); per
     * cascade three rows mapping this frame's player space (positions relative to the camera) to the cascade's
     * coordinates (u, v from 0 to 1 within it) and its depth (0 at the light's end); (texel size in blocks of cascade 0,
     * of cascade 1, 0, 0).
     */
    private static float[] info(Vec3 camera, int size) {
        float[] out = new float[FarTextures.SHADOW_INFO_FLOATS];
        out[0] = 1.0F;
        out[1] = size;
        out[2] = (float) (0.5 / depthHalf[0]);
        out[3] = (float) (0.5 / depthHalf[1]);
        for (int c = 0; c < CASCADES; c++) {
            double[] r = rows[c];
            double dx = camera.x - cameraAt[c][0];
            double dy = camera.y - cameraAt[c][1];
            double dz = camera.z - cameraAt[c][2];
            double e = extent[c];
            double h = depthHalf[c];
            int at = 4 + c * 12;
            double[] scale = {0.5 / e, 0.5 / e, -0.5 / h};
            double[] constant = {0.5 + 0.5 * (r[0] * dx + r[1] * dy + r[2] * dz + offsetX[c]) / e, 0.5 + 0.5 * (r[3] * dx + r[4] * dy + r[5] * dz + offsetY[c]) / e,
                    0.5 - 0.5 * (r[6] * dx + r[7] * dy + r[8] * dz) / h};
            for (int row = 0; row < 3; row++) {
                out[at + row * 4] = (float) (r[row * 3] * scale[row]);
                out[at + row * 4 + 1] = (float) (r[row * 3 + 1] * scale[row]);
                out[at + row * 4 + 2] = (float) (r[row * 3 + 2] * scale[row]);
                out[at + row * 4 + 3] = (float) constant[row];
            }
            out[28 + c] = (float) (2.0 * e / size);
        }
        return out;
    }

    /** Draws one cascade: the selection with the cascade's box and the light's back faces, then the depth-only draw. */
    private static void draw(int c, Vec3 camera, double[] light, FarResidency.View v, FarGpuBuffers b, int top, int size, int range) {
        double reach = reach(c, range);
        double e = reach * (1.0 + MOVE_SHARE);
        double texel = 2.0 * e / size;
        // The camera's light-space position, the centre snapped to the texel grid
        double lx = light[0] * camera.x + light[1] * camera.y + light[2] * camera.z;
        double ly = light[3] * camera.x + light[4] * camera.y + light[5] * camera.z;
        double ox = lx - Math.rint(lx / texel) * texel;
        double oy = ly - Math.rint(ly / texel) * texel;
        // Every caster within the far range of the camera, along the light both ways
        double h = range * 1.05 + 384.0;
        System.arraycopy(light, 0, rows[c], 0, 9);
        cameraAt[c][0] = camera.x;
        cameraAt[c][1] = camera.y;
        cameraAt[c][2] = camera.z;
        offsetX[c] = ox;
        offsetY[c] = oy;
        extent[c] = e;
        depthHalf[c] = h;
        drawnFrame[c] = frame;
        uploadsAt[c] = b.uploads;
        int bx = net.minecraft.util.Mth.floor(camera.x);
        int by = net.minecraft.util.Mth.floor(camera.y);
        int bz = net.minecraft.util.Mth.floor(camera.z);
        float fx = (float) (camera.x - bx);
        float fy = (float) (camera.y - by);
        float fz = (float) (camera.z - bz);

        int s = slot;
        boolean stamp = !pending[s];
        if (stamp) {
            GL33C.glQueryCounter(queries[s * STAMPS], GL33C.GL_TIMESTAMP);
        }
        // Selection: the cascade's box (camera-relative planes, inside where dot(xyz, p) + w >= 0) and the faces that
        // look away from the light
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, counts);
        GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, 0, new int[8]);
        int max = commandRecords * NodeMesh.RANGES;
        if (!FarRenderer.indirectCount) {
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, commands);
            GL43C.glClearBufferData(GL31C.GL_COPY_WRITE_BUFFER, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        }
        Gl.useProgram(FarRenderer.selectProgram);
        int[] u = FarRenderer.selectUniforms;
        GL20C.glUniform3i(u[0], bx, by, bz);
        GL20C.glUniform3f(u[1], fx, fy, fz);
        GL20C.glUniform1f(u[2], (float) v.k());
        GL20C.glUniform1f(u[3], range);
        GL20C.glUniform1i(u[4], top);
        GL20C.glUniform1f(u[5], 0.0F);
        float[] planes = {
                (float) light[0], (float) light[1], (float) light[2], (float) (ox + e),
                (float) -light[0], (float) -light[1], (float) -light[2], (float) (e - ox),
                (float) light[3], (float) light[4], (float) light[5], (float) (oy + e),
                (float) -light[3], (float) -light[4], (float) -light[5], (float) (e - oy),
                0.0F, 0.0F, 0.0F, 1.0F};
        GL20C.glUniform4fv(u[6], planes);
        GL20C.glUniform1i(u[7], b.recordHigh);
        GL20C.glUniform1i(u[8], max);
        GL20C.glUniform1i(u[9], 0);
        GL20C.glUniform1f(u[10], 0.0F);
        GL20C.glUniform1i(u[11], backFaces(light));
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, b.records);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, commands);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, counts);
        if (b.recordHigh > 0) {
            GL43C.glDispatchCompute((b.recordHigh + 63) / 64, 1, 1);
        }
        GL42C.glMemoryBarrier(GL42C.GL_COMMAND_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        if (stamp) {
            GL33C.glQueryCounter(queries[s * STAMPS + 1], GL33C.GL_TIMESTAMP);
        }

        // The depth-only draw into the cascade's square
        Gl.bindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GlStateManager._viewport(c * size, 0, size, size);
        GlStateManager._enableScissorTest();
        GlStateManager._scissorBox(c * size, 0, size, size);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GlStateManager._disableBlend();
        GlStateManager._disableCull();
        GlStateManager._depthMask(true);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_LESS);
        if (FarRenderer.clipControl) {
            FarRenderer.clipControl(GL20C.GL_LOWER_LEFT, GL45C.GL_NEGATIVE_ONE_TO_ONE);
        }
        GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[]{1.0F});
        Gl.useProgram(program);
        // Camera-relative positions to the cascade's clip space: x, y over the extent from the snapped centre, z along the
        // light over the depth range (toward the light is nearer)
        float[] matrix = {
                (float) (light[0] / e), (float) (light[3] / e), (float) (-light[6] / h), 0.0F,
                (float) (light[1] / e), (float) (light[4] / e), (float) (-light[7] / h), 0.0F,
                (float) (light[2] / e), (float) (light[5] / e), (float) (-light[8] / h), 0.0F,
                (float) (ox / e), (float) (oy / e), 0.0F, 1.0F};
        GL20C.glUniformMatrix4fv(uniforms[0], false, matrix);
        GL20C.glUniform3i(uniforms[1], bx, by, bz);
        GL20C.glUniform3f(uniforms[2], fx, fy, fz);
        FarRenderer.ensureVao();
        GlStateManager._glBindVertexArray(FarRenderer.vao);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, b.geometry);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, commands);
        if (FarRenderer.indirectCount) {
            GL15C.glBindBuffer(GL46C.GL_PARAMETER_BUFFER, counts);
        }
        if (!FarRenderer.indirectCount) {
            GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, 0L, max, 20);
        } else if (FarRenderer.arbIndirectCount) {
            ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, 0L, 0L, max, 20);
        } else {
            GL46C.glMultiDrawElementsIndirectCount(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, 0L, 0L, max, 20);
        }
        if (stamp) {
            GL33C.glQueryCounter(queries[s * STAMPS + 2], GL33C.GL_TIMESTAMP);
            pending[s] = true;
            slotCascade[s] = c;
        }
        if (fences[s] == 0) {
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, counts);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, readback[s]);
            GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, 32);
            fences[s] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
        slot = (s + 1) % RING;
        valid[c] = true;
        redraws[c]++;
    }

    /**
     * The face directions (bit per Minecraft Direction: down, up, north, south, west, east) that look away from the light
     * (rows[6..8], toward the light); faces edge-on to it are left out (they cover no texel).
     */
    private static int backFaces(double[] light) {
        double[] facing = {-light[7], light[7], -light[8], light[8], -light[6], light[6]};
        int mask = 0;
        for (int d = 0; d < 6; d++) {
            if (facing[d] < -1e-4) {
                mask |= 1 << d;
            }
        }
        return mask;
    }

    /** The texture at 2N x N and its depth-only framebuffer. */
    private static void ensureTarget(int size) {
        FarTextures.ensureShadowSize(size * 2, size);
        if (framebuffer != 0 && framebufferSize == size) {
            return;
        }
        if (framebuffer == 0) {
            framebuffer = GL30C.glGenFramebuffers();
        }
        Gl.bindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, FarTextures.shadow(), 0);
        GL11C.glDrawBuffer(GL11C.GL_NONE);
        GL11C.glReadBuffer(GL11C.GL_NONE);
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("far shadow map framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
        framebufferSize = size;
        valid[0] = valid[1] = false;
    }

    /** The shadow pass's own command buffer (every record's 12 ranges, one region), counters and readback copies. */
    private static void ensureCommands(FarGpuBuffers b) {
        if (counts == 0) {
            counts = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, counts);
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, 32, GL15C.GL_DYNAMIC_DRAW);
            for (int i = 0; i < RING; i++) {
                readback[i] = GL15C.glGenBuffers();
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, readback[i]);
                GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, 32, GL15C.GL_STREAM_READ);
            }
            if (queries[0] == 0) {
                GL15C.glGenQueries(queries);
            }
        }
        if (commands == 0 || commandRecords != b.recordCapacity) {
            if (commands != 0) {
                GL15C.glDeleteBuffers(commands);
            }
            commands = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, commands);
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, (long) b.recordCapacity * NodeMesh.RANGES * 20, GL15C.GL_DYNAMIC_DRAW);
            commandRecords = b.recordCapacity;
        }
    }

    /** Reads finished timestamps and counters (never waits; from FarRenderer's read back, every frame drawn). */
    static void readBack() {
        for (int i = 0; i < RING; i++) {
            if (fences[i] != 0) {
                int status = GL32C.glClientWaitSync(fences[i], 0, 0L);
                if (status == GL32C.GL_ALREADY_SIGNALED || status == GL32C.GL_CONDITION_SATISFIED) {
                    int[] c = new int[8];
                    GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, readback[i]);
                    GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER, 0, c);
                    GL32C.glDeleteSync(fences[i]);
                    fences[i] = 0;
                    lastQuads[slotCascade[i]] = Integer.toUnsignedLong(c[5]);
                }
            }
            if (pending[i] && GL15C.glGetQueryObjecti(queries[i * STAMPS + 2], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long t0 = GL33C.glGetQueryObjecti64(queries[i * STAMPS], GL15C.GL_QUERY_RESULT);
                long t2 = GL33C.glGetQueryObjecti64(queries[i * STAMPS + 2], GL15C.GL_QUERY_RESULT);
                int c = slotCascade[i];
                lastNanos[c] = t2 - t0;
                history[c][historyCount[c] % HISTORY] = t2 - t0;
                historyCount[c]++;
                pending[i] = false;
            }
        }
    }

    /** Frees the map, its framebuffer and buffers (the pack stopped binding it, or far terrain went off). */
    static void release() {
        if (framebuffer == 0 && counts == 0) {
            return;
        }
        if (framebuffer != 0) {
            GL30C.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
            framebufferSize = 0;
        }
        if (commands != 0) {
            GL15C.glDeleteBuffers(commands);
            commands = 0;
            commandRecords = 0;
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
            pending[i] = false;
        }
        FarTextures.ensureShadowSize(1, 1);
        valid[0] = valid[1] = false;
        why = "not wanted";
    }

    /** Video memory of the map and the shadow pass's buffers, in bytes. */
    static long bytes() {
        return FarTextures.shadowBytes() + (commands != 0 ? (long) commandRecords * NodeMesh.RANGES * 20 + 32 + RING * 32 : 0);
    }

    /** The status line's part, or "" while the pack doesn't bind the map. */
    static String status() {
        if (framebuffer == 0) {
            return "";
        }
        return String.format("; far shadow map %d x %d (%s), cascade 0 redrawn %d times (%s, %d quads), cascade 1 %d times (%s, %d quads), last for: %s",
                framebufferSize * 2, framebufferSize, valid[0] && valid[1] ? "in use" : "not ready", redraws[0], ms(lastNanos[0]), lastQuads[0], redraws[1],
                ms(lastNanos[1]), lastQuads[1], why);
    }

    private static String ms(long nanos) {
        return nanos < 0 ? "n/a" : String.format("%.2f ms", nanos / 1.0e6);
    }

    /**
     * Dev checks: since the last call, per cascade the redraws, the median and the max of their GPU time (selection and
     * draw) and the last redraw's quads; then the frames, the frames with a redraw, and whether both cascades are in
     * use now (1 or 0).
     */
    static long[] numbersForChecks() {
        long[] out = new long[CASCADES * 4 + 3];
        for (int c = 0; c < CASCADES; c++) {
            int n = Math.min(historyCount[c], HISTORY);
            long[] values = java.util.Arrays.copyOf(history[c], n);
            java.util.Arrays.sort(values);
            out[c * 4] = historyCount[c];
            out[c * 4 + 1] = n == 0 ? -1 : values[n / 2];
            out[c * 4 + 2] = n == 0 ? -1 : values[n - 1];
            out[c * 4 + 3] = lastQuads[c];
            historyCount[c] = 0;
        }
        out[CASCADES * 4] = framesSince;
        out[CASCADES * 4 + 1] = redrawFramesSince;
        out[CASCADES * 4 + 2] = valid[0] && valid[1] ? 1 : 0;
        framesSince = 0;
        redrawFramesSince = 0;
        return out;
    }
}
