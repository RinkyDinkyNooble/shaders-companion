package com.rinkynooble.shaderscompanion.client;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Material classes for items (held items, and every other item Oculus draws with an item id). Oculus gives an item it
 * draws the id the shader pack's item.properties lists for it ({@code currentRenderedItemId}); a block item takes its
 * block's default state id from the block map instead, so held blocks already carry their block's class. Every other
 * item with a class gets {@code 16384 | class} here (or the pack's flag-range id with the class in its low bits), from
 * the user's overrides ({@link MaterialRules}), the jar's item table
 * (vanilla items and tech mods'), then inference: a tool's tier or an armour piece's material (iron, gold, diamond,
 * netherite; a modded one by its repair ingredient's ingot or gem tag), metal ingots, nuggets, plates, gears and rods by
 * their forge tags, and guns (an item class named ...GunItem: Superb Warfare, TaCZ). Names are not read, so golden apples
 * and other food named after a metal stay out.
 */
public final class ItemMaterials {
    private static final String TABLE = "/assets/shaderscompanion/materials/items.json";
    /** Forge item tag folders whose metal (forge:ingots/iron ...) names the item's class. */
    private static final String[] METAL_TAG_FOLDERS = {"ingots", "nuggets", "plates", "gears", "rods"};
    private static final String[] GEM_TAGS = {"diamond", "emerald", "amethyst"};

    private static volatile Object2IntOpenHashMap<NamespacedId> classes = new Object2IntOpenHashMap<>();
    private static volatile boolean active;
    static String status = "";

    private ItemMaterials() {
    }

    /** The block merge didn't run for this pipeline: items get no classes. */
    static void clear() {
        active = false;
        classes = new Object2IntOpenHashMap<>();
        status = "";
    }

    /**
     * Oculus is setting a pipeline's item id map: returns it with the item classes in when this pipeline's pack reads the
     * material table, as it is otherwise.
     */
    public static Object2IntFunction<NamespacedId> wrap(Object2IntFunction<NamespacedId> packIds) {
        if (!active || packIds == null) {
            return packIds;
        }
        Object2IntOpenHashMap<NamespacedId> table = classes;
        if (table.isEmpty()) {
            return packIds;
        }
        Object2IntMap<NamespacedId> listed = packIds instanceof Object2IntMap<NamespacedId> map ? map : null;
        return new Object2IntFunction<>() {
            @Override
            public int getInt(Object key) {
                int material = table.getInt(key);
                if (material <= 0) {
                    return packIds.getInt(key);
                }
                if (listed != null && listed.containsKey(key)) {
                    int id = listed.getInt(key);
                    return id >= Materials.FLAG_RANGE && (id & Materials.CLASS_MASK) == 0 ? id | material : id;
                }
                return Materials.FLAG_RANGE | material;
            }

            @Override
            public int defaultReturnValue() {
                return packIds.defaultReturnValue();
            }
        };
    }

    /** Works out every item's class (called from the block merge, which runs first in the same pipeline). */
    static void build(List<String> problems, List<MaterialRules.Rule> user) {
        long start = System.nanoTime();
        Reference2IntOpenHashMap<Item> tagged = taggedItems();
        Reference2IntOpenHashMap<Item> fixed = new Reference2IntOpenHashMap<>();
        int fromTable = MaterialRules.apply(MaterialRules.resource(TABLE, MaterialRules.Kind.ITEM, "the item table", problems), MaterialRules.Kind.ITEM,
                BuiltInRegistries.ITEM, fixed, true, problems);
        int overrides = MaterialRules.apply(user, MaterialRules.Kind.ITEM, BuiltInRegistries.ITEM, fixed, false, problems);
        Object2IntOpenHashMap<NamespacedId> built = new Object2IntOpenHashMap<>();
        built.defaultReturnValue(0);
        int itemsFixed = 0;
        Map<String, List<String>> inferred = new java.util.TreeMap<>();
        for (Map.Entry<net.minecraft.resources.ResourceKey<Item>, Item> entry : ForgeRegistries.ITEMS.getEntries()) {
            Item item = entry.getValue();
            ResourceLocation key = entry.getKey().location();
            // Oculus draws a block item with its block's id (the powder snow bucket aside)
            if (item instanceof BlockItem && !(item instanceof SolidBucketItem)) {
                continue;
            }
            boolean isFixed = fixed.containsKey(item);
            int material = isFixed ? fixed.getInt(item) : infer(item, tagged);
            if (material <= 0) {
                continue;
            }
            built.put(new NamespacedId(key.getNamespace(), key.getPath()), material);
            if (isFixed) {
                itemsFixed++;
            } else {
                inferred.computeIfAbsent(Materials.className(material), k -> new ArrayList<>()).add(key.toString());
            }
        }
        int itemsInferred = 0;
        for (List<String> names : inferred.values()) {
            itemsInferred += names.size();
        }
        classes = built;
        active = true;
        status = String.format("%d items given a class: %d from the item table (%d entries%s), %d inferred; %.0f ms",
                built.size(), itemsFixed, fromTable, overrides > 0 ? ", " + overrides + " overrides" : "", itemsInferred, (System.nanoTime() - start) / 1.0e6);
        ShadersCompanion.LOGGER.info("Shaders Companion materials: {}", status);
        for (Map.Entry<String, List<String>> entry : inferred.entrySet()) {
            ShadersCompanion.LOGGER.info("Shaders Companion materials: inferred item {} ({}): {}", entry.getKey(), entry.getValue().size(), String.join(", ", entry.getValue()));
        }
    }

    /** An item's class from its tier, armour material, class name or tags (tagged); 0 when none says. */
    static int infer(Item item, Reference2IntOpenHashMap<Item> tagged) {
        if (item.isEdible()) {
            return 0;
        }
        try {
            if (item instanceof TieredItem tiered) {
                return tierClass(tiered.getTier(), tagged);
            }
            if (item instanceof ArmorItem armor) {
                return armorClass(armor.getMaterial(), tagged);
            }
        } catch (Throwable t) {
            // A modded tier or material that can't be read here
            return 0;
        }
        for (Class<?> type = item.getClass(); type != null && type != Item.class; type = type.getSuperclass()) {
            if (type.getSimpleName().endsWith("GunItem")) {
                return Materials.classNumber("metal");
            }
        }
        return tagged.getInt(item);
    }

    private static int tierClass(Tier tier, Reference2IntOpenHashMap<Item> tagged) {
        if (tier == Tiers.WOOD || tier == Tiers.STONE) {
            return 0;
        }
        if (tier == Tiers.IRON) {
            return Materials.classNumber("iron");
        }
        if (tier == Tiers.GOLD) {
            return Materials.classNumber("gold");
        }
        if (tier == Tiers.DIAMOND) {
            return Materials.classNumber("gem");
        }
        if (tier == Tiers.NETHERITE) {
            return Materials.classNumber("dark_metal");
        }
        return ingredientClass(tier.getRepairIngredient(), tagged);
    }

    private static int armorClass(ArmorMaterial material, Reference2IntOpenHashMap<Item> tagged) {
        if (material == ArmorMaterials.LEATHER || material == ArmorMaterials.TURTLE) {
            return 0;
        }
        if (material == ArmorMaterials.IRON || material == ArmorMaterials.CHAIN) {
            return Materials.classNumber("iron");
        }
        if (material == ArmorMaterials.GOLD) {
            return Materials.classNumber("gold");
        }
        if (material == ArmorMaterials.DIAMOND) {
            return Materials.classNumber("gem");
        }
        if (material == ArmorMaterials.NETHERITE) {
            return Materials.classNumber("dark_metal");
        }
        return ingredientClass(material.getRepairIngredient(), tagged);
    }

    /** A modded tier or armour material: the class its repair ingredient's ingot or gem tag names, when all agree. */
    private static int ingredientClass(Ingredient ingredient, Reference2IntOpenHashMap<Item> tagged) {
        if (ingredient == null) {
            return 0;
        }
        int chosen = 0;
        for (ItemStack stack : ingredient.getItems()) {
            int material = tagged.getInt(stack.getItem());
            if (material == 0 || chosen != 0 && material != chosen) {
                return 0;
            }
            chosen = material;
        }
        return chosen;
    }

    /** Items in the forge metal and gem tags, with the class each tag names (forge:ingots/iron -> iron ...). */
    private static Reference2IntOpenHashMap<Item> taggedItems() {
        Reference2IntOpenHashMap<Item> tagged = new Reference2IntOpenHashMap<>();
        tagged.defaultReturnValue(0);
        for (Map.Entry<String, String> entry : Materials.STORAGE_TAGS.entrySet()) {
            String material = entry.getValue();
            if (material.equals("gem") || material.equals("glazed")) {
                continue;
            }
            for (String folder : METAL_TAG_FOLDERS) {
                addTagged(tagged, tag(folder + "/" + entry.getKey()), Materials.classNumber(material));
            }
        }
        for (String gem : GEM_TAGS) {
            addTagged(tagged, tag("gems/" + gem), Materials.classNumber("gem"));
        }
        return tagged;
    }

    private static void addTagged(Reference2IntOpenHashMap<Item> tagged, TagKey<Item> tag, int material) {
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
            tagged.putIfAbsent(holder.value(), material);
        }
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, new ResourceLocation("forge", path));
    }
}
