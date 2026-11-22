package com.rinkynooble.shaderscompanion.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

/**
 * The material system: a LabPBR material class for every block that should look smooth or metallic (glass, ice,
 * polished stone, metals, gems ...), from the user's overrides ({@code config/shaderscompanion-materials.json}), the
 * tables in the jar (vanilla blocks, tech mods' machines), or, for other modded blocks, inference from the block's sound
 * type, tags and name (sources that disagree give nothing). The classes reach a shader pack that reads {@code shaderscompanion:material_table} through
 * Oculus's block ids: when Oculus builds a pipeline's block state to id map, each class goes into the free low bits
 * of Sundial's flag range (ids from 16384), so chunk meshes, the main view, the mod's views and the lattice all carry it.
 * The table holds each class's LabPBR bytes; the pack uses them where the resource pack has no PBR map of its own.
 */
public final class Materials {
    static final int PROTOCOL = 1;
    static final int TABLE_WIDTH = 512;
    static final String TABLE = "material_table";
    /** Sundial's flag range starts here (bit 14); bits 9-13 are its feature flags, bits 0-8 are free for the class. */
    static final int FLAG_RANGE = 1 << 14;
    static final int CLASS_MASK = 0x1FF;
    private static final String OVERRIDES = "shaderscompanion-materials.json";
    private static final String VANILLA = "/assets/shaderscompanion/materials/vanilla.json";
    /**
     * Blocks of tech mods inference can't read: Mekanism's machines sound like stone and have shaped models, Superb
     * Warfare's containers and radar are shaped and drawn by block entity renderers, and Ad Astra's metal blocks have
     * nothing but their sound to tell them from decorative blocks that sound like metal.
     * Blocks of mods that aren't installed are skipped.
     */
    private static final String MODS = "/assets/shaderscompanion/materials/mods.json";

    /** Classes in order (class number = index + 1): name, then LabPBR smoothness, F0 or metal id, porosity (bytes). */
    private static final Object[][] CLASSES = {
            // Perfectly smooth: vanilla glass stays the mirror it is without a specular map
            {"glass", 255, 10, 0},
            {"ice", 235, 5, 0},
            {"gel", 190, 5, 0},
            // Below 128 a dielectric gets a sun sheen but no traced reflection in Sundial-based packs (outside "full
            // reflection"): smooth stone and polished stone are common floors, and a whole floor tracing cost 9 ms of
            // GPU at 1080p in the dev client
            {"polished", 120, 10, 0},
            {"glazed", 200, 10, 0},
            {"concrete", 90, 10, 0},
            {"obsidian", 215, 10, 0},
            {"gem", 220, 43, 0},
            {"iron", 180, 230, 0},
            {"gold", 200, 231, 0},
            {"aluminum", 170, 232, 0},
            {"chrome", 210, 233, 0},
            {"copper", 165, 234, 0},
            {"exposed_copper", 120, 234, 0},
            {"silver", 190, 237, 0},
            {"metal", 140, 255, 0},
            {"dark_metal", 150, 255, 0},
            // Modded blocks that are metal by their sound type alone (no tag, name word or block entity), with the values
            // of the class that sound gives: a pack can tell them apart (Better Blockz gives nearly every block a metal
            // sound, painted tiles and walls included) and light them as something else
            {"sound_metal", 140, 255, 0},
            {"sound_copper", 165, 234, 0},
            {"sound_dark_metal", 150, 255, 0},
    };

