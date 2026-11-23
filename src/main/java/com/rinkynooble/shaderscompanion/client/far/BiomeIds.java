package com.rinkynooble.shaderscompanion.client.far;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * The far store's numbering of biomes for one world, like {@link StateIds}: 0 is unknown, and every biome gets the next
 * number by its registry name the first time the worker sees it, saved as {@code biomes.txt} (line n is id n, line 0 a
 * placeholder). Biomes are kept by name, not by holder, so a biome that leaves the registry still keeps its number, and
 * the client's and the integrated server's holders of one biome (live capture and import) get the same number. Only
 * the worker assigns ids; any thread may read a name back by id ({@link #published}, for the far colour table).
 */
final class BiomeIds {
    static final int UNKNOWN = 0;

    private final Reference2IntOpenHashMap<Holder<Biome>> byHolder = new Reference2IntOpenHashMap<>();
    private final Object2IntOpenHashMap<String> byName = new Object2IntOpenHashMap<>();
    private final List<String> names = new ArrayList<>();
    /** The names by id for other threads, copied whenever one is added (a world has a few dozen). */
    private volatile String[] published = {""};
    private int saved;

    BiomeIds() {
        byHolder.defaultReturnValue(-1);
        byName.defaultReturnValue(-1);
        names.add("");
    }

    /** The ids of an earlier session, from the lines of biomes.txt (before any id is assigned). */
    void load(List<String> lines) {
        for (int id = 1; id < lines.size(); id++) {
            String name = lines.get(id);
            names.add(name);
            if (!name.isEmpty() && !byName.containsKey(name)) {
                byName.put(name, id);
            }
        }
        saved = lines.size();
        published = names.toArray(new String[0]);
    }

    /** The id of a biome, assigning the next one when it is new (worker thread only). */
    int id(Holder<Biome> biome) {
        int id = byHolder.getInt(biome);
        if (id >= 0) {
            return id;
        }
        String name = biome.unwrapKey().map(key -> key.location().toString()).orElse("");
        if (name.isEmpty()) {
            // An unregistered biome (a mod's direct holder): unknown
            byHolder.put(biome, UNKNOWN);
            return UNKNOWN;
        }
        id = byName.getInt(name);
        if (id < 0) {
            id = names.size();
            names.add(name);
            byName.put(name, id);
            published = names.toArray(new String[0]);
        }
        byHolder.put(biome, id);
        return id;
    }

    /** The registry name of an id ("" for unknown). */
    String name(int id) {
        return id > 0 && id < names.size() ? names.get(id) : "";
    }

    int size() {
        return names.size();
    }

    /** The names by id as far as assigned (any thread; index 0 is "", unknown). */
    String[] published() {
        return published;
    }

    /** The lines of biomes.txt not written yet ({@link #markSaved} once they are, as for {@link StateIds}). */
    List<String> unsaved() {
        List<String> lines = new ArrayList<>();
        for (int id = saved; id < names.size(); id++) {
            lines.add(id == UNKNOWN ? "#unknown" : names.get(id));
        }
        return lines;
    }

    /** The first {@code lines} of {@link #unsaved} are in biomes.txt now. */
    void markSaved(int lines) {
        saved += lines;
    }
}
