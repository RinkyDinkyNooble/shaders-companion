package com.rinkynooble.shaderscompanion.client;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.nio.IntBuffer;
import java.util.Arrays;
import net.minecraft.core.SectionPos;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;

/**
 * The lattice's openness field (API.md, "Lattice", openness): baked ambient occlusion. Probes every half block in a
 * {@link #WIDTH}-block window centred on the section boundary nearest the camera, each with how open it is in six directions, traced through the
 * lattice's quarter-block shapes within {@link #REACH} blocks by a compute shader of the mod's own
 * ({@code lattice_open.csh}). A section's probes are baked when the section enters the window and again when the
 * shapes around it change (a stamp of the lattice's shape hashes of the section and its 26 neighbours), never because
 * the camera turned: the pack samples a field that holds still, with no noise and no history. Nearest sections first,
 * at most {@link #SECTIONS_PER_FRAME} a frame. Needs OpenGL 4.4 (or 4.3 with texture clearing).
 */
final class LatticeField {
    static final int MAGIC = 4243;
    static final int PROTOCOL = 1;
    /** Window width in blocks (and height); probes every half block, so the textures are twice as wide. */
    static final int WIDTH = 64;
    static final int PROBES = WIDTH * 2;
    /** How far the probes look, in blocks. */
    static final float REACH = 1.0F;
    static final String OPEN_A = "lattice_open_a";
    static final String OPEN_B = "lattice_open_b";
    private static final int SECTIONS = WIDTH / 16;
    private static final int SECTIONS_PER_FRAME = 4;
    private static final long NONE = Long.MIN_VALUE;

    private static boolean available;
    private static boolean checked;
    private static int program;
    private static int openA;
    private static int openB;
    private static int uSectionMin;
    private static int uLatticeMin;
    private static int uLatticeSize;
    private static int uLog2Width;
    private static int uProbeMask;
    private static int uTag;
    private static int uReach;
    private static final long[] slotSection = new long[SECTIONS * SECTIONS * SECTIONS];
    private static final long[] slotStamp = new long[SECTIONS * SECTIONS * SECTIONS];
    /** Section offsets from the camera's section covering the window, nearest first. */
    private static final int[] offsets = offsets();

    // GPU time of the baking, from timestamps read a few frames later
    private static final int[] queries = new int[8];
    private static final boolean[] pending = new boolean[4];
    private static int nextQuery;
    private static long gpuNanos = -1;

    static int baked;
    static int waiting;
    static int ready;
    static String problem;

    private LatticeField() {
    }

    static int openA() {
        return openA;
    }

    static int openB() {
        return openB;
    }

