package com.rinkynooble.shaderscompanion.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * The material class tables and the user's overrides, read as rules for blocks, items and entity types. A rule gives a
 * class (or none) to an id, every id a {@code *} pattern matches ({@code *} never crosses the {@code :}), or a
 * {@code #tag}. Later rules win: the jar's tables first, then {@code config/shaderscompanion-materials.json} (class name
 * -> targets; its "items" and "entities" objects for those), then {@code config/shaderscompanion/*.rules} in alphabetical
 * order, line by line, in the style of Scalpel's and Lathe's rules:
 * <pre>
 * metal betterblockz:ironslate_*
 * none #forge:glass
 * item gem minecraft:diamond_horse_armor
 * entity paint superbwarfare:*
 * </pre>
 */
final class MaterialRules {
    enum Kind {
        BLOCK("block"), ITEM("item"), ENTITY("entity");

        final String word;

        Kind(String word) {
            this.word = word;
        }
    }

    /** A rule: material -1 is "none" (the target keeps no class, whatever inference says). */
    record Rule(Kind kind, int material, String target, String source) {
    }

    private static final String JSON_FILE = "shaderscompanion-materials.json";
    private static final String RULES_FOLDER = "shaderscompanion";

    private MaterialRules() {
    }

    /** A table in the jar: class name -> targets, of one kind. */
    static List<Rule> resource(String resource, Kind kind, String source, List<String> problems) {
        List<Rule> rules = new ArrayList<>();
        try (InputStream in = MaterialRules.class.getResourceAsStream(resource)) {
            if (in == null) {
                problems.add(source + " is missing from the jar");
                return rules;
            }
            readJson(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject(), kind, source, rules, problems);
        } catch (Exception e) {
            problems.add(source + " can't be read: " + e.getMessage());
        }
        return rules;
    }

    /** The user's overrides: the JSON file, then the rules files. */
    static List<Rule> user(List<String> problems) {
        List<Rule> rules = new ArrayList<>();
        Path config = FMLPaths.CONFIGDIR.get();
        Path json = config.resolve(JSON_FILE);
        if (Files.isRegularFile(json)) {
            try (Reader reader = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                readJson(root, Kind.BLOCK, JSON_FILE, rules, problems);
                if (root.has("items")) {
                    readJson(root.getAsJsonObject("items"), Kind.ITEM, JSON_FILE + " (items)", rules, problems);
                }
                if (root.has("entities")) {
                    readJson(root.getAsJsonObject("entities"), Kind.ENTITY, JSON_FILE + " (entities)", rules, problems);
                }
            } catch (Exception e) {
                problems.add(JSON_FILE + " can't be read: " + e.getMessage());
            }
        }
        Path folder = config.resolve(RULES_FOLDER);
        try {
            if (!Files.isDirectory(folder)) {
                Files.createDirectories(folder);
                Files.writeString(folder.resolve("example.rules"), EXAMPLE, StandardCharsets.UTF_8);
            }
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".rules")).sorted().toList()) {
                    readRules(file, rules, problems);
                }
            }
        } catch (IOException e) {
            problems.add("config/" + RULES_FOLDER + " can't be read: " + e.getMessage());
        }
        return rules;
    }

    private static void readJson(JsonObject root, Kind kind, String source, List<Rule> rules, List<String> problems) {
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("_") || entry.getValue().isJsonObject()) {
                // Comments, and the "items" and "entities" objects (read on their own)
                continue;
            }
            int material = material(name);
            if (material == 0) {
                problems.add(source + ": no class named " + name);
                continue;
            }
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                rules.add(new Rule(kind, material, element.getAsString(), source));
            }
        }
    }

    private static void readRules(Path file, List<Rule> rules, List<String> problems) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String source = file.getFileName() + " line " + (i + 1);
            String[] words = line.split("\\s+");
            Kind kind = Kind.BLOCK;
            int at = 0;
            for (Kind candidate : Kind.values()) {
                if (words[0].equalsIgnoreCase(candidate.word)) {
                    kind = candidate;
                    at = 1;
                }
            }
            if (words.length < at + 2) {
                problems.add(source + ": a rule is [block|item|entity] <class or none> <id, pattern or #tag> ...");
                continue;
            }
            int material = material(words[at]);
            if (material == 0) {
                problems.add(source + ": no class named " + words[at]);
                continue;
            }
            for (int w = at + 1; w < words.length; w++) {
                rules.add(new Rule(kind, material, words[w], source));
            }
        }
    }

    /** A class name's number, -1 for none, 0 for no such class. */
    private static int material(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("none")) {
            return -1;
        }
        try {
            return Materials.classNumber(lower);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /**
     * Applies the rules of a kind to a registry: each target's entries get its class in fixed. Ids not in the registry are
     * reported when their mod is loaded and report is set (the jar's tables should match the mods that are installed; the
     * user's files may name mods they don't have). Returns the number of entries set.
     */
    static <T> int apply(List<Rule> rules, Kind kind, Registry<T> registry, Reference2IntOpenHashMap<T> fixed, boolean report, List<String> problems) {
        int set = 0;
        for (Rule rule : rules) {
            if (rule.kind() != kind) {
                continue;
            }
            String target = rule.target();
            try {
                if (target.startsWith("#")) {
                    TagKey<T> tag = TagKey.create(registry.key(), new ResourceLocation(target.substring(1)));
                    for (Holder<T> holder : registry.getTagOrEmpty(tag)) {
                        fixed.put(holder.value(), rule.material());
                        set++;
                    }
                } else if (target.contains("*")) {
                    String full = target.contains(":") ? target : "minecraft:" + target;
                    Pattern pattern = Pattern.compile(Pattern.quote(full).replace("*", "\\E[^:]*\\Q"));
                    for (Map.Entry<ResourceKey<T>, T> entry : registry.entrySet()) {
                        if (pattern.matcher(entry.getKey().location().toString()).matches()) {
                            fixed.put(entry.getValue(), rule.material());
                            set++;
                        }
                    }
                } else {
                    ResourceLocation id = new ResourceLocation(target);
                    T value = registry.getOptional(id).orElse(null);
                    if (value == null) {
                        if (report && ModList.get().isLoaded(id.getNamespace())) {
                            problems.add(rule.source() + ": no " + kind.word + " " + target);
                        }
                        continue;
                    }
                    fixed.put(value, rule.material());
                    set++;
                }
            } catch (RuntimeException e) {
                problems.add(rule.source() + ": " + target + " is not an id, pattern or #tag (" + e.getMessage() + ")");
            }
        }
        return set;
    }

    private static final String EXAMPLE = """
            # Shaders Companion material rules. Every *.rules file in this folder is read, in alphabetical order, after the
            # mod's own tables and config/shaderscompanion-materials.json; later rules win. Changes take effect when the
            # shader pack is next loaded (reload it from the shader pack menu, or press the reload shaders key).
            #
            # A rule: [block|item|entity] <class> <target> [more targets]
            #   block is the default. A target is an id (minecraft:iron_block), an id with * wildcards (mekanism:*_tank;
            #   * never crosses the :), or a #tag (#forge:glass). A block entity takes its block's class; armour, tools
            #   and other items are items; mobs and vehicles are entities (with "Entity materials" on in the shader pack).
            #
            # Classes: glass, ice, gel, polished, glazed, concrete, obsidian, gem, iron, gold, aluminum, chrome, copper,
            # exposed_copper, silver, metal, dark_metal, paint (a glossy coat over its colour), and none (no class, even
            # where the mod would infer one).
            #
            # Examples (remove the # in front to use one):
            # metal betterblockz:ironslate_*
            # none mdm:white_bathtub
            # glass #forge:glass
            # item metal tacz:modern_kinetic_gun
            # entity paint superbwarfare:*
            """;
}
