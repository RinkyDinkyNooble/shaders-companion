package com.rinkynooble.shaderscompanion.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Keeps the shadows of what the mirror view shows in the shader pack's shadow map.
 * <p>
 * Oculus leaves out of the shadow pass whatever can't cast a shadow into the main view (its advanced shadow culling,
 * the default for packs). Surfaces behind the player, which only the mirror view shows, would then be lit without
 * shadows, and gain and lose them as the view turns. While a mirror view is drawn, a box that can cast a shadow into
 * the mirror view also counts as visible to the shadow pass (ShadowCullingMixin asks {@link #casts}).
 * <p>
 * The test: the part of the mirror view's frustum in front of the mirror, stretched towards the light without end, as
 * an intersection of half spaces. A plane of the frustum stays when moving towards the light never crosses it to the
 * outside. Every other plane is replaced, together with each plane that stays, by the plane through the line where
 * the two meet that runs along the light's direction: for inward normals a (stays) and b (replaced) with the light
 * direction L, that is (a.L) b - (b.L) a, which holds for every point of the frustum and is parallel to L.
 */
public final class MirrorShadows {
    private static final int FRUSTUM_PLANES = 6;
    /** At most 6 planes stay and 3 x 3 pairs are added. */
    private static final int MAX_PLANES = 16;

    private static final float[] planes = new float[MAX_PLANES * 4];
    private static int planeCount;
    private static boolean active;
    private static boolean stale;
    private static final float[] frustum = new float[FRUSTUM_PLANES * 4];
    private static final Vector3f builtFor = new Vector3f();
    private static int kept;
    /** Boxes the last frame's shadow pass kept for the mirror view (for the status line). */
    static int keptLastFrame;

    private MirrorShadows() {
    }

    /**
     * Sets this frame's mirror view.
     *
     * @param viewProjection the view's matrix for points relative to the camera
     * @param normal         the mirror plane's normal, towards the camera
     * @param heightAbove    the camera's height above the plane
     */
    static void set(Matrix4f viewProjection, Vector3f normal, float heightAbove) {
        Vector4f plane = new Vector4f();
        for (int i = 0; i < FRUSTUM_PLANES; i++) {
            if (i == Matrix4f.PLANE_NZ) {
                // In place of the view's near plane: the mirror, which cuts away everything behind it
                plane.set(normal.x, normal.y, normal.z, heightAbove);
            } else {
                viewProjection.frustumPlane(i, plane);
            }
            frustum[i * 4] = plane.x;
            frustum[i * 4 + 1] = plane.y;
            frustum[i * 4 + 2] = plane.z;
            frustum[i * 4 + 3] = plane.w;
        }
        active = true;
        stale = true;
        keptLastFrame = kept;
        kept = 0;
    }

    /** No mirror view this frame: the shadow pass culls as Oculus does. */
    static void clear() {
        active = false;
        keptLastFrame = 0;
        kept = 0;
    }

    /**
     * Whether a box, relative to the camera, can cast a shadow onto something the mirror view shows.
     *
     * @param light the direction to the shadow light, of length 1
     */
    public static boolean casts(Vector3f light, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (!active) {
            return false;
        }
        if (stale || !builtFor.equals(light)) {
            build(light);
        }
        for (int i = 0; i < planeCount; i++) {
            float a = planes[i * 4];
            float b = planes[i * 4 + 1];
            float c = planes[i * 4 + 2];
            if (a * (a < 0.0F ? minX : maxX) + b * (b < 0.0F ? minY : maxY) + c * (c < 0.0F ? minZ : maxZ) + planes[i * 4 + 3] < 0.0F) {
                return false;
            }
        }
        kept++;
        return true;
    }

    private static void build(Vector3f light) {
        builtFor.set(light);
        stale = false;
        planeCount = 0;
        float[] towardsLight = new float[FRUSTUM_PLANES];
        for (int i = 0; i < FRUSTUM_PLANES; i++) {
            towardsLight[i] = frustum[i * 4] * light.x + frustum[i * 4 + 1] * light.y + frustum[i * 4 + 2] * light.z;
            if (towardsLight[i] >= 0.0F) {
                add(frustum[i * 4], frustum[i * 4 + 1], frustum[i * 4 + 2], frustum[i * 4 + 3]);
            }
        }
        for (int stays = 0; stays < FRUSTUM_PLANES; stays++) {
            if (towardsLight[stays] < 0.0F) {
                continue;
            }
            for (int replaced = 0; replaced < FRUSTUM_PLANES; replaced++) {
                if (towardsLight[replaced] >= 0.0F) {
                    continue;
                }
                float s = towardsLight[stays];
                float r = towardsLight[replaced];
                add(s * frustum[replaced * 4] - r * frustum[stays * 4],
                        s * frustum[replaced * 4 + 1] - r * frustum[stays * 4 + 1],
                        s * frustum[replaced * 4 + 2] - r * frustum[stays * 4 + 2],
                        s * frustum[replaced * 4 + 3] - r * frustum[stays * 4 + 3]);
            }
        }
    }

    /** Adds a plane, scaled to a normal of length 1; a pair of planes facing each other along the light gives none. */
    private static void add(float a, float b, float c, float d) {
        float length = (float) Math.sqrt(a * a + b * b + c * c);
        if (length < 1.0E-6F || planeCount == MAX_PLANES) {
            return;
        }
        int at = planeCount++ * 4;
        planes[at] = a / length;
        planes[at + 1] = b / length;
        planes[at + 2] = c / length;
        planes[at + 3] = d / length;
    }
}
