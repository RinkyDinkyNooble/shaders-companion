package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraftforge.fml.ModList;

/**
 * The far terrain's coexistence questions (research/lod/M3-PLAN.md, "Coexistence"): whether Distant Horizons draws far
 * terrain on screen, whether a Voxy mod is loaded. Only by mod id (Forge's ModList) and, for Distant Horizons' renderer
 * mode, by reflection on its public API, so the mod neither compiles against nor loads Distant Horizons' classes
 * (3.0.0: the views no longer draw its terrain, research/lod/M4.md).
 */
final class FarCoexistence {
    /** Mod ids a Voxy build may use (Voxy's Modrinth slug first; no public page states the id). */
    private static final String[] VOXY_IDS = {"voxy", "neovoxy", "neo_voxy"};
    private static String voxy;
    private static boolean voxyChecked;
    /** DhApi.Delayed.configs, then graphics(), renderingEnabled() and getValue() on the API's interfaces. */
    private static Field dhConfigs;
    private static Method dhGraphics;
    private static Method dhRenderingEnabled;
    private static Method dhValue;
    private static boolean dhFailureLogged;

    private FarCoexistence() {
    }

    /**
     * Whether Distant Horizons draws far terrain on screen: installed and its rendering on, by its API (its renderer
     * mode; the setting Oculus defines DISTANT_HORIZONS by). Unknown (its config not ready, or its API not as expected)
     * counts as drawing.
     */
    static boolean distantHorizonsDrawing() {
        if (!ModList.get().isLoaded("distanthorizons")) {
            return false;
        }
        try {
            if (dhConfigs == null) {
                dhConfigs = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("configs");
                dhGraphics = dhConfigs.getType().getMethod("graphics");
                dhRenderingEnabled = dhGraphics.getReturnType().getMethod("renderingEnabled");
                dhValue = dhRenderingEnabled.getReturnType().getMethod("getValue");
            }
            Object configs = dhConfigs.get(null);
            if (configs == null) {
                return true;
            }
            Object on = dhValue.invoke(dhRenderingEnabled.invoke(dhGraphics.invoke(configs)));
            return !(on instanceof Boolean b) || b;
        } catch (Throwable t) {
            if (!dhFailureLogged) {
                dhFailureLogged = true;
                ShadersCompanion.LOGGER.warn("Shaders Companion far terrain: can't ask Distant Horizons whether it draws far terrain ({}); taken as drawing", t.toString());
            }
            return true;
        }
    }

    /** The id of a loaded Voxy mod, or null. */
    static String voxyLoaded() {
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
}
