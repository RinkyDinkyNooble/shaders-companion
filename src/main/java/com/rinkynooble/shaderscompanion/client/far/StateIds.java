package com.rinkynooble.shaderscompanion.client.far;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.Arrays;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The far store's numbering of block states for one world: 0 is unknown (no data), 1 is air (cave and void air too),
 * and every other state gets the next number the first time the worker sees it. Numbers never change while the world
 * is open, so stored terrain survives a mod update that renumbers the game's registries once M1b saves the list as
 * {@code states.txt} (line number = id); that is also why a state that no longer parses keeps its id. Only the worker
 * assigns ids; any thread may read a state back by id.
 */
final class StateIds {
    static final int UNKNOWN = 0;
    static final int AIR = 1;

    private final Reference2IntOpenHashMap<BlockState> ids = new Reference2IntOpenHashMap<>();
    /** By id; index 0 is null (unknown). Grown by copying, then published through the volatile count. */
    private volatile BlockState[] states = new BlockState[256];
    private volatile int count = 2;

    StateIds() {
        ids.defaultReturnValue(-1);
        states[AIR] = Blocks.AIR.defaultBlockState();
        ids.put(Blocks.AIR.defaultBlockState(), AIR);
        ids.put(Blocks.CAVE_AIR.defaultBlockState(), AIR);
        ids.put(Blocks.VOID_AIR.defaultBlockState(), AIR);
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

    /** The state of an id, or null for unknown and for ids not assigned (any thread). */
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
}
