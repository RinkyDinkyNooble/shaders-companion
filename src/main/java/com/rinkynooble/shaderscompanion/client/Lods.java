package com.rinkynooble.shaderscompanion.client;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import net.minecraftforge.fml.ModList;

/**
 * Guards {@link DistantHorizonsLods}: its class refers to Distant Horizons' classes, so it is only touched when
 * Distant Horizons is installed, and it is turned off for the session after any error. Also answers the far terrain's
 * coexistence questions (research/lod/M3-PLAN.md, "Coexistence"): whether Distant Horizons draws far terrain, whether
 * a Voxy mod is loaded.
 */
public final class Lods {
    /** Mod ids a Voxy build may use (Voxy's Modrinth slug first; no public page states the id). */
    private static final String[] VOXY_IDS = {"voxy", "neovoxy", "neo_voxy"};
    private static int state; // 0 unchecked, 1 available, -1 unavailable
    private static String voxy;
    private static boolean voxyChecked;

    private Lods() {
    }

    /**
     * Whether Distant Horizons draws far terrain on screen: installed and its rendering on, by its API (its renderer
     * mode; the setting Oculus defines DISTANT_HORIZONS by). Unknown (its config not ready yet) counts as drawing.
     */
    public static boolean distantHorizonsDrawing() {
        if (!ModList.get().isLoaded("distanthorizons")) {
            return false;
        }
        try {
            Boolean on = DistantHorizonsLods.renderingEnabled();
            return on == null || on;
        } catch (Throwable t) {
            return true;
        }
    }

    /** The id of a loaded Voxy mod, or null. */
    public static String voxyLoaded() {
        if (!voxyChecked) {
            voxyChecked = true;
            for (String id : VOXY_IDS) {
                if (ModList.get().isLoaded(id)) {
                    voxy = id;
                    break;
                }
            }
        }
        return voxy;
    }

    static boolean available() {
        if (state == 0) {
            state = ModList.get().isLoaded("distanthorizons") ? 1 : -1;
        }
        return state > 0;
    }

    static boolean collect(Object mcLevel) {
        DistantHorizonsLods.containersDrawn = 0;
        return DistantHorizonsLods.collect(mcLevel);
    }

    static int range() {
        return DistantHorizonsLods.range();
    }

    static int draw(BoxTest seen, boolean opaque, double cameraX, double cameraY, double cameraZ, double originX, double originY, double originZ,
                    double maxDistanceSq, double clipDistance, int worldHeight, int uModelOffset) {
        return DistantHorizonsLods.draw(seen, opaque, cameraX, cameraY, cameraZ, originX, originY, originZ, maxDistanceSq, clipDistance, worldHeight,
                uModelOffset);
    }

    static String reason() {
        available();
        return state > 0 ? DistantHorizonsLods.reason : state < 0 && ModList.get().isLoaded("distanthorizons") ? "turned off after an error" : "not installed";
    }

    static int containersDrawn() {
        return DistantHorizonsLods.containersDrawn;
    }

    static void clear() {
        if (state > 0) {
            DistantHorizonsLods.clear();
        }
    }

    static void fail(Throwable t) {
        if (state > 0) {
            // Lets go of the frame's collected buffers: clear() won't reach them once the state is off
            DistantHorizonsLods.clear();
            state = -1;
            ShadersCompanion.LOGGER.error("Shaders Companion: Distant Horizons terrain turned off after an error", t);
        }
    }
}
