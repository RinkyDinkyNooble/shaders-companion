package com.rinkynooble.shaderscompanion.client.far;

import net.minecraft.world.level.block.state.BlockState;

/**
 * The far mesher's class of each state id of one store ({@link FarMesher}'s class comment), with its tint, translucent
 * and plant bits: worked out once per state, the first time something meshes after the id appeared, from the store's
 * flags ({@link FarNodes#flags}) and the client's block colours and models. Worker thread; the array is shared with mesh
 * jobs, and its entries never change (a longer array is a new one: jobs made before keep the old).
 */
final class MeshClasses {
    private final FarNodes store;
    private byte[] classes = new byte[256];
    /** Ids whose class is worked out. */
    private int known = 2;

    MeshClasses(FarNodes store) {
        this.store = store;
        classes[StateIds.UNKNOWN] = FarMesher.UNKNOWN;
        classes[StateIds.AIR] = FarMesher.AIR;
    }

    /** Every state id's class so far. */
    byte[] all() {
        int size = store.states.size();
        if (known < size) {
            byte[] out = classes;
            if (out.length < size) {
                out = java.util.Arrays.copyOf(out, Math.max(size, out.length * 2));
            }
            for (int i = known; i < size; i++) {
                out[i] = classOf(store.states.state(i), store.flags(i));
            }
            classes = out;
            known = size;
        }
        return classes;
    }

    /**
     * The far mesher's class of a state with these flags (FarMesher's class comment), with {@link FarMesher#TINTED}
     * when the state has a tint provider (its faces keep their biome in a mesh's palette) or is water-like, and
     * {@link FarMesher#TRANSLUCENT} when it is drawn in the translucent pass (water-like ones too).
     */
    static byte classOf(BlockState state, byte f) {
        if (state == null) {
            // A line of states.txt that no longer parses: an unknown block, still a block
            return FarMesher.SOLID;
        }
        int c;
        boolean invisible;
        try {
            invisible = state.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE && state.getFluidState().isEmpty();
        } catch (RuntimeException e) {
            invisible = false;
        }
        if ((f & FarNodes.OPEN) != 0) {
            c = state.isAir() ? FarMesher.AIR : FarMesher.WATER;
        } else if (invisible || (f & FarNodes.THIN) != 0) {
            // Decorations, and blocks nothing draws (barriers, light blocks, structure voids)
            c = FarMesher.AIR;
        } else if ((f & FarNodes.SHEER) != 0) {
            c = FarMesher.SHEER;
        } else {
            c = FarMesher.SOLID;
        }
        boolean tinted = c == FarMesher.WATER;
        boolean translucent = c == FarMesher.WATER;
        if (!tinted && c != FarMesher.AIR) {
            try {
                // A tint provider answers without a world (vanilla's give their default colours); none answers -1
                tinted = net.minecraft.client.Minecraft.getInstance().getBlockColors().getColor(state, null, null, 0) != -1;
            } catch (RuntimeException e) {
                tinted = true;
            }
            try {
                // Drawn in the translucent pass (stained glass, ice, slime): the far terrain's second pass too
                translucent = net.minecraft.client.Minecraft.getInstance().getBlockRenderer().getBlockModel(state)
                        .getRenderTypes(state, net.minecraft.util.RandomSource.create(42L), net.minecraftforge.client.model.data.ModelData.EMPTY)
                        .contains(net.minecraft.client.renderer.RenderType.translucent());
            } catch (RuntimeException e) {
                translucent = false;
            }
        }
        // Plants without collision (grass, ferns, flowers, crops: bushes) over a face darken it at level 0 (FarMesher)
        boolean plant = (f & FarNodes.THIN) != 0 && state.getBlock() instanceof net.minecraft.world.level.block.BushBlock;
        return (byte) (c | (tinted ? FarMesher.TINTED : 0) | (translucent ? FarMesher.TRANSLUCENT : 0) | (plant ? FarMesher.PLANT : 0));
    }
}
