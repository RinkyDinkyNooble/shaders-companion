package com.rinkynooble.shaderscompanion.client;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Dev runs only (-PdevProbe=mekanism,superbwarfare): one line per block of those mods with the evidence the material
 * system's inference reads (sound type, shape, light, block entity, render shape, tool, map colour, hardness, tags),
 * what infer() gives it and the id it carries in the current pipeline's map, written to research/materials-probe.tsv.
 */
public final class MaterialsProbe {
    private MaterialsProbe() {
    }

    private static String constantName(Class<?> type, Object value) {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                try {
                    field.setAccessible(true);
                    if (field.get(null) == value) {
                        return field.getName();
                    }
                } catch (Exception e) {
                    // Unreadable field: skip it
                }
            }
        }
        return value == null ? "null" : "custom:" + value.getClass().getSimpleName();
    }

    public static void write(String namespaces, Path out) {
        List<String> wanted = List.of(namespaces.split(","));
        Object2IntMap<BlockState> ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
        List<String> lines = new ArrayList<>();
        lines.add("block\tsound\tfull\tnocollision\tlight\tblockentity\trendershape\tneedstool\tmineable\ttoollevel\tmapcolor\thardness\tinfer\tid\tclass\ttags");
        for (Map.Entry<net.minecraft.resources.ResourceKey<Block>, Block> entry : ForgeRegistries.BLOCKS.getEntries()) {
            ResourceLocation key = entry.getKey().location();
            if (!wanted.contains(key.getNamespace())) {
                continue;
            }
            Block block = entry.getValue();
            BlockState state = block.defaultBlockState();
            String sound = constantName(SoundType.class, state.getSoundType());
            boolean full;
            boolean noCollision;
            try {
                full = Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
                noCollision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
            } catch (Throwable t) {
                full = false;
                noCollision = false;
            }
            String tags = state.getTags().map(tag -> tag.location().toString()).sorted().collect(Collectors.joining(" "));
            List<String> mineable = new ArrayList<>();
            List<String> level = new ArrayList<>();
            for (String tag : tags.split(" ")) {
                if (tag.startsWith("minecraft:mineable/")) {
                    mineable.add(tag.substring("minecraft:mineable/".length()));
                } else if (tag.startsWith("minecraft:needs_") || tag.startsWith("forge:needs_")) {
                    level.add(tag.substring(tag.indexOf(':') + 1));
                }
            }
            String mapColor;
            try {
                MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                mapColor = constantName(MapColor.class, color) + "(" + color.id + ")";
            } catch (Throwable t) {
                mapColor = "?";
            }
            float hardness;
            try {
                hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            } catch (Throwable t) {
                hardness = Float.NaN;
            }
            int inferred = Materials.infer(block, key);
            int id = ids == null ? -2 : ids.getOrDefault(state, -1);
            int material = Materials.classOf(ids, state);
            lines.add(String.join("\t", key.toString(), sound, String.valueOf(full), String.valueOf(noCollision), String.valueOf(state.getLightEmission()),
                    String.valueOf(block instanceof EntityBlock), state.getRenderShape().name().toLowerCase(Locale.ROOT), String.valueOf(state.requiresCorrectToolForDrops()),
                    String.join(",", mineable), String.join(",", level), mapColor, String.valueOf(hardness),
                    inferred > 0 ? Materials.className(inferred) : "-", String.valueOf(id), material > 0 ? Materials.className(material) : "-", tags));
        }
        try {
            Files.write(out, lines, StandardCharsets.UTF_8);
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: wrote {} blocks of {} to {}", lines.size() - 1, namespaces, out.toAbsolutePath());
        } catch (Exception e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: can't write the materials probe", e);
        }
    }
}
