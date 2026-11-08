package com.rinkynooble.shaderscompanion.client;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import net.minecraftforge.fml.ModList;

/**
 * Guards {@link DistantHorizonsLods}: its class refers to Distant Horizons' classes, so it is only touched when
 * Distant Horizons is installed, and it is turned off for the session after any error.
 */
final class Lods {
    private static int state; // 0 unchecked, 1 available, -1 unavailable

    private Lods() {
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
            state = -1;
            ShadersCompanion.LOGGER.error("Shaders Companion: Distant Horizons terrain turned off after an error", t);
        }
    }
}
