package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The blocks the far terrain leaves out (config {@code farHiddenBlocks}): block ids, every block of a mod
 * ({@code modid:*}) and block tags ({@code #namespace:path}). {@link FarNodes#ingest} stores them as air lit by the
 * voxels around them, so they leave no hole of black faces. Made once per store (each world join), on the worker.
 */
final class FarBlockFilter {
    private final Set<ResourceLocation> ids = new HashSet<>();
    private final Set<String> namespaces = new HashSet<>();
    private final List<TagKey<Block>> tags = new ArrayList<>();

    /** The config's list (farHiddenBlocks), or none when it can't be read. */
    static FarBlockFilter fromConfig() {
        try {
            return new FarBlockFilter(com.rinkynooble.shaderscompanion.client.Config.FAR_HIDDEN_BLOCKS.get());
        } catch (RuntimeException e) {
            return new FarBlockFilter(List.of());
        }
    }

    FarBlockFilter(List<? extends String> entries) {
        List<String> unreadable = new ArrayList<>();
        for (String raw : entries) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(entry.substring(1));
                if (tag != null) {
                    tags.add(TagKey.create(Registries.BLOCK, tag));
                } else {
                    unreadable.add(entry);
                }
            } else if (entry.endsWith(":*")) {
                namespaces.add(entry.substring(0, entry.length() - 2));
            } else {
                ResourceLocation id = ResourceLocation.tryParse(entry);
                if (id != null) {
                    ids.add(id);
                } else {
                    unreadable.add(entry);
                }
            }
        }
        if (!unreadable.isEmpty()) {
            ShadersCompanion.LOGGER.warn("Shaders Companion far store: farHiddenBlocks entries that aren't block ids, modid:* or #tags (ignored): {}", unreadable);
        }
    }

    boolean isEmpty() {
        return ids.isEmpty() && namespaces.isEmpty() && tags.isEmpty();
    }

    /** Whether the far terrain leaves this state out (null, an unknown block, never). */
    boolean hides(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (ids.contains(id) || namespaces.contains(id.getNamespace())) {
            return true;
        }
        for (TagKey<Block> tag : tags) {
            try {
                if (state.is(tag)) {
                    return true;
                }
            } catch (RuntimeException e) {
                // Tags not bound yet: not hidden by this entry
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("%d block ids, %d mods, %d tags", ids.size(), namespaces.size(), tags.size());
    }
}
