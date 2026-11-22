package com.rinkynooble.shaderscompanion.verify;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/**
 * Dev-only per-pass profile (-PdevProfile): a GL timestamp and a CPU time at every mark the verify mixins set (Oculus's
 * shadow pass sections, the level's profiler sections and Oculus's phases, each deferred and composite program by
 * name, the final pass, the mod's own drawing), read back a few frames later. Each segment's GPU and CPU time goes to
 * the label of the mark that starts it; per frame the segments of a label are summed. Logged next to every timing line
 * as means and medians of the last 120 frames.
 */
public final class PassProfiler {
    public static final boolean ON = System.getProperty("shaderscompanion.devProfile") != null;
    private static final int RING = 6;
    private static final int MARKS = 512;
    private static final int FRAMES = 120;
    private static final int[][] queries = new int[RING][];
    private static final String[][] labels = new String[RING][MARKS];
    private static final long[][] cpuNanos = new long[RING][MARKS];
    private static final int[] count = new int[RING];
    private static final boolean[] pending = new boolean[RING];
    private static int frame;
    private static boolean inFrame;
    private static String current;
    private static String section = "start";
    private static String shadowSection = "setup";
    private static String phaseName = "NONE";
    private static boolean inShadow;
    /** GL program id to the pack's program name (set when Oculus builds each program). */
    public static final Map<Integer, String> PROGRAM_NAMES = new HashMap<>();
    /** Per label: GPU and CPU milliseconds of the last FRAMES frames (0 where the label had no segment). */
    private static final Map<String, double[][]> samples = new LinkedHashMap<>();
    private static final double[] frameGpu = new double[FRAMES];
    private static final double[] frameCpu = new double[FRAMES];
    private static int framesRead;

    private PassProfiler() {
    }

    public static void beginFrame() {
        if (!ON) {
            return;
        }
        if (queries[0] == null) {
            for (int i = 0; i < RING; i++) {
                queries[i] = new int[MARKS];
                GL15C.glGenQueries(queries[i]);
            }
        }
        int slot = frame % RING;
        if (pending[slot]) {
            read(slot);
        }
        count[slot] = 0;
        inFrame = true;
        current = null;
        mark("frame start (before level)");
    }

    public static void endFrame() {
        if (!ON || !inFrame) {
            return;
        }
        int slot = frame % RING;
        current = null;
        mark("end");
        pending[slot] = true;
        inFrame = false;
        frame++;
    }

    /** Starts a segment with this label (nothing when it is already the current one). */
    public static void mark(String label) {
        if (!ON || !inFrame || label.equals(current)) {
            return;
        }
        int slot = frame % RING;
        int n = count[slot];
        if (n >= MARKS) {
            return;
        }
        GL33C.glQueryCounter(queries[slot][n], GL33C.GL_TIMESTAMP);
        labels[slot][n] = label;
        cpuNanos[slot][n] = System.nanoTime();
        count[slot] = n + 1;
        current = label;
    }

    /** The level's profiler section (LevelRenderer.renderLevel) or, inside the shadow pass, the shadow pass's. */
    public static void section(String name) {
        if (inShadow) {
            shadowSection = name;
        } else {
            section = name;
        }
        markWorld();
    }

    public static void phase(String name) {
        phaseName = name;
        markWorld();
    }

    public static void levelStart() {
        section = "start";
        shadowSection = "setup";
        inShadow = false;
        markWorld();
    }

    public static void shadow(boolean begin) {
        inShadow = begin;
        shadowSection = "setup";
        markWorld();
    }

    /** Marks the current world drawing: the section and Oculus's phase. */
    public static void markWorld() {
        String phase = "NONE".equals(phaseName) ? "" : "/" + phaseName.toLowerCase();
        mark(inShadow ? "shadow " + shadowSection + phase : "level " + section + phase);
    }

    public static void program(int id, String kind) {
        String name = PROGRAM_NAMES.get(id);
        mark("pass " + (name == null ? kind + " #" + id : name));
    }

    private static void read(int slot) {
        int n = count[slot];
        pending[slot] = false;
        if (n < 2) {
            return;
        }
        long[] stamps = new long[n];
        for (int i = 0; i < n; i++) {
            stamps[i] = GL33C.glGetQueryObjecti64(queries[slot][i], GL15C.GL_QUERY_RESULT);
        }
        Map<String, double[]> sums = new HashMap<>();
        for (int i = 0; i + 1 < n; i++) {
            samples.computeIfAbsent(labels[slot][i], k -> new double[2][FRAMES]);
            double[] s = sums.computeIfAbsent(labels[slot][i], k -> new double[2]);
            s[0] += (stamps[i + 1] - stamps[i]) / 1.0E6;
            s[1] += (cpuNanos[slot][i + 1] - cpuNanos[slot][i]) / 1.0E6;
        }
        int at = framesRead % FRAMES;
        for (Map.Entry<String, double[][]> e : samples.entrySet()) {
            double[] s = sums.get(e.getKey());
            e.getValue()[0][at] = s == null ? 0.0 : s[0];
            e.getValue()[1][at] = s == null ? 0.0 : s[1];
        }
        frameGpu[at] = (stamps[n - 1] - stamps[0]) / 1.0E6;
        frameCpu[at] = (cpuNanos[slot][n - 1] - cpuNanos[slot][0]) / 1.0E6;
        framesRead++;
    }

    private static double median(double[] v, int n) {
        double[] s = Arrays.copyOf(v, n);
        Arrays.sort(s);
        return s[n / 2];
    }

    private static double mean(double[] v, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += v[i];
        }
        return sum / n;
    }

    /** Logs the profile of the last 120 frames read (labels below 0.01 ms of GPU and CPU left out). */
    public static void log(String title) {
        if (!ON || framesRead == 0) {
            return;
        }
        int n = Math.min(framesRead, FRAMES);
        StringBuilder b = new StringBuilder();
        b.append(String.format("Shaders Companion pass profile (%s), %d frames: GPU mean %.2f / median %.2f ms, CPU from frame start to end mean %.2f ms%n",
                title, n, mean(frameGpu, n), median(frameGpu, n), mean(frameCpu, n)));
        b.append(String.format("  %-58s %8s %8s %8s%n", "label", "GPU mean", "GPU med", "CPU mean"));
        for (Map.Entry<String, double[][]> e : samples.entrySet()) {
            double gpu = mean(e.getValue()[0], n);
            double cpu = mean(e.getValue()[1], n);
            if (gpu < 0.01 && cpu < 0.01) {
                continue;
            }
            b.append(String.format("  %-58s %8.3f %8.3f %8.3f%n", e.getKey(), gpu, median(e.getValue()[0], n), cpu));
        }
        ShadersCompanion.LOGGER.info(b.toString());
    }
}
