package com.rinkynooble.shaderscompanion.client;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.option.values.OptionValues;

/**
 * On/off options of the shader pack in use that the mod itself acts on (the pack declares them, the mod reads them).
 * Oculus loads the pack again when its options change, so the values read here are the applied ones.
 */
final class PackOptions {
    private PackOptions() {
    }

    /** The option's value in the pack in use; false without a pack, or when the pack has no such option. */
    static boolean on(String name) {
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (pack == null) {
            return false;
        }
        OptionValues values = pack.getShaderPackOptions().getOptionValues();
        return values.getOptionSet().getBooleanOptions().containsKey(name) && values.getBooleanValueOrDefault(name);
    }
}
