package com.rinkynooble.shaderscompanion.client.far;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.Config;
import com.rinkynooble.shaderscompanion.client.FarTextures;
import com.rinkynooble.shaderscompanion.client.Gl;
import com.rinkynooble.shaderscompanion.client.GlState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.ARBClipControl;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
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
import org.lwjgl.opengl.GLCapabilities;

/**
 * Draws the far terrain (research/lod/M2-PLAN.md, "GPU"; M3-PLAN.md, "The mod side"; render thread only) while the far
 * store has it on ({@link FarStore#drawMode}): for the shader pack, which binds its textures ({@link FarTextures}, far
 * protocol 1, API.md "Far terrain"), or for the debug view when the pack doesn't.
 * <ol>
 * <li>in the frame hook before the world ({@link #render}): the selection compute pass ({@code far_select.csh}) writes
 * the frame's draw commands from the node records in {@link FarGpuBuffers}, then one
 * {@code glMultiDrawElementsIndirect(Count)} per pass and kind (opaque, then water and glass; each for the nodes clear
 * of the chunk terrain and those reaching into it or into the far edge's fade) draws them with vertex pulling
 * ({@code far_terrain.vsh}; 4 vertices a quad through a shared index pattern, so the vertex cache shades each corner
 * once) into two layers that share one 32-bit float depth buffer: the opaque layer ({@code far_albedo},
 * {@code far_data}, {@code far_depth}'s r), then the nearest translucent surface in front of it
 * ({@code far_translucent_albedo}, {@code far_translucent_data}, {@code far_depth}'s g), with the main camera and this
 * frame's projection, jittered as the pack jitters its gbuffers when it binds {@code far_info}. The hardware depth is
 * reverse Z through {@code glClipControl} (GL 4.5) or else logarithmic, for the depth test only; the pack's depth is a
 * colour output. Then the info texture ({@code far_info}) says what was drawn;</li>
 * <li>after the world ({@link #overlay}), only while the pack doesn't read the layers: the debug view, the far layers lit
 * simply over the finished frame where the screen shows sky ({@code far_overlay.fsh}); or alone, for screenshots.</li>
 * </ol>
 * Every GL binding and setting touched is read first and put back ({@link Saved}, {@link GlState}). GPU time of the
 * selection, the draw and the overlay comes from timestamps read a few frames later; the counts of drawn nodes and
 * quads from a copy of the selection's counters read back once its fence has passed.
 */
public final class FarRenderer {
    /** The far layer's near plane, in blocks (reverse Z keeps its precision even from here to 30,000 blocks). */
    private static final float NEAR = 0.5F;
    private static final int REGIONS = 4;
    /** A DrawElementsIndirectCommand: count, instance count, first index, base vertex, base instance. */
    private static final int COMMAND_BYTES = 20;
    /** The most quads one command draws (the shared index pattern's length); a node's range never holds more than 32,768. */
    private static final int PATTERN_QUADS = 65536;
    private static final int RING = 4;
    /** Per frame slot: selection start, draw start, draw end, overlay start, overlay end. */
    private static final int STAMPS = 5;
    private static final int HISTORY = 240;
    /** The far edge's fade starts at this share of the far range. */
    private static final float FADE_FROM = 0.9F;

    /** The debug view shows the far layer alone (not saved; {@code far show alone}). */
    static boolean alone;
    /** Dev checks: drawing paused, buffers kept (for timing the frame without the far terrain). */
    private static boolean paused;
    /**
     * Dev checks, for where the draw's time goes: 1 draws with the rasterizer off (vertices and the commands only), 2
     * draws one quad per command (the commands' own cost).
     */
    private static int probe;

    private static boolean checked;
    private static boolean available;
    private static String problem;
    static boolean clipControl;
    static boolean indirectCount;
    static boolean arbIndirectCount;
    private static boolean arbClipControl;

    static int selectProgram;
    private static final int[] terrainPrograms = new int[2];
    private static int overlayProgram;
    static int[] selectUniforms;
    private static final int[][] terrainUniforms = new int[2][];
    private static int[] overlayUniforms;
    static final String[] SELECT_UNIFORMS = {"u_CameraBlock", "u_CameraFrac", "u_K", "u_Far", "u_TopLevel", "u_ChunkRadius", "u_Planes", "u_RecordCount",
            "u_Region", "u_OneQuad", "u_FadeStart", "u_ShadowDirs", "u_Eye", "u_ViewRegions"};
    static final String[] TERRAIN_UNIFORMS = {"u_ViewProjection", "u_CameraBlock", "u_CameraFrac", "u_StateCount", "u_BiomeCount", "u_Translucent",
            "u_ChunkRadius", "u_LogDepth", "u_Near", "u_Jitter", "u_ViewZ", "u_DepthMapping", "u_FarNear", "u_FadeStart", "u_FarRange", "u_FrameIndex", "u_PlantShade",
            "u_PlantFade"};
    /**
     * A planted face's colour factor (on its encoded colour): a level-0 up face with a plant on it, which the far terrain
     * leaves out (FarMesher.PLANTED). Measured at the seam (research/lod/M3.md, "Round B"): the chunk terrain shows a
     * block with a plant on it at 0.58 of a bare grass top's luminance (linear), 0.594 against the far terrain's 0.98,
     * and 0.594 ^ (1 / 2.2) = 0.79.
     */
    static final float PLANT_SHADE = 0.79F;
    /** Dev checks: another factor for the planted faces (-PdevFarPlantShade), or NaN. */
    static float plantShadeForChecks = Float.NaN;
    private static final String[] OVERLAY_UNIFORMS = {"u_InverseProjection", "u_ScreenSize", "u_Alone", "u_UseScene", "u_SunDir", "u_Daylight", "u_FarRange", "u_FogColor", "u_FogStart"};
    /** The overlay's texture units: the two layers' albedo and data, the far depth, the scene's depth. */
    private static final int OVERLAY_UNITS = 6;

