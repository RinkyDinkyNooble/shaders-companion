package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.FloatBuffer;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * One plane's mirror view, in one slot (tile) of {@link MirrorView}'s textures: the world seen from the camera mirrored
 * in the plane, drawn like the main view with everything on the far side of the plane cut away, so a point on the plane
 * lands on the same texel as on screen. Only the part of the screen the plane covers (with a margin) is drawn
 * ({@link MirrorArea}), and a plane that covers little of the screen is drawn at a lower resolution (mirrorLod), in the
 * lower left corner of its tile.
 */
final class MirrorPlaneView {
    /** Geometry closer than this to the plane, on its far side or on it (the mirror itself), is cut from the view. */
    private static final float CLIP_EPSILON = 0.01F;
    /** mirrorLod: the plane's share of the weighted screen below which the view drops to 75% and 50% resolution. */
    private static final float LOD_75_BELOW = 0.35F;
    private static final float LOD_50_BELOW = 0.15F;
    /** A lower level waits this long before it is used, a higher one LOD_UP_NANOS. */
    private static final long LOD_DOWN_NANOS = 1_000_000_000L;
    private static final long LOD_UP_NANOS = 200_000_000L;
    /** A newly mirrored plane's reflections fade in from the pack's own over this long (info texel 8). */
    private static final float FADE_IN_SECONDS = 0.15F;

    private final int slot;
    private final MirrorArea area = new MirrorArea(MirrorView.MARGIN);
    /** mirrorLod: the resolution in use (percent of the tile), a different one waiting, and since when. */
    private int lodPercent = 100;
    private int lodWaiting = 100;
    private long lodWaitingSince;

    // This frame's view, valid while active
    private boolean active;
    private int width;
    private int height;
    private int axis;
    private int side;
    private double coordinate;
    private double heightAbove;
    private final Vector3f normal = new Vector3f();
    private final Vector3f origin = new Vector3f();
    private Vec3 originWorld = Vec3.ZERO;
    private final Matrix4f drawMatrix = new Matrix4f();
    private final Matrix4f packMatrix = new Matrix4f();
    private final Matrix4f croppedMatrix = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final FrustumIntersection wholeFrustum = new FrustumIntersection();
    private float maxDistanceSq;

    MirrorPlaneView(int slot) {
        this.slot = slot;
    }

    boolean active() {
        return this.active;
    }

    void deactivate() {
        this.active = false;
    }

    /**
     * Sets up this frame's view of a plane, in a tile of {@code tileWidth} x {@code tileHeight} pixels.
     *
     * @param view       the main view's rotation (the pose the world is drawn with)
     * @param projection the main view's projection
     */
    void setUp(long newPlane, Vec3 cameraPos, Matrix4f view, Matrix4f projection, float near, float far, float maxDistance, int tileWidth, int tileHeight) {
        this.axis = MirrorPlaneSearch.axis(newPlane);
        this.side = MirrorPlaneSearch.side(newPlane);
        this.coordinate = MirrorPlaneSearch.coordinate(newPlane);
        this.heightAbove = MirrorPlaneSearch.heightOf(newPlane, cameraPos);
        this.normal.set(this.axis == 0 ? this.side : 0, this.axis == 1 ? this.side : 0, this.axis == 2 ? this.side : 0);
        // The camera mirrored in the plane, relative to the camera
        this.origin.set(this.normal).mul((float) (-2.0 * this.heightAbove));
        this.originWorld = cameraPos.add(this.origin.x, this.origin.y, this.origin.z);

        float tanY = MirrorView.MARGIN / projection.m11();
        float aspect = projection.m11() / projection.m00();
        Matrix4f mirror = new Matrix4f().scaling(this.axis == 0 ? -1.0F : 1.0F, this.axis == 1 ? -1.0F : 1.0F, this.axis == 2 ? -1.0F : 1.0F);
        // Positions relative to the mirrored camera: mirror them, then look at them as the main view does
        this.drawMatrix.setPerspective(2.0F * (float) Math.atan(tanY), aspect, near, far).mul(view).mul(mirror);
        this.packMatrix.set(this.drawMatrix).translate(-this.origin.x, -this.origin.y, -this.origin.z);
        this.area.update(newPlane, MirrorPlaneSearch.lastStep());
        // Culled to the drawn part, shadow casters too (Oculus's shadow pass runs after this, in the same frame)
        this.croppedMatrix.set(this.area.crop()).mul(this.packMatrix);
        this.frustum.set(this.croppedMatrix);
        this.wholeFrustum.set(this.packMatrix);
        this.maxDistanceSq = maxDistance * maxDistance;

        int lod = this.chooseLod();
        this.width = Math.max(16, tileWidth * lod / 100);
        this.height = Math.max(16, tileHeight * lod / 100);
        this.area.setPixels(this.width, this.height);
        this.active = true;
    }

    /** mirrorLod: the resolution for the plane's share of the screen (percent), changed only once it has held. */
    private int chooseLod() {
        float share = MirrorPlaneSearch.share(this.slot);
        int wanted = !Config.MIRROR_LOD.get() || share >= LOD_75_BELOW ? 100 : share >= LOD_50_BELOW ? 75 : 50;
        long now = System.nanoTime();
        if (wanted == this.lodPercent) {
            this.lodWaiting = wanted;
        } else if (wanted != this.lodWaiting) {
            this.lodWaiting = wanted;
            this.lodWaitingSince = now;
        } else if (now - this.lodWaitingSince >= (wanted > this.lodPercent ? LOD_UP_NANOS : LOD_DOWN_NANOS)) {
            this.lodPercent = wanted;
        }
        return this.lodPercent;
    }

