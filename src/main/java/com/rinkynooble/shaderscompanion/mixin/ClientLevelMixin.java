package com.rinkynooble.shaderscompanion.mixin;

import com.rinkynooble.shaderscompanion.client.DoorLight;
import com.rinkynooble.shaderscompanion.client.Lattice;
import com.rinkynooble.shaderscompanion.client.far.FarStore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the lattice, the door light and the far store where blocks changed (every client block change passes here)
 * and where chunk data arrived.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void shaderscompanion$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        Lattice.markBlock(pos);
        DoorLight.blockChanged(pos, oldState, newState);
        FarStore.blockChanged((ClientLevel) (Object) this, pos);
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
    private void shaderscompanion$sectionChanged(int x, int y, int z, CallbackInfo ci) {
        Lattice.markSection(x, y, z);
        DoorLight.sectionChanged(x, y, z);
        FarStore.sectionChanged((ClientLevel) (Object) this, x, y, z);
    }
}
