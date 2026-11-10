package com.rinkynooble.shaderscompanion.client;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/**
 * The GPU time of the mod's drawing per frame, for the status line: a timer query around each frame's drawing, read
 * when its result is ready a few frames later, so reading never waits for the GPU.
 */
final class GpuTimer {
    private static final int COUNT = 4;
    private static final int[] queries = new int[COUNT];
    private static final boolean[] pending = new boolean[COUNT];
    private static int next;
    private static int running = -1;
    /** The latest finished measurement in nanoseconds, -1 before the first. */
    static long nanos = -1;

    private GpuTimer() {
    }

    /** Collects finished measurements and starts this frame's, unless its query still waits for a result. */
    static void begin() {
        if (queries[0] == 0) {
            GL15C.glGenQueries(queries);
        }
        for (int i = 0; i < COUNT; i++) {
            if (pending[i] && GL15C.glGetQueryObjecti(queries[i], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
                nanos = GL33C.glGetQueryObjectui64(queries[i], GL15C.GL_QUERY_RESULT);
                pending[i] = false;
            }
        }
        if (pending[next]) {
            return;
        }
        GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, queries[next]);
        pending[next] = true;
        running = next;
    }

    /** Ends this frame's measurement, if one was started. */
    static void end() {
        if (running >= 0) {
            GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
            next = (running + 1) % COUNT;
            running = -1;
        }
    }
}