    /** Keeps the shadows of what this view shows ({@link MirrorShadows}). */
    void addShadowVolume() {
        MirrorShadows.add(this.croppedMatrix, this.normal, (float) this.heightAbove);
    }

    /**
     * Draws this frame's view into its tile, whose left edge is at {@code tileX} in the bound target (cleared to "not
     * drawn"): the part of the view the plane covers, the rest left saying "not drawn". {@code behindTarget} (null for
     * none) gets the opaque surfaces copied before water and glass are drawn over them.
     */
    void draw(ViewTarget target, ViewTarget behindTarget, int tileX, Vec3 camera, float maxDistance, float lodDistance, RenderRegionManager regions) {
        GlStateManager._viewport(tileX, 0, this.width, this.height);
        int[] drawn = this.area.pixels();
        int x = tileX + drawn[0];
        GlStateManager._scissorBox(x, drawn[1], drawn[2], drawn[3]);
        // Inside the drawn part, 0 is open sky
        GL30C.glClearBufferfv(GL11C.GL_COLOR, ViewProgram.DISTANCE_OUTPUT, new float[]{0.0F, 0.0F, 0.0F, 0.0F});

        float[] cameraOffset = {(float) (camera.x - this.originWorld.x), (float) (camera.y - this.originWorld.y), (float) (camera.z - this.originWorld.z)};
        Runnable copyBehind = behindTarget == null ? null : () -> target.copyTo(behindTarget, x, drawn[1], drawn[2], drawn[3]);
        ViewRenderer.drawView(this.drawMatrix, this.seen(), this.frustumSeen(), camera, this.originWorld, this.clipPlane(), cameraOffset, maxDistance,
                lodDistance, regions, true, copyBehind);
    }

    /**
     * Boxes relative to the camera that the drawn part of the view can see: in its frustum, in front of the plane and
     * within the terrain range.
     */
    BoxTest seen() {
        return (minX, minY, minZ, maxX, maxY, maxZ) -> CubeView.nearestDistanceSq(minX, minY, minZ, maxX, maxY, maxZ) <= this.maxDistanceSq
                && this.inView(this.frustum, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** As {@link #seen()} for the whole view, not only its drawn part. */
    BoxTest seenWhole() {
        return (minX, minY, minZ, maxX, maxY, maxZ) -> CubeView.nearestDistanceSq(minX, minY, minZ, maxX, maxY, maxZ) <= this.maxDistanceSq
                && this.inView(this.wholeFrustum, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** As {@link #seen()} without the terrain range, for Distant Horizons' far terrain. */
    private BoxTest frustumSeen() {
        return (minX, minY, minZ, maxX, maxY, maxZ) -> this.inView(this.frustum, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private boolean inView(FrustumIntersection test, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double front = this.heightAbove + (this.side > 0 ? (this.axis == 0 ? maxX : this.axis == 1 ? maxY : maxZ)
                : -(this.axis == 0 ? minX : this.axis == 1 ? minY : minZ));
        return front > CLIP_EPSILON && test.testAab((float) minX, (float) minY, (float) minZ, (float) maxX, (float) maxY, (float) maxZ);
    }

    /** The cut plane for positions relative to the mirrored camera: drawn where dot(xyz, p) + w >= 0. */
    private float[] clipPlane() {
        return new float[]{this.normal.x, this.normal.y, this.normal.z, (float) -this.heightAbove - CLIP_EPSILON};
    }

    /**
     * Writes this view's row of the info texture (see API.md): header, plane, origin, matrix, ranges, fade-in and its
     * tile's corner, relative to this frame's camera.
     */
    void writeRow(FloatBuffer buffer, int frameCounter, float near, float far, float terrainRange, float lodRange, int tileX) {
        buffer.put(CubeTextures.MAGIC).put(CubeTextures.MIRROR_PROTOCOL).put(this.width).put(this.height);
        buffer.put(this.normal.x).put(this.normal.y).put(this.normal.z).put((float) this.heightAbove);
        buffer.put(this.origin.x).put(this.origin.y).put(this.origin.z).put(frameCounter % 4096);
        this.packMatrix.get(buffer.position(), buffer);
        buffer.position(buffer.position() + 16);
        buffer.put(near).put(far).put(terrainRange).put(lodRange);
        buffer.put(Math.min(1.0F, MirrorPlaneSearch.planeAge(this.slot) / FADE_IN_SECONDS)).put(tileX).put(0.0F).put(0.0F);
    }

    /** For the status line: the plane, its share, its size and how much of it is drawn. */
    String describe() {
        String name = this.axis == 0 ? "x" : this.axis == 1 ? "y" : "z";
        return String.format("%s = %.3f (%s side, share %.2f), %d x %d px (%d%% of it drawn)", name, this.coordinate, this.side > 0 ? "+" : "-",
                MirrorPlaneSearch.share(this.slot), this.width, this.height, this.area.drawnPercent(this.width, this.height));
    }
}
