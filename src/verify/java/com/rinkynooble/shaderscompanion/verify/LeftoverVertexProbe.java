package com.rinkynooble.shaderscompanion.verify;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatDescription;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatRegistry;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializer;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializerRegistry;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;

/**
 * Dev only, for the Superb Warfare boat bug (HANDOFF). Wraps Oculus's converter from Minecraft's entity vertices to
 * its own longer layout (used when Embeddium's push writes a batch into an entity buffer with a shader pack on). That
 * converter writes only whole groups of 4 vertices and leaves the last {@code count % 4} slots as they were. This
 * counts the batches that leave slots, and with -PdevPoison fills those slots with the vertices that belonged there,
 * moved 6 blocks up and 3 to either side, so a triangle left with them shows as a spike in the model's colours, the
 * way stale bytes do in a busy game (a fresh client's zeroed memory puts them at the eye, where they clip to nothing).
 */
final class LeftoverVertexProbe {
    private static volatile boolean installed;
    private static volatile long batches;
    private static volatile long leftoverBatches;
    private static volatile long leftoverVertices;

    private LeftoverVertexProbe() {
    }

    static boolean installed() {
        return installed;
    }

    @SuppressWarnings("unchecked")
    static void install(boolean poison) throws ReflectiveOperationException {
        VertexFormatDescription src = VertexFormatRegistry.instance().get(DefaultVertexFormat.NEW_ENTITY);
        VertexFormatDescription dst = VertexFormatRegistry.instance().get(IrisVertexFormats.ENTITY);
        VertexSerializerRegistry registry = VertexSerializerRegistry.instance();
        Field cacheField = registry.getClass().getDeclaredField("cache");
        cacheField.setAccessible(true);
        Long2ReferenceMap<VertexSerializer> cache = (Long2ReferenceMap<VertexSerializer>) cacheField.get(registry);
        // Embeddium's key for a source and destination format (VertexSerializerRegistryImpl.createKey)
        long key = src.id() & 0xFFFFFFFFL | (dst.id() & 0xFFFFFFFFL) << 32;
        VertexSerializer original = cache.get(key);
        if (original == null) {
            throw new IllegalStateException("no converter registered from the entity format to Oculus's");
        }
        int stride = IrisVertexFormats.ENTITY.getVertexSize();
        cache.put(key, (srcPtr, dstPtr, count) -> {
            batches++;
            int rest = count % 4;
            if (rest != 0) {
                leftoverBatches++;
                leftoverVertices += rest;
            }
            original.serialize(srcPtr, dstPtr, count);
            if (rest != 0 && poison) {
                for (int i = count - rest; i < count; i++) {
                    // The vertex that should have been there (its texture, colour, light), moved 6 blocks up and
                    // 3 to one side, each slot to its own side, or a triangle left with two of them has no area
                    long v = dstPtr + (long) i * stride;
                    MemoryUtil.memCopy(srcPtr + 36L * i, v, 36);
                    MemoryUtil.memPutFloat(v, MemoryUtil.memGetFloat(v) + 6.0F * (i - (count - rest)) - 3.0F);
                    MemoryUtil.memPutFloat(v + 4, MemoryUtil.memGetFloat(v + 4) + 6.0F);
                }
            }
        });
        installed = true;
    }

    static String summary() {
        return installed ? "entity vertex conversions " + batches + ", with leftover slots " + leftoverBatches
                + " (" + leftoverVertices + " vertices)" : "leftover probe not installed";
    }
}
