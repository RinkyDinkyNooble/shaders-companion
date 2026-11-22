package com.rinkynooble.shaderscompanion.mixin;

import com.rinkynooble.shaderscompanion.client.DoorLight;
import com.rinkynooble.shaderscompanion.client.Lattice;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the lattice and the door light where blocks changed (every client block change passes here) and where chunk data arrived. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void shaderscompanion$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        Lattice.markBlock(pos);
        DoorLight.blockChanged(pos, oldState, newState);
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
    private void shaderscompanion$sectionChanged(int x, int y, int z, CallbackInfo ci) {
        Lattice.markSection(x, y, z);
        DoorLight.sectionChanged(x, y, z);
    }
}
