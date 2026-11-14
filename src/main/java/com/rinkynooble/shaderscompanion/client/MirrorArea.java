package com.rinkynooble.shaderscompanion.client;

import org.joml.Matrix4f;

/**
 * The part of the mirror view that is drawn: where the mirror's plane is on screen, with a margin (mirror protocol 2;
 * elsewhere the view's distance texture holds -1). It grows at once and shrinks smoothly, so the rays' changing spots
 * don't make it flicker. Positions are in screen halves (see {@link MirrorPlaneSearch}); the view
 * reaches {@code viewMargin} past the screen's edges.
 */
final class MirrorArea {
    /** The drawn part reaches this many grid cells past the cells whose rays hit the plane. */
    private static final float MARGIN_CELLS = 1.5F;
    /** When the plane covers less, the drawn part shrinks towards it with this time constant (it grows at once). */
    private static final float SHRINK_SECONDS = 0.037F;

    private final float viewMargin;
    private final float[] covered = new float[4];
    private final int[] pixels = new int[4];
    /** The drawn part, in screen halves (-viewMargin to viewMargin), for the plane it was found for. */
    private float minX;
    private float minY;
    private float maxX;
    private float maxY;
    private long plane = MirrorPlaneSearch.NO_PLANE;

    MirrorArea(float viewMargin) {
        this.viewMargin = viewMargin;
        this.minX = -viewMargin;
        this.minY = -viewMargin;
        this.maxX = viewMargin;
        this.maxY = viewMargin;
    }

    /**
     * Moves the area to the part of the screen this frame's plane covers (the whole view when no ray hit it).
     *
     * @param seconds the time since the last frame
     */
    void update(long framePlane, float seconds) {
        float newMinX;
        float newMinY;
        float newMaxX;
        float newMaxY;
        if (MirrorPlaneSearch.coveredArea(framePlane, MARGIN_CELLS, this.covered)) {
            // Reaching the screen's edge, it takes in the view's margin past it too
            newMinX = this.covered[0] <= -1.0F ? -this.viewMargin : this.covered[0];
            newMinY = this.covered[1] <= -1.0F ? -this.viewMargin : this.covered[1];
            newMaxX = this.covered[2] >= 1.0F ? this.viewMargin : this.covered[2];
            newMaxY = this.covered[3] >= 1.0F ? this.viewMargin : this.covered[3];
        } else {
            newMinX = newMinY = -this.viewMargin;
            newMaxX = newMaxY = this.viewMargin;
        }
        if (this.plane != framePlane) {
            this.plane = framePlane;
            this.minX = newMinX;
            this.minY = newMinY;
            this.maxX = newMaxX;
            this.maxY = newMaxY;
            return;
        }
        float shrink = Smoothing.follow(seconds, SHRINK_SECONDS);
        this.minX = newMinX < this.minX ? newMinX : this.minX + (newMinX - this.minX) * shrink;
        this.minY = newMinY < this.minY ? newMinY : this.minY + (newMinY - this.minY) * shrink;
        this.maxX = newMaxX > this.maxX ? newMaxX : this.maxX + (newMaxX - this.maxX) * shrink;
        this.maxY = newMaxY > this.maxY ? newMaxY : this.maxY + (newMaxY - this.maxY) * shrink;
    }

    // The area in the view's clip space (-1 to 1 across the view)

    private float x0() {
        return this.minX / this.viewMargin;
    }

    private float x1() {
        return this.maxX / this.viewMargin;
    }

    private float y0() {
        return this.minY / this.viewMargin;
    }

    private float y1() {
        return this.maxY / this.viewMargin;
    }

    /** A matrix that takes the view's clip space to the area's: the area fills -1 to 1, the rest lies outside. */
    Matrix4f crop() {
        float x0 = this.x0();
        float x1 = this.x1();
        float y0 = this.y0();
        float y1 = this.y1();
        return new Matrix4f().scaling(2.0F / (x1 - x0), 2.0F / (y1 - y0), 1.0F).translate(-(x0 + x1) * 0.5F, -(y0 + y1) * 0.5F, 0.0F);
    }

    /** Sets the area's pixels in a view of this size (x, y, width, height), read with {@link #pixels()}. */
    void setPixels(int width, int height) {
        this.pixels[0] = Math.max(0, (int) Math.floor((this.x0() * 0.5F + 0.5F) * width));
        this.pixels[1] = Math.max(0, (int) Math.floor((this.y0() * 0.5F + 0.5F) * height));
        this.pixels[2] = Math.min(width, (int) Math.ceil((this.x1() * 0.5F + 0.5F) * width)) - this.pixels[0];
        this.pixels[3] = Math.min(height, (int) Math.ceil((this.y1() * 0.5F + 0.5F) * height)) - this.pixels[1];
    }

    /** The drawn pixels (x, y, width, height) as last set. */
    int[] pixels() {
        return this.pixels;
    }

    /** The drawn share of the view, in percent. */
    int drawnPercent(int width, int height) {
        return Math.round(100.0F * this.pixels[2] * this.pixels[3] / Math.max(1, width * height));
    }
}
