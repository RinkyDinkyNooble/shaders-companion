package com.rinkynooble.shaderscompanion.client.far;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The far store's numbering of block states for one world: 0 is unknown (no data), 1 is air (cave and void air too),
 * and every other state gets the next number the first time the worker sees it. Numbers never change: they are saved
 * as {@code states.txt} (line n is id n, as {@link BlockStateParser#serialize}; lines 0 and 1 are placeholders for
 * unknown and air), loaded when the store opens and appended to before any node that uses a new id is written. A line
 * that no longer parses (a mod removed) keeps its id and reads back as an unknown block, so stored terrain survives a
 * mod update that renumbers the game's registries. Only the worker assigns ids; any thread may read a state back by id.
 */
final class StateIds {
    static final int UNKNOWN = 0;
    static final int AIR = 1;

    private final Reference2IntOpenHashMap<BlockState> ids = new Reference2IntOpenHashMap<>();
    /** By id; index 0 is null (unknown). Grown by copying, then published through the volatile count. */
    private volatile BlockState[] states = new BlockState[256];
    private volatile int count = 2;
    /** Ids written to states.txt so far. */
    private int saved;
    /** Lines of states.txt that didn't parse when it was loaded. */
    int unparsed;

    StateIds() {
        ids.defaultReturnValue(-1);
        states[AIR] = Blocks.AIR.defaultBlockState();
        ids.put(Blocks.AIR.defaultBlockState(), AIR);
        ids.put(Blocks.CAVE_AIR.defaultBlockState(), AIR);
        ids.put(Blocks.VOID_AIR.defaultBlockState(), AIR);
    }

    /** The ids of an earlier session, from the lines of states.txt (before any id is assigned; worker thread). */
    void load(List<String> lines) {
        BlockState[] array = states;
        int n = Math.max(2, lines.size());
        if (array.length < n) {
            array = Arrays.copyOf(array, Integer.highestOneBit(n - 1) << 1);
        }
        for (int id = 2; id < lines.size(); id++) {
            BlockState state = null;
            try {
                state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), lines.get(id), false).blockState();
            } catch (Exception e) {
                unparsed++;
            }
            array[id] = state;
            if (state != null && !ids.containsKey(state)) {
                ids.put(state, id);
            }
        }
        states = array;
        count = n;
        saved = lines.size();
    }

    /** The id of a state, assigning the next one when it is new (worker thread only). */
    int id(BlockState state) {
        int id = ids.getInt(state);
        if (id >= 0) {
            return id;
        }
        if (state.isAir()) {
            // Modded air blocks count as air too
            ids.put(state, AIR);
            return AIR;
        }
        id = count;
        BlockState[] array = states;
        if (id == array.length) {
            array = Arrays.copyOf(array, array.length * 2);
        }
        array[id] = state;
        states = array;
        ids.put(state, id);
        count = id + 1;
        return id;
    }

    /** The state of an id, or null for unknown, for ids not assigned and for lines that no longer parse (any thread). */
    BlockState state(int id) {
        // The count first: the array read after it holds every state up to it
        int assigned = count;
        BlockState[] array = states;
        return id > 0 && id < assigned ? array[id] : null;
    }

    /** Ids assigned so far, including unknown and air. */
    int size() {
        return count;
    }

    /**
     * The lines of states.txt not written yet. The caller appends them before writing any node and then calls
     * {@link #markSaved}, so a failed append leaves them unsaved (line n must stay id n).
     */
    List<String> unsaved() {
        List<String> lines = new ArrayList<>();
        int n = count;
        for (int id = saved; id < n; id++) {
            lines.add(id == UNKNOWN ? "#unknown" : id == AIR ? "minecraft:air" : BlockStateParser.serialize(states[id]));
        }
        return lines;
    }

    /** The first {@code lines} of {@link #unsaved} are in states.txt now. */
    void markSaved(int lines) {
        saved += lines;
    }
}
