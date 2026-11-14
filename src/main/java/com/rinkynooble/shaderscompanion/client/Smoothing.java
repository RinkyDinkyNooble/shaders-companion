package com.rinkynooble.shaderscompanion.client;

/** Exponential smoothing by time, so a smoothed value settles as fast at any frame rate. */
final class Smoothing {
    private Smoothing() {
    }

    /** How far a value smoothed with a time constant moves towards its target in a step of this many seconds. */
    static float follow(float seconds, float timeConstant) {
        return 1.0F - (float) Math.exp(-seconds / timeConstant);
    }
}
