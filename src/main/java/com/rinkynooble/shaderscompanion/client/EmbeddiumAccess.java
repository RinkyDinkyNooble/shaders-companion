package com.rinkynooble.shaderscompanion.client;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Map;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;

/**
 * Reads Embeddium's private renderer fields by reflection. A mixin accessor would work too, but Embeddium logs any
 * mod mixin into its classes as an error ("instance is now tainted"), and reading needs no change to its code.
 */
final class EmbeddiumAccess {
    private static final Field RENDER_SECTION_MANAGER = field(SodiumWorldRenderer.class, "renderSectionManager");
    private static final Field REGIONS = field(RenderSectionManager.class, "regions");
    private static final Field CHUNK_RENDERER = field(RenderSectionManager.class, "chunkRenderer");
    private static final Field REBUILD_LISTS = field(RenderSectionManager.class, "rebuildLists");

    private EmbeddiumAccess() {
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Embeddium field " + owner.getSimpleName() + "." + name + " not found", e);
        }
    }

    static RenderSectionManager sectionManager(SodiumWorldRenderer renderer) throws IllegalAccessException {
        return (RenderSectionManager) RENDER_SECTION_MANAGER.get(renderer);
    }

    static RenderRegionManager regions(RenderSectionManager sections) throws IllegalAccessException {
        return (RenderRegionManager) REGIONS.get(sections);
    }

    static ChunkRenderer chunkRenderer(RenderSectionManager sections) throws IllegalAccessException {
        return (ChunkRenderer) CHUNK_RENDERER.get(sections);
    }

    @SuppressWarnings("unchecked")
    static Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists(RenderSectionManager sections) throws IllegalAccessException {
        return (Map<ChunkUpdateType, ArrayDeque<RenderSection>>) REBUILD_LISTS.get(sections);
    }
}