    private static FarGpuBuffers buffers;
    static int vao;
    /** The instance id buffer generation the vertex array points at, plus one (0: none yet). */
    private static int vaoInstanceIds;
    private static int overlayVao;
    /**
     * The overlay's framebuffer: the screen's colour texture alone. The overlay samples the screen's depth texture, so it
     * must not draw into the screen's own framebuffer, where that texture is attached: sampling an attachment of the bound
     * framebuffer is a feedback loop with undefined results, and NVIDIA's compressed depth read as sky in bands over near
     * terrain (the user's dev5 and dev6 tests, shaders off).
     */
    private static int overlayFramebuffer;
    /** The colour texture attached to {@link #overlayFramebuffer} (the screen's is made again when the window resizes). */
    private static int overlayColor;
    /** The blend function before the overlay (source and destination, colour and alpha), put back after it. */
    private static final int[] blendBefore = new int[4];
    private static int sampler;
    private static int indices;
    private static int commands;
    private static int commandRecords;
    private static int counts;
    private static final int[] readback = new int[RING];
    private static final long[] fences = new long[RING];
    private static final int[] queries = new int[RING * STAMPS];
    private static final boolean[] drawPending = new boolean[RING];
    /** The slot's overlay stamps (3 and 4) were written too. */
    private static final boolean[] overlayStamped = new boolean[RING];
    private static int slot;

    // The far layers (their colour textures are FarTextures'; the hardware depth buffer is the mod's own, shared)
    private static int opaqueFramebuffer;
    private static int translucentFramebuffer;
    private static int depth;
    private static int layerWidth;
    private static int layerHeight;

    // This frame
    private static boolean drewThisFrame;
    /** The mode this frame's layers were drawn for (FarTerrain.MODE_*). */
    private static int drawnFor;
    private static int drawSlot = -1;
    private static final Matrix4f projection = new Matrix4f();
    /** The far depth's planes this frame (API.md: N and F). */
    private static float farNear;
    private static float farFar;
    /** This frame's far range, for the overlay's haze. */
    private static float overlayRange;
    /** Oculus's frame counter this frame, as worked out before the world (its frameCounter uniform's value to come). */
    private static int frameCounter;

    // The jitter's check: frames drawn for the pack, and those whose frame counter Oculus didn't use after all
    static long jitterFrames;
    static long jitterMismatches;
    /** Frames whose hook made GL calls for the far terrain (drawing, the info texture, the overlay); for the dev checks. */
    private static long glFrames;

    // Statistics (render thread)
    static long selectNanos = -1;
    static long drawNanos = -1;
    static long overlayNanos = -1;
    static int nodesDrawn;
    static long quadsDrawn;
    static int commandsDrawn;
    static long cpuNanos;
    static long overlayCpuNanos;
    private static final long[][] history = new long[7][HISTORY];
    private static int historyCount;

    private FarRenderer() {
    }

    // Capability and programs

    /** Whether the far terrain can be drawn here (OpenGL 4.3, the programs compiled); compiles them on first call. */
    static boolean available() {
        if (!checked) {
            checked = true;
            GLCapabilities caps = GL.getCapabilities();
            if (!caps.OpenGL43) {
                problem = "needs OpenGL 4.3";
                return available = false;
            }
            arbClipControl = !caps.OpenGL45 && caps.GL_ARB_clip_control;
            clipControl = caps.OpenGL45 || caps.GL_ARB_clip_control;
            arbIndirectCount = !caps.OpenGL46 && caps.GL_ARB_indirect_parameters;
            indirectCount = caps.OpenGL46 || caps.GL_ARB_indirect_parameters;
            long start = System.nanoTime();
            try {
                selectProgram = link("far select", compile(GL43C.GL_COMPUTE_SHADER, "far_select.csh"));
                String depthDefine = clipControl ? "" : "#define LOG_DEPTH\n";
                for (int i = 0; i < 2; i++) {
                    terrainPrograms[i] = link("far terrain", compile(GL20C.GL_VERTEX_SHADER, "far_terrain.vsh"),
                            compile(GL20C.GL_FRAGMENT_SHADER, "far_terrain.fsh", depthDefine + (i == 1 ? "#define CLIP\n" : "")));
                    terrainUniforms[i] = uniforms(terrainPrograms[i], TERRAIN_UNIFORMS);
                }
                overlayProgram = link("far overlay", compile(GL20C.GL_VERTEX_SHADER, "far_overlay.vsh"), compile(GL20C.GL_FRAGMENT_SHADER, "far_overlay.fsh"));
                FarShadow.compile();
                FarViews.compile();
            } catch (RuntimeException e) {
                ShadersCompanion.LOGGER.error("Shaders Companion far terrain: its programs failed; the debug view is off", e);
                problem = "its shaders failed (see the log)";
                return available = false;
            }
            selectUniforms = uniforms(selectProgram, SELECT_UNIFORMS);
            overlayUniforms = uniforms(overlayProgram, OVERLAY_UNIFORMS);
            available = true;
            ShadersCompanion.LOGGER.info("Shaders Companion far terrain: drawing with {}, {}; programs compiled in {} ms",
                    clipControl ? "reverse Z (glClipControl)" : "logarithmic depth (no glClipControl)",
                    indirectCount ? "the draw count read from the GPU (indirect parameters)" : "a fixed draw count (no indirect parameters)",
                    (System.nanoTime() - start) / 1_000_000);
        }
        return available;
    }

    static int compile(int type, String name) {
        return compile(type, name, "");
    }

    /** Compiles a shader from assets/shaderscompanion/shaders, with defines inserted after its #version line. */
    static int compile(int type, String name, String defines) {
        String source;
        try (InputStream in = FarRenderer.class.getResourceAsStream("/assets/shaderscompanion/shaders/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing shader " + name);
            }
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("can't read shader " + name, e);
        }
        if (!defines.isEmpty()) {
            int line = source.indexOf('\n') + 1;
            source = source.substring(0, line) + defines + source.substring(line);
        }
        int id = GL20C.glCreateShader(type);
        GL20C.glShaderSource(id, source);
        GL20C.glCompileShader(id);
        if (GL20C.glGetShaderi(id, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(id);
            GL20C.glDeleteShader(id);
            throw new IllegalStateException(name + " failed to compile: " + log);
        }
        return id;
    }

