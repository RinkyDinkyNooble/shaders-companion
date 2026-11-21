package com.rinkynooble.shaderscompanion.client;

import java.util.concurrent.atomic.LongAdder;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Minecraft;

/**
 * Whether smooth lighting leaves out light behind the closed sides of partial blocks ({@code AoFaceDataMixin}): on while
 * the {@code lightLeakFix} setting is on and a shader pack is in use. A change rebuilds the terrain, since the light is
 * baked into the chunk meshes.
 */
public final class LightLeaks {
    /** Read on Embeddium's chunk build threads. */
    public static volatile boolean active;

    /** Light samples left out since the game started, counted on the chunk build threads. */
    public static final LongAdder closedSamples = new LongAdder();

    /** Left-out samples still to log (dev checks set it). */
    public static final java.util.concurrent.atomic.AtomicInteger logBudget = new java.util.concurrent.atomic.AtomicInteger();

    private LightLeaks() {
    }

    /** Every client tick, the title screen included, so the setting is known before a world's first chunks are built. */
    static void tick(Minecraft mc) {
        boolean wanted = Config.LIGHT_LEAK_FIX.get() && IrisApi.getInstance().isShaderPackInUse();
        if (wanted != active) {
            active = wanted;
            if (mc.level != null) {
                mc.levelRenderer.allChanged();
            }
        }
    }

    static String status() {
        return (active ? "on" : Config.LIGHT_LEAK_FIX.get() ? "off (no shader pack in use)" : "off in the config")
                + ", " + closedSamples.sum() + " light samples left out behind closed sides";
    }
}