    // Inference: names of the classes each source can give
    private static final int GLASS = classNumber("glass");
    private static final int ICE = classNumber("ice");
    private static final int METAL = classNumber("metal");
    private static final int GEM = classNumber("gem");
    private static final Set<Integer> METALS = Set.of(classNumber("iron"), classNumber("gold"), classNumber("aluminum"), classNumber("chrome"),
            classNumber("copper"), classNumber("exposed_copper"), classNumber("silver"), METAL, classNumber("dark_metal"));
    /** The class a metal sound type gives, and the class a block gets when that sound is its only evidence. */
    private static final Map<Integer, Integer> SOUND_CLASSES = Map.of(METAL, classNumber("sound_metal"), classNumber("copper"), classNumber("sound_copper"),
            classNumber("dark_metal"), classNumber("sound_dark_metal"));
    /** Storage block tags (forge:storage_blocks/<name>) and the class they give. */
    static final Map<String, String> STORAGE_TAGS = Map.ofEntries(
            Map.entry("iron", "iron"), Map.entry("gold", "gold"), Map.entry("copper", "copper"), Map.entry("netherite", "dark_metal"),
            Map.entry("aluminum", "aluminum"), Map.entry("aluminium", "aluminum"), Map.entry("silver", "silver"), Map.entry("platinum", "silver"),
            Map.entry("steel", "metal"), Map.entry("lead", "metal"), Map.entry("tin", "metal"), Map.entry("bronze", "metal"), Map.entry("brass", "metal"),
            Map.entry("nickel", "metal"), Map.entry("zinc", "metal"), Map.entry("osmium", "metal"), Map.entry("invar", "metal"),
            Map.entry("electrum", "gold"), Map.entry("constantan", "copper"), Map.entry("titanium", "metal"), Map.entry("chrome", "chrome"),
            Map.entry("uranium", "metal"), Map.entry("tungsten", "metal"),
            Map.entry("diamond", "gem"), Map.entry("emerald", "gem"), Map.entry("amethyst", "gem"), Map.entry("quartz", "glazed"));
    /** Name words that give a class on their own, and words that need a second source (sound type or tag) to agree. */
    private static final Map<String, String> STRONG_WORDS = Map.ofEntries(
            Map.entry("glass", "glass"), Map.entry("ice", "ice"), Map.entry("steel", "metal"), Map.entry("marble", "glazed"),
            Map.entry("glazed", "glazed"), Map.entry("quartz", "glazed"), Map.entry("polished", "polished"), Map.entry("obsidian", "obsidian"),
            Map.entry("chrome", "chrome"), Map.entry("aluminum", "aluminum"), Map.entry("aluminium", "aluminum"), Map.entry("titanium", "metal"));
    private static final Map<String, String> WEAK_WORDS = Map.ofEntries(
            Map.entry("iron", "iron"), Map.entry("gold", "gold"), Map.entry("golden", "gold"), Map.entry("copper", "copper"),
            Map.entry("silver", "silver"), Map.entry("platinum", "silver"), Map.entry("metal", "metal"), Map.entry("metallic", "metal"),
            Map.entry("lead", "metal"), Map.entry("tin", "metal"), Map.entry("bronze", "metal"), Map.entry("brass", "metal"), Map.entry("tungsten", "metal"),
            Map.entry("plate", "metal"), Map.entry("sheet", "metal"), Map.entry("tile", "glazed"), Map.entry("tiles", "glazed"),
            Map.entry("ceramic", "glazed"), Map.entry("porcelain", "glazed"), Map.entry("concrete", "concrete"), Map.entry("smooth", "polished"),
            Map.entry("exposed", "exposed_copper"));
    /**
     * Name words that rule a block out: ores and raw materials look like stone; plants and items are not surfaces;
     * weathered and oxidized copper are rough (the vanilla table leaves them out too).
     */
    private static final Set<String> EXCLUDED_WORDS = Set.of("ore", "raw", "sandstone", "nugget", "dust", "crop", "sapling", "leaves", "flower", "bush",
            "weathered", "oxidized");
    /**
     * Name words for rough materials, which overrule a block's sound type: a mod that gives all its blocks one sound
     * (Better Blockz: metal) names its stone ones stone. A strong name word or a tag still counts.
     */
    private static final Set<String> ROUGH_WORDS = Set.of("stone", "cobblestone", "cobble", "rock", "brick", "bricks", "plank", "planks", "wood", "log",
            "wool", "carpet", "dirt", "sand", "gravel", "clay", "terracotta", "mud", "moss");

