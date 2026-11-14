package com.rinkynooble.shaderscompanion.client;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/**
 * The GPU time of the mod's drawing per frame, for the status line: GPU timestamps before and after each frame's
 * drawing, read when they are ready a few frames later, so reading never waits for the GPU. Timestamps, unlike a
 * time-elapsed query, work while another program's time-elapsed query is running.
 */
final class GpuTimer {
    private static final int COUNT = 4;
    /** A pair of timestamp queries per slot: start and end. */
    private static final int[] queries = new int[COUNT * 2];
    private static final boolean[] pending = new boolean[COUNT];
    private static int next;
    private static int running = -1;
    /** The latest finished measurement in nanoseconds, -1 before the first. */
    static long nanos = -1;

    private GpuTimer() {
    }

    /** Collects finished measurements and starts this frame's, unless its slot still waits for a result. */
    static void begin() {
        if (queries[0] == 0) {
            GL15C.glGenQueries(queries);
        }
        // Oldest first (the slot after the last one started), so the newest finished measurement is the one kept
        for (int k = 0; k < COUNT; k++) {
            int i = (next + k) % COUNT;
            if (pending[i] && GL15C.glGetQueryObjecti(queries[i * 2 + 1], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
                nanos = GL33C.glGetQueryObjectui64(queries[i * 2 + 1], GL15C.GL_QUERY_RESULT)
                        - GL33C.glGetQueryObjectui64(queries[i * 2], GL15C.GL_QUERY_RESULT);
                pending[i] = false;
            }
        }
        if (pending[next]) {
            return;
        }
        GL33C.glQueryCounter(queries[next * 2], GL33C.GL_TIMESTAMP);
        running = next;
    }

    /** Ends this frame's measurement, if one was started. */
    static void end() {
        if (running >= 0) {
            GL33C.glQueryCounter(queries[running * 2 + 1], GL33C.GL_TIMESTAMP);
            pending[running] = true;
            next = (running + 1) % COUNT;
            running = -1;
        }
    }
}