    static int link(String name, int... shaders) {
        int program = GL20C.glCreateProgram();
        for (int shader : shaders) {
            GL20C.glAttachShader(program, shader);
        }
        GL20C.glLinkProgram(program);
        for (int shader : shaders) {
            GL20C.glDetachShader(program, shader);
            GL20C.glDeleteShader(shader);
        }
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(program);
            GL20C.glDeleteProgram(program);
            throw new IllegalStateException(name + " program failed to link: " + log);
        }
        return program;
    }

    static int[] uniforms(int program, String[] names) {
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = GL20C.glGetUniformLocation(program, names[i]);
        }
        return out;
    }

    /** Why the far terrain can't be drawn, or null. */
    static String problem() {
        return problem;
    }

    // Buffers (FarStore's uploads)

    /** The GPU buffers, made on first use (render thread; only while {@link #available}). */
    static FarGpuBuffers buffers() {
        if (buffers == null) {
            int saved = GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER);
            buffers = new FarGpuBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, saved != 0 && GL15C.glIsBuffer(saved) ? saved : 0);
        }
        return buffers;
    }

    static boolean hasBuffers() {
        return buffers != null;
    }

    /** Frees every buffer and texture (the far terrain went off). */
    static void release() {
        if (buffers != null) {
            buffers.delete();
            buffers = null;
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
            drawPending[i] = false;
            overlayStamped[i] = false;
        }
        if (vao != 0) {
            GL30C.glDeleteVertexArrays(vao);
            GL15C.glDeleteBuffers(indices);
            vao = 0;
            indices = 0;
            vaoInstanceIds = 0;
        }
        if (overlayVao != 0) {
            GL30C.glDeleteVertexArrays(overlayVao);
            overlayVao = 0;
        }
        if (overlayFramebuffer > 0) {
            GL30C.glDeleteFramebuffers(overlayFramebuffer);
        }
        overlayFramebuffer = 0;
        overlayColor = 0;
        deleteLayer();
        FarShadow.release();
        FarViews.release();
        // The layers back to 1 x 1 (a pack binding them never gets the missing texture)
        FarTextures.release();
        selectNanos = drawNanos = overlayNanos = -1;
        nodesDrawn = 0;
        quadsDrawn = 0;
        commandsDrawn = 0;
        drewThisFrame = false;
    }

    private static void deleteLayer() {
        if (opaqueFramebuffer != 0) {
            GL30C.glDeleteFramebuffers(new int[]{opaqueFramebuffer, translucentFramebuffer});
            GL11C.glDeleteTextures(depth);
            opaqueFramebuffer = translucentFramebuffer = depth = 0;
            layerWidth = layerHeight = 0;
        }
    }

    /** Video memory of the far layers' textures, in bytes: the five layer textures and the depth buffer. */
    static long layerBytes() {
        return FarTextures.layerBytes() + (long) layerWidth * layerHeight * 4;
    }

    /** Video memory of the command, counter and readback buffers, in bytes. */
    static long commandBytes() {
        return (long) commandRecords * NodeMesh.RANGES * REGIONS * COMMAND_BYTES + (counts != 0 ? 32 + RING * 32 : 0)
                + (indices != 0 ? PATTERN_QUADS * 24L : 0);
    }

    // The frame

    /**
     * Draws the far terrain into the far layers (the frame hook, before the world) and writes the info texture. Does
     * nothing unless the far store has the GPU path on ({@link FarStore#gpuOn}) and a mode for this frame; then only
     * says in the info texture that nothing was drawn, if the pack binds it and it says otherwise.
     *
     * @param camera the main camera
     * @param view   the frame's view rotation (the pose the world is drawn with)
     */
    public static void render(Camera camera, Matrix4f view) {
        drewThisFrame = false;
        Runnable startProbe = frameStartProbeForChecks;
        if (startProbe != null) {
            startProbe.run();
        }
        int mode = FarTerrain.drawMode();
        FarResidency.View v = FarTerrain.frameView();
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (mode == FarTerrain.MODE_NONE || !FarTerrain.gpuOn() || buffers == null || paused || v == null || main.width <= 0 || main.height <= 0) {
            notDrawn();
            return;
        }
        long start = System.nanoTime();
        glFrames++;
        Saved saved = Saved.capture();
        try {
            readBack();
            ensureLayer(main.width, main.height);
            ensureCommands();
            boolean forPack = mode == FarTerrain.MODE_PACK || mode == FarTerrain.MODE_PACK_STILL;
            // Oculus counts the frame at the head of LevelRenderer.renderLevel, after this hook (720720 is a multiple of 16)
            frameCounter = (SystemTimeUniforms.COUNTER.getAsInt() + 1) % 720720;
            int index = frameCounter % 16;
            float jitterX = 0.0F;
            float jitterY = 0.0F;
            if (mode == FarTerrain.MODE_PACK) {
                // The pack's taaOffset (Sundial's R2 sequence, shaders.properties), in clip space per unit of w
                jitterX = (fract(0.5F + index * 0.7548776269F) * 2.0F - 1.0F) / main.width;
                jitterY = (fract(0.5F + index * 0.5698402524F) * 2.0F - 1.0F) / main.height;
            }
            boolean fade = mode == FarTerrain.MODE_PACK && Config.FAR_EDGE_FADE.get();
            draw(camera, view, v, jitterX, jitterY, fade ? v.farRange() * FADE_FROM : 0.0F, index);
            if (forPack) {
                // The far shadow map while the pack binds it (a cascade redrawn when due), then the info with its texels
                float[] shadow = FarShadow.frame(camera.getPosition(), v, buffers, topLevel(v));
                FarTextures.writeDrawn(frameCounter, farNear, farFar, v.farRange(), chunkRadius(v.renderDistance()), projection.m00(), projection.m11(),
                        jitterX, jitterY, layerWidth, layerHeight, shadow);
            } else {
                FarShadow.release();
                FarTextures.writeNotDrawn();
            }
            drewThisFrame = true;
            drawnFor = mode;
        } catch (RuntimeException e) {
            problem = "stopped after an error: " + e;
            available = false;
            ShadersCompanion.LOGGER.error("Shaders Companion far terrain: drawing stopped after an error", e);
            FarTextures.writeNotDrawn();
        } finally {
            saved.restore();
        }
        cpuNanos = System.nanoTime() - start;
    }

    /** The info texture says nothing was drawn this frame (written only when the pack binds it and it said otherwise). */
    private static void notDrawn() {
        if (!FarTextures.notDrawnPending()) {
            return;
        }
        GlState saved = GlState.capture();
        try {
            if (FarTextures.writeNotDrawn()) {
                glFrames++;
            }
        } finally {
            saved.restore();
        }
    }

    private static float fract(float x) {
        return x - (float) Math.floor(x);
    }

    /** The far depth's near plane N (API.md): about an eighth of the chunk terrain's radius, which far fragments never come nearer than. */
    static float farNear(float chunkRadius) {
        return Math.max(2.0F, chunkRadius / 8.0F);
    }

    /** The far depth's far plane F: four times the far range, at least 1,024 blocks. */
    static float farFar(int farRange) {
        return Math.max(1024.0F, farRange * 4.0F);
    }

    private static void draw(Camera camera, Matrix4f view, FarResidency.View v, float jitterX, float jitterY, float fadeStart, int frameIndex) {
        Vec3 position = camera.getPosition();
        int bx = Mth.floor(position.x);
        int by = Mth.floor(position.y);
        int bz = Mth.floor(position.z);
        float fx = (float) (position.x - bx);
        float fy = (float) (position.y - by);
        float fz = (float) (position.z - bz);
        projection.set(RenderSystem.getProjectionMatrix());
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        // The far layer's projection: the frame's with its depth row replaced (the view bobbing is affine, so the
        // depth row only needs the near plane): reverse Z, depth n / -z, or an infinite far plane for the log depth
        Matrix4f layerProjection = new Matrix4f(projection);
        if (clipControl) {
            layerProjection.m02(0.0F).m12(0.0F).m22(0.0F).m32(NEAR);
        } else {
            layerProjection.m02(projection.m03()).m12(projection.m13()).m22(projection.m23()).m32(projection.m33() - 2.0F * NEAR);
        }
        Matrix4f layerViewProjection = layerProjection.mul(view);
        double k = v.k();
        int top = topLevel(v);
        float chunkRadius = chunkRadius(v.renderDistance());
        farNear = farNear(chunkRadius);
        farFar = farFar(v.farRange());
        overlayRange = v.farRange();
        FarGpuBuffers b = buffers;
        int s = slot;
        // Timestamps only in a slot whose last ones were read (it is 4 frames old by now)
        boolean stamp = !drawPending[s];
        if (stamp) {
            GL33C.glQueryCounter(queries[s * STAMPS], GL33C.GL_TIMESTAMP);
        }

        // Selection
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, counts);
        GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, 0, new int[8]);
        if (!indirectCount) {
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, commands);
            GL43C.glClearBufferData(GL31C.GL_COPY_WRITE_BUFFER, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        }
        Gl.useProgram(selectProgram);
        int[] u = selectUniforms;
        GL20C.glUniform3i(u[0], bx, by, bz);
        GL20C.glUniform3f(u[1], fx, fy, fz);
        GL20C.glUniform1f(u[2], (float) k);
        GL20C.glUniform1f(u[3], v.farRange());
        GL20C.glUniform1i(u[4], top);
        GL20C.glUniform1f(u[5], chunkRadius);
        GL20C.glUniform4fv(u[6], frustumPlanes(viewProjection, null));
        GL20C.glUniform1i(u[7], b.recordHigh);
        GL20C.glUniform1i(u[8], commandRecords * NodeMesh.RANGES);
        GL20C.glUniform1i(u[9], probe == 2 ? 1 : 0);
        GL20C.glUniform1f(u[10], fadeStart);
        GL20C.glUniform1i(u[11], 0);
        GL20C.glUniform3f(u[12], 0.0F, 0.0F, 0.0F);
        GL20C.glUniform1i(u[13], 0);
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

        // The draw into the far layers: the opaque one first (albedo, data, far depth's r), cleared to nothing and the
        // far depth to 1 in both channels
        Gl.bindFramebuffer(GL30C.GL_FRAMEBUFFER, opaqueFramebuffer);
        GlStateManager._viewport(0, 0, layerWidth, layerHeight);
        GlStateManager._disableScissorTest();
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GlStateManager._disableBlend();
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._depthMask(true);
        if (clipControl) {
            clipControl(GL20C.GL_LOWER_LEFT, GL45C.GL_ZERO_TO_ONE);
        }
        float[] zero = {0.0F, 0.0F, 0.0F, 0.0F};
        GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, zero);
        GL30C.glClearBufferfv(GL11C.GL_COLOR, 1, zero);
        GL30C.glClearBufferfv(GL11C.GL_COLOR, 2, new float[]{1.0F, 1.0F, 0.0F, 0.0F});
        GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[]{clipControl ? 0.0F : 1.0F});
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(clipControl ? GL11C.GL_GREATER : GL11C.GL_LESS);
        GlStateManager._enableCull();
        GL11C.glCullFace(GL11C.GL_BACK);
        GL11C.glFrontFace(GL11C.GL_CCW);
        ensureVao();
        GlStateManager._glBindVertexArray(vao);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, b.geometry);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, b.colours);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, b.info);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, b.biomes);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, commands);
        if (indirectCount) {
            GL15C.glBindBuffer(GL46C.GL_PARAMETER_BUFFER, counts);
        }
        float[] matrix = layerViewProjection.get(new float[16]);
        float logDepth = (float) (1.0 / (Math.log(Math.max(v.farRange() * 4.0, 1024.0) / NEAR) / Math.log(2.0)));
        for (int i = 0; i < 2; i++) {
            Gl.useProgram(terrainPrograms[i]);
            int[] t = terrainUniforms[i];
            GL20C.glUniformMatrix4fv(t[0], false, matrix);
            GL20C.glUniform3i(t[1], bx, by, bz);
            GL20C.glUniform3f(t[2], fx, fy, fz);
            GL20C.glUniform1i(t[3], b.stateCount);
            GL20C.glUniform1i(t[4], b.biomeCount);
            GL20C.glUniform1f(t[6], chunkRadius);
            GL20C.glUniform1f(t[7], logDepth);
            GL20C.glUniform1f(t[8], NEAR);
            GL20C.glUniform2f(t[9], jitterX, jitterY);
            // The view's z row (gbufferModelView's third row): the protocol's depth is of this z, not the bobbed one
            GL20C.glUniform4f(t[10], view.m02(), view.m12(), view.m22(), view.m32());
            GL20C.glUniform2f(t[11], (farFar + farNear) / (farFar - farNear), 2.0F * farFar * farNear / (farFar - farNear));
            GL20C.glUniform1f(t[12], farNear);
            GL20C.glUniform1f(t[13], fadeStart);
            GL20C.glUniform1f(t[14], v.farRange());
            GL20C.glUniform1i(t[15], frameIndex);
            GL20C.glUniform1f(t[16], Float.isNaN(plantShadeForChecks) ? PLANT_SHADE : plantShadeForChecks);
            // Level 0 is drawn out to about 2K (its parent splits nearer than that): faded out from 1.25K to 2K less a node
            GL20C.glUniform2f(t[17], (float) (k * 1.25), (float) Math.max(k * 1.25 + 1.0, k * 2.0 - Node.SIZE));
        }
        int max = commandRecords * NodeMesh.RANGES;
        boolean discard = probe == 1 && !GL11C.glIsEnabled(GL30C.GL_RASTERIZER_DISCARD);
        if (discard) {
            GL11C.glEnable(GL30C.GL_RASTERIZER_DISCARD);
        }
        // The far depth's r only in the opaque pass, its g only in the translucent one (colour masks are per draw buffer)
        GL30C.glColorMaski(2, true, false, false, false);
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                // The translucent layer: its own albedo and data, cleared; the far depth's g; the opaque pass's hardware
                // depth, so the nearest translucent surface in front of the opaque one wins
                Gl.bindFramebuffer(GL30C.GL_FRAMEBUFFER, translucentFramebuffer);
                GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, zero);
                GL30C.glClearBufferfv(GL11C.GL_COLOR, 1, zero);
                GL30C.glColorMaski(2, false, true, false, false);
            }
            for (int variant = 0; variant < 2; variant++) {
                int region = pass * 2 + variant;
                Gl.useProgram(terrainPrograms[variant]);
                GL20C.glUniform1i(terrainUniforms[variant][5], pass);
                long offset = (long) region * max * COMMAND_BYTES;
                if (!indirectCount) {
                    GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, offset, max, COMMAND_BYTES);
                } else if (arbIndirectCount) {
                    ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, offset, region * 4L, max, COMMAND_BYTES);
                } else {
                    GL46C.glMultiDrawElementsIndirectCount(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, offset, region * 4L, max, COMMAND_BYTES);
                }
            }
        }
        if (discard) {
            GL11C.glDisable(GL30C.GL_RASTERIZER_DISCARD);
        }
        // Every draw buffer's mask back to all on, as Minecraft's record (set above) says; GlState puts its own back
        GL11C.glColorMask(true, true, true, true);
        if (stamp) {
            GL33C.glQueryCounter(queries[s * STAMPS + 2], GL33C.GL_TIMESTAMP);
            drawPending[s] = true;
            overlayStamped[s] = false;
            drawSlot = s;
        } else {
            drawSlot = -1;
        }
        // The counters, read back once the GPU is past them
        if (fences[s] == 0) {
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, counts);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, readback[s]);
            GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, 32);
            fences[s] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
        slot = (s + 1) % RING;
    }

    /**
     * The selection's six planes (camera-relative, inside where dot(xyz, p) + w >= 0): a matrix's frustum planes but the
     * far one, then a cut plane, or (0, 0, 0, 1) for none.
     */
    static float[] frustumPlanes(Matrix4f matrix, float[] cut) {
        float[] planes = new float[24];
        Vector4f plane = new Vector4f();
        for (int i = 0; i < 5; i++) {
            matrix.frustumPlane(i, plane);
            planes[i * 4] = plane.x;
            planes[i * 4 + 1] = plane.y;
            planes[i * 4 + 2] = plane.z;
            planes[i * 4 + 3] = plane.w;
        }
        if (cut != null) {
            System.arraycopy(cut, 0, planes, 20, 4);
        } else {
            planes[23] = 1.0F;
        }
        return planes;
    }

    static void clipControl(int origin, int mode) {
        if (arbClipControl) {
            ARBClipControl.glClipControl(origin, mode);
        } else {
            GL45C.glClipControl(origin, mode);
        }
    }

    /**
     * The chunk terrain the far terrain leaves out: Embeddium draws the sections whose nearest point is within the render
     * distance of the camera, horizontally (a cylinder) and vertically (RenderSectionDistanceFilter.DEFAULT, with
     * shaders on); the outermost ring of loaded chunks isn't built, so one chunk less.
     */
    static float chunkRadius(int renderDistance) {
        return Math.max(0, renderDistance - 1) * 16.0F;
    }

    /** The coarsest level drawn: the last whose inner edge (K * 2^L) is within the far range (as FarResidency). */
    static int topLevel(FarResidency.View v) {
        double k = v.k();
        int top = 0;
        for (int level = 1; level < Node.LEVELS; level++) {
            if (k * (1 << level) < v.farRange()) {
                top = level;
            }
        }
        return top;
    }

    /**
     * The layers at this size: FarTextures' five textures (re-specified in place) and the mod's own hardware depth
     * buffer, in two framebuffers: opaque (far_albedo, far_data, far_depth) and translucent (far_translucent_albedo,
     * far_translucent_data, far_depth), both with the same depth buffer.
     */
    private static void ensureLayer(int width, int height) {
        if (opaqueFramebuffer != 0 && width == layerWidth && height == layerHeight) {
            return;
        }
        deleteLayer();
        FarTextures.ensureSize(width, height);
        depth = texture(GL30C.GL_DEPTH_COMPONENT32F, width, height);
        opaqueFramebuffer = framebuffer(FarTextures.albedo(), FarTextures.data(), "opaque");
        translucentFramebuffer = framebuffer(FarTextures.translucentAlbedo(), FarTextures.translucentData(), "translucent");
        layerWidth = width;
        layerHeight = height;
    }

    private static int framebuffer(int albedo, int data, String name) {
        int id = GL30C.glGenFramebuffers();
        Gl.bindFramebuffer(GL30C.GL_FRAMEBUFFER, id);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, albedo, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1, GL11C.GL_TEXTURE_2D, data, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT2, GL11C.GL_TEXTURE_2D, FarTextures.depth(), 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
        GL20C.glDrawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1, GL30C.GL_COLOR_ATTACHMENT2});
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("far " + name + " layer framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
        return id;
    }

    /** A screen-sized texture (bound on the active unit, unit 0 here: the saved state puts unit 0 back). */
    private static int texture(int format, int width, int height) {
        int id = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        GL42C.glTexStorage2D(GL11C.GL_TEXTURE_2D, 1, format, width, height);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL13C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL13C.GL_CLAMP_TO_EDGE);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        return id;
    }

    /** The command buffer holds every record's 12 ranges in each of the 4 regions; counters and their readback copies. */
    private static void ensureCommands() {
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
        int records = buffers.recordCapacity;
        if (commands == 0 || commandRecords != records) {
            if (commands != 0) {
                GL15C.glDeleteBuffers(commands);
            }
            commands = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, commands);
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, (long) records * NodeMesh.RANGES * REGIONS * COMMAND_BYTES, GL15C.GL_DYNAMIC_DRAW);
            commandRecords = records;
        }
    }

    /**
     * The vertex array: attribute 0 is the record index, one per instance, from the instance id buffer; the element
     * buffer is the shared quad pattern (quad q: 4q, 4q + 1, 4q + 2, 4q, 4q + 2, 4q + 3), which a command offsets to its
     * range by its base vertex (4 per quad).
     */
    static void ensureVao() {
        if (vao == 0) {
            vao = GL30C.glGenVertexArrays();
            int[] pattern = new int[PATTERN_QUADS * 6];
            for (int q = 0; q < PATTERN_QUADS; q++) {
                pattern[q * 6] = q * 4;
                pattern[q * 6 + 1] = q * 4 + 1;
                pattern[q * 6 + 2] = q * 4 + 2;
                pattern[q * 6 + 3] = q * 4;
                pattern[q * 6 + 4] = q * 4 + 2;
                pattern[q * 6 + 5] = q * 4 + 3;
            }
            indices = GL15C.glGenBuffers();
            GlStateManager._glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indices);
            GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, pattern, GL15C.GL_STATIC_DRAW);
            vaoInstanceIds = 0;
        }
        if (vaoInstanceIds != buffers.instanceIdsGeneration + 1) {
            GlStateManager._glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffers.instanceIds);
            GL20C.glEnableVertexAttribArray(0);
            GL30C.glVertexAttribIPointer(0, 1, GL11C.GL_INT, 4, 0L);
            GL33C.glVertexAttribDivisor(0, 1);
            vaoInstanceIds = buffers.instanceIdsGeneration + 1;
        }
    }

    /** Reads finished GPU timestamps and counters (never waits). */
    private static void readBack() {
        FarShadow.readBack();
        for (int i = 0; i < RING; i++) {
            if (fences[i] != 0) {
                int status = GL32C.glClientWaitSync(fences[i], 0, 0L);
                if (status == GL32C.GL_ALREADY_SIGNALED || status == GL32C.GL_CONDITION_SATISFIED) {
                    int[] c = new int[8];
                    GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, readback[i]);
                    GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER, 0, c);
                    GL32C.glDeleteSync(fences[i]);
                    fences[i] = 0;
                    commandsDrawn = c[0] + c[1] + c[2] + c[3];
                    nodesDrawn = c[4];
                    quadsDrawn = Integer.toUnsignedLong(c[5]);
                }
            }
            int last = i * STAMPS + (overlayStamped[i] ? 4 : 2);
            if (drawPending[i] && GL15C.glGetQueryObjecti(queries[last], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long t0 = GL33C.glGetQueryObjecti64(queries[i * STAMPS], GL15C.GL_QUERY_RESULT);
                long t1 = GL33C.glGetQueryObjecti64(queries[i * STAMPS + 1], GL15C.GL_QUERY_RESULT);
                long t2 = GL33C.glGetQueryObjecti64(queries[i * STAMPS + 2], GL15C.GL_QUERY_RESULT);
                selectNanos = t1 - t0;
                drawNanos = t2 - t1;
                if (overlayStamped[i]) {
                    overlayNanos = GL33C.glGetQueryObjecti64(queries[i * STAMPS + 4], GL15C.GL_QUERY_RESULT)
                            - GL33C.glGetQueryObjecti64(queries[i * STAMPS + 3], GL15C.GL_QUERY_RESULT);
                }
                drawPending[i] = false;
                overlayStamped[i] = false;
                int at = historyCount % HISTORY;
                history[0][at] = selectNanos;
                history[1][at] = drawNanos;
                history[2][at] = overlayNanos;
                history[3][at] = nodesDrawn;
                history[4][at] = quadsDrawn;
                history[5][at] = cpuNanos;
                history[6][at] = FarTerrain.terrainFrameNanos();
                historyCount++;
            }
        }
    }

    /**
     * Draws the debug view over the finished frame (the frame hook, after the world; before the hand and the GUI).
     * Does nothing unless the far layer was drawn this frame.
     */
    public static void overlay() {
        if (!drewThisFrame) {
            return;
        }
        drewThisFrame = false;
        if (drawnFor == FarTerrain.MODE_PACK || drawnFor == FarTerrain.MODE_PACK_STILL) {
            // Drawn for the pack: no overlay. Oculus counted this frame at the head of the world's rendering: the info
            // texture's frame counter (and the jitter) should be the one it used
            jitterFrames++;
            if (SystemTimeUniforms.COUNTER.getAsInt() != frameCounter) {
                jitterMismatches++;
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main.width != layerWidth || main.height != layerHeight) {
            return;
        }
        Runnable probe = overlayProbeForChecks;
        if (probe != null) {
            overlayProbeForChecks = null;
            probe.run();
        }
        long start = System.nanoTime();
        int s = drawSlot;
        GlState saved = GlState.capture();
        int vaoBefore = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int[] textures = new int[OVERLAY_UNITS];
        int[] samplers = new int[OVERLAY_UNITS];
        try {
            for (int unit = 0; unit < OVERLAY_UNITS; unit++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                textures[unit] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                samplers[unit] = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
            }
            Gl.activeTexture(GL13C.GL_TEXTURE0);
            if (s >= 0) {
                // Overlay stamps 3 and 4 in the draw's slot (the slot is read once its last stamp is in)
                GL33C.glQueryCounter(queries[s * STAMPS + 3], GL33C.GL_TIMESTAMP);
            }
            if (sampler == 0) {
                sampler = GL33C.glGenSamplers();
                GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                GL33C.glSamplerParameteri(sampler, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
            }
            if (overlayVao == 0) {
                overlayVao = GL30C.glGenVertexArrays();
            }
            int sceneDepth = alone ? 0 : main.getDepthTextureId();
            int[] bind = {FarTextures.albedo(), FarTextures.data(), FarTextures.translucentAlbedo(), FarTextures.translucentData(), FarTextures.depth(), sceneDepth};
            for (int unit = 0; unit < OVERLAY_UNITS; unit++) {
                Gl.activeTexture(GL13C.GL_TEXTURE0 + unit);
                Gl.bindTexture(bind[unit]);
                GL33C.glBindSampler(unit, sampler);
            }
            Gl.activeTexture(GL13C.GL_TEXTURE0);
            Gl.bindFramebuffer(GL30C.GL_FRAMEBUFFER, sceneDepth != 0 ? overlayFramebuffer(main) : main.frameBufferId);
            GlStateManager._viewport(0, 0, main.width, main.height);
            GlStateManager._disableScissorTest();
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._disableCull();
            // Over the frame the far terrain's haze blends into the sky the pack drew (the blend function is put back below)
            blendBefore[0] = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
            blendBefore[1] = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
            blendBefore[2] = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
            blendBefore[3] = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
            GlStateManager._disableBlend();
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            GlStateManager._colorMask(true, true, true, true);
            Gl.useProgram(overlayProgram);
            int[] u = overlayUniforms;
            // The protocol's inverse projection (API.md): the frame's, with [2].w and [3].w from the far depth's N and F
            Matrix4f inverse = new Matrix4f(projection).invert();
            inverse.m23(-(farFar - farNear) / (2.0F * farFar * farNear)).m33((farFar + farNear) / (2.0F * farFar * farNear));
            GL20C.glUniformMatrix4fv(u[0], false, inverse.get(new float[16]));
            GL20C.glUniform2f(u[1], main.width, main.height);
            GL20C.glUniform1i(u[2], alone ? 1 : 0);
            GL20C.glUniform1i(u[3], sceneDepth != 0 ? 1 : 0);
            // The sun moves in the x-y plane: up at noon, east (+x) at sunrise
            float partial = mc.getFrameTime();
            float sunAngle = mc.level == null ? 0.0f : mc.level.getSunAngle(partial);
            GL20C.glUniform3f(u[4], -Mth.sin(sunAngle), Mth.cos(sunAngle), 0.0f);
            float daylight = mc.level == null ? 1.0f : Mth.clamp((mc.level.getSkyDarken(partial) - 0.2f) / 0.8f, 0.0f, 1.0f);
            GL20C.glUniform1f(u[5], daylight);
            GL20C.glUniform1f(u[6], overlayRange);
            // The vanilla fog colour of this frame (FogRenderer: time of day, weather, biome, sunsets)
            float[] fogColor = RenderSystem.getShaderFogColor();
            GL20C.glUniform3f(u[7], Mth.clamp(fogColor[0], 0.0f, 1.0f), Mth.clamp(fogColor[1], 0.0f, 1.0f), Mth.clamp(fogColor[2], 0.0f, 1.0f));
            GL20C.glUniform1f(u[8], overlayRange * FarTerrain.FOG_START);
            GlStateManager._glBindVertexArray(overlayVao);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
            if (s >= 0) {
                GL33C.glQueryCounter(queries[s * STAMPS + 4], GL33C.GL_TIMESTAMP);
                overlayStamped[s] = true;
            }
        } finally {
            for (int unit = OVERLAY_UNITS - 1; unit >= 0; unit--) {
                GL33C.glBindSampler(unit, samplers[unit] != 0 && GL33C.glIsSampler(samplers[unit]) ? samplers[unit] : 0);
                if (unit > 0) {
                    Gl.activeTexture(GL13C.GL_TEXTURE0 + unit);
                    Gl.bindTextureIfAlive(textures[unit]);
                }
            }
            GlStateManager._blendFuncSeparate(blendBefore[0], blendBefore[1], blendBefore[2], blendBefore[3]);
            saved.restore();
            GlStateManager._glBindVertexArray(vaoBefore != 0 && GL30C.glIsVertexArray(vaoBefore) ? vaoBefore : 0);
        }
        overlayCpuNanos = System.nanoTime() - start;
    }

    /**
     * The overlay's framebuffer with the screen's colour texture attached and no depth, or the screen's own framebuffer
     * if that can't be made complete (logged once). Attached every frame: a texture the screen made again (a resize) can
     * reuse the old one's name, and the old attachment would still be the deleted texture.
     */
    private static int overlayFramebuffer(RenderTarget main) {
        if (overlayFramebuffer < 0) {
            return main.frameBufferId;
        }
        if (overlayFramebuffer == 0) {
            overlayFramebuffer = GL30C.glGenFramebuffers();
            overlayColor = 0;
        }
        Gl.bindFramebuffer(GL30C.GL_FRAMEBUFFER, overlayFramebuffer);
        int color = main.getColorTextureId();
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, color, 0);
        if (color != overlayColor) {
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                ShadersCompanion.LOGGER.warn("Shaders Companion far terrain: the debug view's framebuffer is incomplete (0x{}); drawing it into the screen's own",
                        Integer.toHexString(status));
                GL30C.glDeleteFramebuffers(overlayFramebuffer);
                overlayFramebuffer = -1;
                return main.frameBufferId;
            }
            overlayColor = color;
        }
        return overlayFramebuffer;
    }

    // Status and checks

    /** The status line's GPU part. */
    static String status() {
        if (problem != null) {
            return "the far terrain can't draw: " + problem;
        }
        FarGpuBuffers b = buffers;
        if (b == null) {
            return "nothing on the GPU yet";
        }
        boolean forPack = drawnFor == FarTerrain.MODE_PACK || drawnFor == FarTerrain.MODE_PACK_STILL;
        String overlay = forPack ? "" : String.format(", overlay %s", ms(overlayNanos));
        String jitter = jitterFrames == 0 ? "" : String.format("; the frame counter was Oculus's in %d of %d frames drawn for the pack", jitterFrames - jitterMismatches,
                jitterFrames);
        return String.format("%d meshes on the GPU (%.1f MB of %.0f MB%s), %d drawn (%d quads, %d draws), GPU %s (selection %s, draw %s%s), "
                        + "uploads %.1f MB a second%s, render thread %.0f us a frame for the draw%s%s%s%s", b.meshesOnGpu(),
                b.geometryBytesUsed() / 1048576.0, b.geometryBytes / 1048576.0, b.dirtyCount() > 0 ? ", " + b.dirtyCount() + " waiting to upload" : "",
                nodesDrawn, quadsDrawn, commandsDrawn, ms(selectNanos < 0 ? -1 : selectNanos + drawNanos), ms(selectNanos), ms(drawNanos), overlay,
                b.lastSecondBytes / 1048576.0, b.noRoom > 0 ? ", " + b.noRoom + " meshes didn't fit" : "", cpuNanos / 1.0e3,
                forPack ? "" : String.format(", %.0f for the overlay", overlayCpuNanos / 1.0e3), jitter, alone ? "; shown alone" : "", FarShadow.status());
    }

    private static String ms(long nanos) {
        return nanos < 0 ? "n/a" : String.format("%.2f ms", nanos / 1.0e6);
    }

    /** Dev checks: run once at the start of the next debug view overlay, with the frame as the overlay reads it. */
    public static volatile Runnable overlayProbeForChecks;

    /** Dev checks: run at the head of every frame's far draw hook (just before the level renders) while set. */
    public static volatile Runnable frameStartProbeForChecks;

    /** Dev checks: 0 draws normally, 1 with the rasterizer off, 2 one quad per command (see {@link #probe}). */
    public static void probeForChecks(int which) {
        probe = which;
    }

    /** Dev checks: drawing paused (true) or not, the buffers kept. */
    public static void pauseForChecks(boolean pause) {
        paused = pause;
    }

    /** Dev checks: the planted faces' colour factor for the run (NaN: the mod's own). */
    public static void plantShadeForChecks(float factor) {
        plantShadeForChecks = factor;
    }

    /** Dev checks: the debug view alone (true) or over the frame. */
    public static void aloneForChecks(boolean show) {
        alone = show;
    }

    /** Dev checks: frames recorded so far (the history's write count); the history restarts with {@link #historyForChecks}. */
    public static int framesForChecks() {
        return historyCount;
    }

    /**
     * Dev checks: medians and maxima over the frames recorded since the last call: selection, draw and overlay GPU
     * nanos, nodes drawn, quads drawn, the draw's render-thread nanos and the far store's render-thread nanos (each
     * median then max), then the frame count.
     */
    public static long[] historyForChecks() {
        int n = Math.min(historyCount, HISTORY);
        long[] out = new long[7 * 2 + 1];
        for (int k = 0; k < 7; k++) {
            long[] values = Arrays.copyOf(history[k], n);
            Arrays.sort(values);
            out[k * 2] = n == 0 ? 0 : values[n / 2];
            out[k * 2 + 1] = n == 0 ? 0 : values[n - 1];
        }
        out[14] = n;
        historyCount = 0;
        return out;
    }

    /** Dev checks: frames drawn for the pack so far, and those whose frame counter wasn't the one Oculus used. */
    public static long[] jitterForChecks() {
        return new long[]{jitterFrames, jitterMismatches};
    }

    /** Dev checks: frames whose hook made GL calls for the far terrain so far (drawing, the info texture). */
    public static long glFramesForChecks() {
        return glFrames;
    }

    /** Dev checks: this frame's far depth planes N and F and Oculus's frame counter as worked out before the world. */
    public static float[] depthPlanesForChecks() {
        return new float[]{farNear, farFar, frameCounter};
    }

    /** Dev checks: the video memory of the far terrain's buffers and the far layers, in bytes: geometry allocated, geometry used, everything. */
    public static long[] memoryForChecks() {
        FarGpuBuffers b = buffers;
        if (b == null) {
            return new long[3];
        }
        return new long[]{b.geometryBytes, b.geometryBytesUsed(), b.bytesAllocated() + commandBytes() + layerBytes() + FarShadow.bytes()};
    }

    /**
     * Dev checks: the far shadow map since the last call: per cascade the redraws, the median and max GPU nanos of a
     * redraw, the last redraw's quads; then the frames drawn, the frames with a redraw, and 1 when both cascades are in use.
     */
    public static long[] shadowNumbersForChecks() {
        return FarShadow.numbersForChecks();
    }

    /**
     * The GL state the far terrain's draw touches beyond {@link GlState}: the vertex array, the array, copy, indirect
     * and storage buffer bindings (generic and indexed 0 to 6, with their ranges), clip control, the cull face and the
     * front face. The unit 0 texture the layer's creation binds is GlState's.
     */
    record Saved(GlState state, int vao, int arrayBuffer, int copyRead, int copyWrite, int drawIndirect, int parameter, int storage, int[] storageIndexed,
                         long[] storageStart, long[] storageSize, int clipOrigin, int clipDepth, int cullFace, int frontFace) {
        static Saved capture() {
            GlState state = GlState.capture();
            int[] indexed = new int[7];
            long[] start = new long[7];
            long[] size = new long[7];
            for (int i = 0; i < 7; i++) {
                indexed[i] = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, i);
                start[i] = GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_START, i);
                size[i] = GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, i);
            }
            return new Saved(state, GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING), GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING),
                    GL11C.glGetInteger(GL31C.GL_COPY_READ_BUFFER), GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER),
                    GL11C.glGetInteger(GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING), indirectCount ? GL11C.glGetInteger(GL46C.GL_PARAMETER_BUFFER_BINDING) : 0,
                    GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING), indexed, start, size,
                    clipControl ? GL11C.glGetInteger(GL45C.GL_CLIP_ORIGIN) : 0, clipControl ? GL11C.glGetInteger(GL45C.GL_CLIP_DEPTH_MODE) : 0,
                    GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE), GL11C.glGetInteger(GL11C.GL_FRONT_FACE));
        }

        void restore() {
            if (clipControl && clipOrigin != 0) {
                clipControl(clipOrigin, clipDepth);
            }
            GL11C.glCullFace(cullFace);
            GL11C.glFrontFace(frontFace);
            for (int i = 0; i < 7; i++) {
                int buffer = alive(storageIndexed[i]);
                if (buffer != 0 && storageSize[i] > 0) {
                    GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, i, buffer, storageStart[i], storageSize[i]);
                } else {
                    GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, i, buffer);
                }
            }
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, alive(storage));
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, alive(drawIndirect));
            if (indirectCount) {
                GL15C.glBindBuffer(GL46C.GL_PARAMETER_BUFFER, alive(parameter));
            }
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, alive(copyRead));
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, alive(copyWrite));
            // The draw's per draw buffer colour masks: every buffer to all on, in Minecraft's record and in GL, so GlState
            // puts the saved mask back on all of them (also after an error between the two)
            GlStateManager._colorMask(true, true, true, true);
            GL11C.glColorMask(true, true, true, true);
            // GlState unbinds the vertex array (and tells Minecraft's buffer uploader); the real one goes back after it
            state.restore();
            GlStateManager._glBindVertexArray(vao != 0 && GL30C.glIsVertexArray(vao) ? vao : 0);
            GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, alive(arrayBuffer));
        }

        private static int alive(int buffer) {
            return buffer != 0 && GL15C.glIsBuffer(buffer) ? buffer : 0;
        }
    }
}
