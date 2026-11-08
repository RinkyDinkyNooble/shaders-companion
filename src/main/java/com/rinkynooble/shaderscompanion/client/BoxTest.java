package com.rinkynooble.shaderscompanion.client;

/** Whether a view can see a box, with coordinates relative to this frame's camera. */
@FunctionalInterface
interface BoxTest {
    BoxTest NONE = (minX, minY, minZ, maxX, maxY, maxZ) -> false;

    boolean visible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

    default BoxTest or(BoxTest other) {
        if (this == NONE) {
            return other;
        }
        if (other == NONE) {
            return this;
        }
        return (minX, minY, minZ, maxX, maxY, maxZ) -> this.visible(minX, minY, minZ, maxX, maxY, maxZ)
                || other.visible(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