    private static boolean tableWanted;
    static String status = "no shader pack has asked for it";

    private Materials() {
    }

    /**
     * The class a block state carries in a pipeline's block state to id map (the block ids chunk meshes and the mod's
     * views are drawn with), 0 for none: what the lattice stores, so a pack reads the same class everywhere.
     */
    static int classOf(Object2IntMap<BlockState> ids, BlockState state) {
        if (ids == null) {
            return 0;
        }
        int id = ids.getOrDefault(state, -1);
        return id >= FLAG_RANGE ? id & CLASS_MASK : 0;
    }

    static int classNumber(String name) {
        for (int i = 0; i < CLASSES.length; i++) {
            if (CLASSES[i][0].equals(name)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("No material class " + name);
    }

    static String className(int material) {
        return (String) CLASSES[material - 1][0];
    }

    /** The table's texels: texel 0 the header (42, 42, protocol, 0), texel c class c's LabPBR bytes and 255. */
    static ByteBuffer tableTexels() {
        ByteBuffer texels = MemoryUtil.memCalloc(TABLE_WIDTH * 4);
        texels.put(0, (byte) 42).put(1, (byte) 42).put(2, (byte) PROTOCOL);
        for (int i = 0; i < CLASSES.length; i++) {
            int at = (i + 1) * 4;
            texels.put(at, (byte) (int) (Integer) CLASSES[i][1]).put(at + 1, (byte) (int) (Integer) CLASSES[i][2])
                    .put(at + 2, (byte) (int) (Integer) CLASSES[i][3]).put(at + 3, (byte) 255);
        }
        return texels;
    }

    /** Fills the table texture (bound by the caller, allocated TABLE_WIDTH x 1). */
    static void uploadTable() {
        ByteBuffer texels = tableTexels();
        try {
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, TABLE_WIDTH, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, texels);
        } finally {
            MemoryUtil.memFree(texels);
        }
    }

    /** Oculus is creating the custom textures of the pipeline it is building; the pack names this one. */
    public static void noteCustomTexture(String location) {
        if (TABLE.equals(location)) {
            tableWanted = true;
        }
    }

    /** Oculus built a pipeline's block state to id map: puts the classes in when that pipeline's pack reads the table. */
    public static void mergeInto(Object2IntMap<BlockState> ids) {
        boolean wanted = tableWanted;
        tableWanted = false;
        ItemMaterials.clear();
        if (!wanted) {
            status = "the shader pack doesn't read it";
            return;
        }
        if (!Config.ENABLED.get() || !Config.MATERIALS.get()) {
            status = "turned off in the config";
            return;
        }
        try {
            merge(ids);
        } catch (Throwable t) {
            status = "stopped after an error: " + t;
            ShadersCompanion.LOGGER.error("Shaders Companion couldn't give blocks their material classes", t);
        }
    }

    private static void merge(Object2IntMap<BlockState> ids) {
        long start = System.nanoTime();
        Reference2IntOpenHashMap<Block> fixed = new Reference2IntOpenHashMap<>();
        List<String> problems = new ArrayList<>();
        readTable(fixed, VANILLA, null, problems);
        int fromMods = readTable(fixed, MODS, null, problems);
        int overrides = readTable(fixed, null, FMLPaths.CONFIGDIR.get().resolve(OVERRIDES), problems);
        int states = 0;
        int blocksFixed = 0;
        int blocksInferred = 0;
        int skippedIds = 0;
        // Inferred blocks by class, for the log: the user checks them there and overrides what is wrong
        Map<String, List<String>> inferred = new java.util.TreeMap<>();
        for (Map.Entry<net.minecraft.resources.ResourceKey<Block>, Block> entry : ForgeRegistries.BLOCKS.getEntries()) {
            Block block = entry.getValue();
            ResourceLocation key = entry.getKey().location();
            boolean isFixed = fixed.containsKey(block);
            int material = isFixed ? fixed.getInt(block) : "minecraft".equals(key.getNamespace()) ? 0 : infer(block, key);
            if (material <= 0) {
                continue;
            }
            int given = 0;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int id = ids.getOrDefault(state, -1);
                if (id < 0) {
                    // Unlisted blocks without collision are left to the pack's own plant detection
                    if (!isFixed && hasNoCollision(state)) {
                        continue;
                    }
                    ids.put(state, FLAG_RANGE | material);
                } else if (id >= FLAG_RANGE && (id & CLASS_MASK) == 0) {
                    ids.put(state, id | material);
                } else {
                    skippedIds++;
                    continue;
                }
                given++;
            }
            if (given > 0) {
                states += given;
                if (isFixed) {
                    blocksFixed++;
                } else {
                    blocksInferred++;
                    inferred.computeIfAbsent(className(material), k -> new ArrayList<>()).add(key.toString());
                }
            }
        }
        // The other block states the pack doesn't list get 16384 (no class, no flags) when they give no light. Sundial's
        // modded light detection makes an unlisted block glow where a pixel has full block light, and a face pointing
        // at a light-15 block (a lamp beside a glass pane: the pane's far face, seen through it) samples the lamp's
        // cell and has it too. With an id these faces stay unlit by it; real modded lights stay unlisted and glow.
        // Plants stay unlisted for the pack's plant detection.
        int unlit = 0;
        for (Map.Entry<net.minecraft.resources.ResourceKey<Block>, Block> entry : ForgeRegistries.BLOCKS.getEntries()) {
            Block block = entry.getValue();
            if (plantLike(block, entry.getKey().location())) {
                continue;
            }
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (ids.getOrDefault(state, -1) < 0 && state.getLightEmission() == 0 && !hasNoCollision(state)) {
                    ids.put(state, FLAG_RANGE);
                    unlit++;
                }
            }
        }
        int soundOnly = 0;
        for (Map.Entry<String, List<String>> entry : inferred.entrySet()) {
            soundOnly += entry.getKey().startsWith("sound_") ? entry.getValue().size() : 0;
        }
        status = String.format("%d blocks (%d block states) given a class: %d from the tables (%d from the mod table%s), %d inferred (%d by their sound alone); %d states kept the pack's own id; %d other unlisted states marked as giving no light; %.0f ms",
                blocksFixed + blocksInferred, states, blocksFixed, fromMods, overrides > 0 ? ", " + overrides + " overrides" : "", blocksInferred, soundOnly, skippedIds, unlit,
                (System.nanoTime() - start) / 1.0e6) + (problems.isEmpty() ? "" : "; " + problems.size() + " problems in the tables (see the log)");
        ShadersCompanion.LOGGER.info("Shaders Companion materials: {}", status);
        for (Map.Entry<String, List<String>> entry : inferred.entrySet()) {
            ShadersCompanion.LOGGER.info("Shaders Companion materials: inferred {} ({}): {}", entry.getKey(), entry.getValue().size(), String.join(", ", entry.getValue()));
        }
        // Items next (Oculus sets the item id map right after this one): ItemMaterials.wrap gives them their classes
        try {
            ItemMaterials.build(problems);
            status += "; " + ItemMaterials.status;
        } catch (Throwable t) {
            status += "; items stopped after an error: " + t;
            ShadersCompanion.LOGGER.error("Shaders Companion couldn't give items their material classes", t);
        }
        for (String problem : problems) {
            ShadersCompanion.LOGGER.warn("Shaders Companion materials: {}", problem);
        }
    }

    /**
     * Reads a table of class name to block ids and #tags, from the jar (resource) or a file (path, skipped when
     * missing), into fixed; later tables win. Returns the number of blocks it set.
     */
    private static int readTable(Reference2IntOpenHashMap<Block> fixed, String resource, Path path, List<String> problems) {
        String source = resource == null ? path.getFileName().toString() : resource.equals(VANILLA) ? "the vanilla table" : "the mod table";
        JsonObject root;
        try {
            if (resource != null) {
                try (InputStream in = Materials.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        problems.add(source + " is missing from the jar");
                        return 0;
                    }
                    root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                }
            } else {
                if (!Files.isRegularFile(path)) {
                    return 0;
                }
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    root = JsonParser.parseReader(reader).getAsJsonObject();
                }
            }
        } catch (Exception e) {
            problems.add(source + " can't be read: " + e.getMessage());
            return 0;
        }
        int set = 0;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            // Items are read by ItemMaterials
            if (entry.getKey().startsWith("_") || entry.getKey().equals("items")) {
                continue;
            }
            int material;
            if (entry.getKey().equals("none")) {
                material = -1;
            } else {
                try {
                    material = classNumber(entry.getKey());
                } catch (IllegalArgumentException e) {
                    problems.add(source + ": no class named " + entry.getKey());
                    continue;
                }
            }
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                String name = element.getAsString();
                if (name.startsWith("#")) {
                    TagKey<Block> tag = TagKey.create(Registries.BLOCK, new ResourceLocation(name.substring(1)));
                    for (Block block : ForgeRegistries.BLOCKS) {
                        if (block.defaultBlockState().is(tag)) {
                            fixed.put(block, material);
                            set++;
                        }
                    }
                } else {
                    ResourceLocation id = new ResourceLocation(name);
                    Block block = ForgeRegistries.BLOCKS.getValue(id);
                    if (block == null || ForgeRegistries.BLOCKS.getKey(block) == null || !ForgeRegistries.BLOCKS.getKey(block).equals(id)) {
                        // Mods that aren't installed are fine in the user's file and the mod table; the jar's tables
                        // should match the mods that are
                        if (resource != null && (resource.equals(VANILLA) || ModList.get().isLoaded(id.getNamespace()))) {
                            problems.add(source + ": no block " + name);
                        }
                        continue;
                    }
                    fixed.put(block, material);
                    set++;
                }
            }
        }
        return set;
    }

    /** A modded block's class from its sound type, tags and name, or 0 when they say too little or disagree. */
    static int infer(Block block, ResourceLocation key) {
        String[] words = key.getPath().toLowerCase(Locale.ROOT).split("_");
        for (String word : words) {
            if (EXCLUDED_WORDS.contains(word)) {
                return 0;
            }
        }
        BlockState state = block.defaultBlockState();
        int sound = soundClass(state.getSoundType());
        // A block that gives light is a lamp, whatever it sounds like (glowstone, sea lanterns and modded light panels
        // sound like glass); and a rough material's name overrules the sound
        boolean rough = false;
        for (String word : words) {
            rough |= ROUGH_WORDS.contains(word);
        }
        if (rough || state.getLightEmission() > 0) {
            sound = 0;
        }
        int tag = tagClass(state);
        int strongName = 0;
        int weakName = 0;
        for (String word : words) {
            if (strongName == 0 && STRONG_WORDS.containsKey(word)) {
                strongName = classNumber(STRONG_WORDS.get(word));
            }
            if (weakName == 0 && WEAK_WORDS.containsKey(word)) {
                weakName = classNumber(WEAK_WORDS.get(word));
            }
        }
        int name = strongName != 0 ? strongName : weakName;
        // Every source that speaks must agree on the family (glass and ice, the metals, the rest one by one)
        int[] sources = {sound, tag, name};
        int chosen = 0;
        for (int source : sources) {
            if (source == 0) {
                continue;
            }
            if (chosen != 0 && family(chosen) != family(source)) {
                return 0;
            }
            // The most specific source wins inside a family: name over tag over sound
            chosen = source;
        }
        if (chosen == 0) {
            return 0;
        }
        // A full cube is one material: its sound type, a tag or a strong name word is enough. A shaped block (furniture,
        // lamps, fittings) mixes materials in one model, and its sound names only one of them (Modern Furniture's desk
        // lamps sound like glass, its bathtub and whiteboard like metal): it needs a tag, or its sound and name to agree.
        boolean enough = isFullCube(state) ? sound != 0 || tag != 0 || strongName != 0 : tag != 0 || sound != 0 && name != 0;
        if (!enough) {
            return 0;
        }
        // Metal by its sound alone gets that sound's own class, which a pack may light as something else; a block with a
        // block entity (a machine, a charging station) keeps the metal class
        if (tag == 0 && name == 0 && family(sound) == METAL && !(block instanceof EntityBlock)) {
            return SOUND_CLASSES.getOrDefault(sound, chosen);
        }
        return chosen;
    }

    private static boolean isFullCube(BlockState state) {
        try {
            return Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        } catch (Throwable t) {
            // A block whose shape needs a real world: not known to be a full cube
            return false;
        }
    }

    private static int family(int material) {
        if (material == GLASS || material == ICE) {
            return GLASS;
        }
        return METALS.contains(material) ? METAL : material;
    }

    private static int soundClass(SoundType sound) {
        if (sound == SoundType.GLASS) {
            return GLASS;
        }
        if (sound == SoundType.METAL || sound == SoundType.ANVIL || sound == SoundType.CHAIN || sound == SoundType.LANTERN) {
            return METAL;
        }
        if (sound == SoundType.COPPER) {
            return classNumber("copper");
        }
        if (sound == SoundType.NETHERITE_BLOCK) {
            return classNumber("dark_metal");
        }
        if (sound == SoundType.AMETHYST) {
            return GEM;
        }
        return 0;
    }

    private static int tagClass(BlockState state) {
        if (state.is(tag("forge", "glass")) || state.is(tag("forge", "glass_panes"))) {
            return GLASS;
        }
        if (state.is(tag("minecraft", "ice"))) {
            return ICE;
        }
        for (Map.Entry<String, String> entry : STORAGE_TAGS.entrySet()) {
            if (state.is(tag("forge", "storage_blocks/" + entry.getKey()))) {
                return classNumber(entry.getValue());
            }
        }
        return 0;
    }

    private static TagKey<Block> tag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, new ResourceLocation(namespace, path));
    }

    /** Plants by sound or name (some have collision, and the pack's plant detection needs them unlisted). */
    private static boolean plantLike(Block block, ResourceLocation key) {
        SoundType sound = block.defaultBlockState().getSoundType();
        if (sound == SoundType.GRASS || sound == SoundType.CROP || sound == SoundType.SWEET_BERRY_BUSH || sound == SoundType.VINE
                || sound == SoundType.CAVE_VINES || sound == SoundType.HANGING_ROOTS || sound == SoundType.ROOTS || sound == SoundType.NETHER_SPROUTS
                || sound == SoundType.AZALEA || sound == SoundType.FLOWERING_AZALEA || sound == SoundType.SPORE_BLOSSOM || sound == SoundType.SMALL_DRIPLEAF
                || sound == SoundType.BIG_DRIPLEAF || sound == SoundType.PINK_PETALS || sound == SoundType.BAMBOO_SAPLING || sound == SoundType.CHERRY_SAPLING
                || sound == SoundType.WET_GRASS || sound == SoundType.LILY_PAD) {
            return true;
        }
        for (String word : key.getPath().toLowerCase(Locale.ROOT).split("_")) {
            if (word.equals("sapling") || word.equals("crop") || word.equals("crops") || word.equals("flower") || word.equals("bush") || word.equals("leaves")
                    || word.equals("plant") || word.equals("vine") || word.equals("vines") || word.equals("grass")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNoCollision(BlockState state) {
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (Throwable t) {
            // A block whose shape needs a real world: leave it alone
            return true;
        }
    }
}