    static boolean available() {
        if (!checked) {
            checked = true;
            if (!GL.getCapabilities().OpenGL43 || !(GL.getCapabilities().OpenGL44 || GL.getCapabilities().GL_ARB_clear_texture)) {
                problem = "needs OpenGL 4.4";
                return available = false;
            }
            try {
                int shader = ViewProgram.compile(GL43C.GL_COMPUTE_SHADER, "lattice_open.csh");
                program = GL20C.glCreateProgram();
                GL20C.glAttachShader(program, shader);
                GL20C.glLinkProgram(program);
                GL20C.glDetachShader(program, shader);
                GL20C.glDeleteShader(shader);
                if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
                    throw new IllegalStateException("lattice_open program failed to link: " + GL20C.glGetProgramInfoLog(program));
                }
            } catch (RuntimeException e) {
                ShadersCompanion.LOGGER.error("Shaders Companion: the lattice's openness field is off", e);
                problem = "its shader failed (see the log)";
                return available = false;
            }
            uSectionMin = GL20C.glGetUniformLocation(program, "u_SectionMin");
            uLatticeMin = GL20C.glGetUniformLocation(program, "u_LatticeMin");
            uLatticeSize = GL20C.glGetUniformLocation(program, "u_LatticeSize");
            uLog2Width = GL20C.glGetUniformLocation(program, "u_Log2Width");
            uProbeMask = GL20C.glGetUniformLocation(program, "u_ProbeMask");
            uTag = GL20C.glGetUniformLocation(program, "u_Tag");
            uReach = GL20C.glGetUniformLocation(program, "u_Reach");
            int previous = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
            openA = allocate();
            openB = allocate();
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previous);
            GL15C.glGenQueries(queries);
            available = true;
            reset();
        }
        return available;
    }

    private static int allocate() {
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
        GL42C.glTexStorage3D(GL12C.GL_TEXTURE_3D, 1, GL11C.GL_RGBA8, PROBES, PROBES, PROBES);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        return texture;
    }

    /** A new world or lattice: every probe unknown. */
    static void reset() {
        if (!available) {
            return;
        }
        GL44C.glClearTexImage(openA, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (IntBuffer) null);
        GL44C.glClearTexImage(openB, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (IntBuffer) null);
        Arrays.fill(slotSection, NONE);
        Arrays.fill(slotStamp, 0L);
    }

    /**
     * The section the window is centred on along an axis: the section boundary nearest the camera, so the window
     * reaches at least 24 blocks from the camera every way.
     */
    static int centre(int cameraBlock) {
        return Math.floorDiv(cameraBlock + 8, 16);
    }

    /** The window's lowest block on an axis, for its centre on that axis. */
    static int windowMin(int centre) {
        return (centre - SECTIONS / 2) * 16;
    }

    /**
     * Bakes the sections that need it, nearest first, after the lattice's own update this frame (render thread). GL
     * state touched here (program, active unit, 3D textures on units 0 and 1, image units 0 and 1) is put back.
     */
    static void update(int centreX, int centreY, int centreZ, int cameraX, int cameraY, int cameraZ) {
        baked = 0;
        waiting = 0;
        ready = 0;
        if (!available()) {
            return;
        }
        readTimer();
        int dispatched = 0;
        int[] saved = null;
        for (int i = 0; i < offsets.length; i += 3) {
            int x = centreX + offsets[i];
            int y = centreY + offsets[i + 1];
            int z = centreZ + offsets[i + 2];
            int slot = (Math.floorMod(y, SECTIONS) * SECTIONS + Math.floorMod(z, SECTIONS)) * SECTIONS + Math.floorMod(x, SECTIONS);
            long stamp = Lattice.neighbourhoodStamp(x, y, z, cameraX, cameraY, cameraZ);
            long key = SectionPos.asLong(x, y, z);
            if (stamp == NONE) {
                // The lattice around it isn't read yet
                waiting++;
                continue;
            }
            if (slotSection[slot] == key && slotStamp[slot] == stamp) {
                ready++;
                continue;
            }
            if (dispatched >= SECTIONS_PER_FRAME) {
                waiting++;
                continue;
            }
            if (saved == null) {
                saved = bind();
            }
            int parity = ((x * 32 >> 7) & 1) | ((y * 32 >> 7) & 1) << 1 | ((z * 32 >> 7) & 1) << 2;
            GL20C.glUniform3i(uSectionMin, x * 16, y * 16, z * 16);
            GL20C.glUniform1f(uTag, (parity + 1) / 255.0F);
            GL43C.glDispatchCompute(PROBES / SECTIONS / 8, PROBES / SECTIONS / 8, PROBES / SECTIONS / 8);
            slotSection[slot] = key;
            slotStamp[slot] = stamp;
            dispatched++;
            ready++;
        }
        if (saved != null) {
            GL42C.glMemoryBarrier(GL42C.GL_TEXTURE_FETCH_BARRIER_BIT | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            endTimer();
            restore(saved);
        }
        baked = dispatched;
    }

    /** Binds the program, the lattice and the field for baking; returns what was bound before. */
    private static int[] bind() {
        int[] saved = new int[3 + 2 + 2 * 6];
        saved[0] = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        saved[1] = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        for (int unit = 0; unit < 2; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            saved[2 + unit] = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, unit == 0 ? Lattice.cellsTexture() : Lattice.shapeTexture());
            int base = 5 + unit * 6;
            saved[base] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_NAME, unit);
            saved[base + 1] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LEVEL, unit);
            saved[base + 2] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYERED, unit);
            saved[base + 3] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYER, unit);
            saved[base + 4] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_ACCESS, unit);
            saved[base + 5] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_FORMAT, unit);
            GL42C.glBindImageTexture(unit, unit == 0 ? openA : openB, 0, true, 0, GL15C.GL_WRITE_ONLY, GL11C.GL_RGBA8);
        }
        GL20C.glUseProgram(program);
        GL20C.glUniform3i(uLatticeMin, Lattice.windowMinX(), Lattice.windowMinY(), Lattice.windowMinZ());
        GL20C.glUniform3i(uLatticeSize, Lattice.width(), Lattice.HEIGHT, Lattice.width());
        GL20C.glUniform1i(uLog2Width, Integer.numberOfTrailingZeros(Lattice.width()));
        GL20C.glUniform1i(uProbeMask, PROBES - 1);
        GL20C.glUniform1f(uReach, REACH);
        startTimer();
        return saved;
    }

    private static void restore(int[] saved) {
        for (int unit = 0; unit < 2; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, saved[2 + unit]);
            int base = 5 + unit * 6;
            if (saved[base] != 0) {
                GL42C.glBindImageTexture(unit, saved[base], saved[base + 1], saved[base + 2] != 0, saved[base + 3], saved[base + 4], saved[base + 5]);
            } else {
                GL42C.glBindImageTexture(unit, 0, 0, false, 0, GL15C.GL_READ_ONLY, GL11C.GL_RGBA8);
            }
        }
        GL13C.glActiveTexture(saved[1]);
        GL20C.glUseProgram(saved[0]);
    }

    private static void startTimer() {
        if (!pending[nextQuery]) {
            GL33C.glQueryCounter(queries[nextQuery * 2], GL33C.GL_TIMESTAMP);
        }
    }

    private static void endTimer() {
        if (!pending[nextQuery]) {
            GL33C.glQueryCounter(queries[nextQuery * 2 + 1], GL33C.GL_TIMESTAMP);
            pending[nextQuery] = true;
            nextQuery = (nextQuery + 1) % pending.length;
        }
    }

    private static void readTimer() {
        for (int i = 0; i < pending.length; i++) {
            if (pending[i] && GL15C.glGetQueryObjecti(queries[i * 2 + 1], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
                gpuNanos = GL33C.glGetQueryObjectui64(queries[i * 2 + 1], GL15C.GL_QUERY_RESULT)
                        - GL33C.glGetQueryObjectui64(queries[i * 2], GL15C.GL_QUERY_RESULT);
                pending[i] = false;
            }
        }
    }

    /** For the status line. */
    static String status() {
        if (!available) {
            return "openness off (" + (problem == null ? "not started" : problem) + ")";
        }
        return String.format("openness %d of %d sections%s, %d baked last frame%s", ready, offsets.length / 3,
                waiting > 0 ? " (" + waiting + " waiting)" : "", baked,
                gpuNanos < 0 ? "" : String.format(" (last bake %.2f ms GPU)", gpuNanos / 1.0e6));
    }

    private static int[] offsets() {
        int[] all = new int[SECTIONS * SECTIONS * SECTIONS * 3];
        Integer[] order = new Integer[SECTIONS * SECTIONS * SECTIONS];
        int n = 0;
        for (int y = -SECTIONS / 2; y < SECTIONS / 2; y++) {
            for (int z = -SECTIONS / 2; z < SECTIONS / 2; z++) {
                for (int x = -SECTIONS / 2; x < SECTIONS / 2; x++) {
                    all[n * 3] = x;
                    all[n * 3 + 1] = y;
                    all[n * 3 + 2] = z;
                    order[n] = n;
                    n++;
                }
            }
        }
        Arrays.sort(order, (a, b) -> Integer.compare(distance(all, a), distance(all, b)));
        int[] sorted = new int[all.length];
        for (int i = 0; i < order.length; i++) {
            System.arraycopy(all, order[i] * 3, sorted, i * 3, 3);
        }
        return sorted;
    }

    private static int distance(int[] all, int i) {
        int x = all[i * 3] * 2 + 1;
        int y = all[i * 3 + 1] * 2 + 1;
        int z = all[i * 3 + 2] * 2 + 1;
        return x * x + y * y + z * z;
    }
}
