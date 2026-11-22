package com.rinkynooble.shaderscompanion.mixin;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.DoorLight;
import com.rinkynooble.shaderscompanion.client.LightLeaks;
import me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Embeddium's smooth lighting gives each corner of a face the average light of four cells in front of it: the cell the
 * face looks into, two beside that one and the one diagonal to it. A cell beside it may be a partial block (stairs, a
 * slab) whose stored light came in through its open sides; when its side toward the face's cell is closed (a stair's
 * flat back turned to a dark room), that light can't reach the face, yet Embeddium (like vanilla) averages it in, so
 * the floor, ceiling and walls along a wall of stairs glow with the daylight outside.
 * <p>
 * The light engine has already decided which sides are closed: light passing an open side between two cells loses at
 * most the receiving cell's light block (at least 1) per step. So a cell beside the face's cell with more light than
 * that (two steps for the diagonal one) can't have shared an open side with it, and when a block that closes sides by
 * its shape (stairs, slabs, {@code useShapeForLightOcclusion}) is among the cells around the face's cell, its light is
 * left out like a solid block's: Embeddium's corner blend then takes the lowest of the others in its place. Everywhere
 * else the light is exactly Embeddium's (the shape test keeps out the light engine's own small gaps, measured beside
 * lava at chunk borders).
 * <p>
 * A closed door's cell is the face's cell for the faces around the doorway (the wall's sides, the floor under the door,
 * the lintel): {@link DoorLight} gives it the light of the door's open side, and the cells beyond the door, outside, are
 * brighter than any light that crossed it. Those are left out too, or Embeddium's corner blend (which counts a dark cell
 * as an opaque one and takes the others' light in its place) lights the doorway with the outside's full light.
 * <p>
 * Embeddium counts mods with mixins into it as tainting it unless they depend on exactly one Embeddium version, so
 * mods.toml pins the version this is written against.
 */
@Mixin(targets = "me.jellysquid.mods.sodium.client.model.light.smooth.AoFaceData", remap = false)
public abstract class AoFaceDataMixin {
    /** Whether this face is checked: the fix is on and the face doesn't press against an opaque block. */
    @Unique
    private boolean shaderscompanion$active;
    /** Light samples read so far for this face. */
    @Unique
    private int shaderscompanion$samples;
    /** Block and sky light of the face's cell. */
    @Unique
    private int shaderscompanion$block;
    @Unique
    private int shaderscompanion$sky;
    /** The face's cell's light block beyond 1, or -1 until needed. */
    @Unique
    private int shaderscompanion$extraLoss;
    /** 1 when a block that closes sides by its shape is around the face's cell, 0 when none is, -1 until needed. */
    @Unique
    private int shaderscompanion$shapeNear;
    @Unique
    private Direction shaderscompanion$direction;
    @Unique
    private LightDataAccess shaderscompanion$cache;
    @Unique
    private final BlockPos.MutableBlockPos shaderscompanion$cell = new BlockPos.MutableBlockPos();
    @Unique
    private final BlockPos.MutableBlockPos shaderscompanion$near = new BlockPos.MutableBlockPos();

    /** The cell the face looks into, as Embeddium picks it. */
    @Inject(method = "initLightData", at = @At("HEAD"))
    private void shaderscompanion$faceCell(LightDataAccess cache, BlockPos pos, Direction direction, boolean offset, CallbackInfo ci) {
        shaderscompanion$active = LightLeaks.active;
        if (!shaderscompanion$active) {
            return;
        }
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (offset) {
            x += direction.getStepX();
            y += direction.getStepY();
            z += direction.getStepZ();
        }
        int word = cache.get(x, y, z);
        if (offset && LightDataAccess.unpackFO(word)) {
            // A face pressed against an opaque block: Embeddium takes the light of the face's own block, and the cells
            // beside the opaque one are a step further from it. Left as Embeddium has it
            shaderscompanion$active = false;
            return;
        }
        shaderscompanion$samples = 0;
        shaderscompanion$block = Math.max(LightDataAccess.unpackBL(word), LightDataAccess.unpackLU(word));
        shaderscompanion$sky = LightDataAccess.unpackSL(word);
        shaderscompanion$extraLoss = -1;
        shaderscompanion$shapeNear = -1;
        shaderscompanion$direction = direction;
        shaderscompanion$cache = cache;
        shaderscompanion$cell.set(x, y, z);
    }

    /**
     * Each of the face's light samples in Embeddium's order: the face's cell, the four beside it, then the diagonal ones
     * it reads (fewer when both cells between are opaque).
     */
    @Redirect(method = "initLightData", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/model/light/data/ArrayLightDataCache;getLightmap(I)I"))
    private int shaderscompanion$lightThroughOpenSides(int word) {
        int lightmap = LightDataAccess.getLightmap(word);
        if (!shaderscompanion$active) {
            return lightmap;
        }
        int sample = shaderscompanion$samples++;
        if (sample == 0) {
            return lightmap;
        }
        int steps = sample <= 4 ? 1 : 2;
        int block = Math.max(LightDataAccess.unpackBL(word), LightDataAccess.unpackLU(word)) - shaderscompanion$block;
        int sky = LightDataAccess.unpackSL(word) - shaderscompanion$sky;
        if (block <= steps && sky <= steps) {
            return lightmap;
        }
        // Rare from here: only beside a closed side, a cell that dims light by more than 1 (tinted glass) or a gap in the
        // light engine's data
        BlockAndTintGetter world = shaderscompanion$cache.getWorld();
        if (shaderscompanion$extraLoss < 0) {
            shaderscompanion$extraLoss = Math.max(0, world.getBlockState(shaderscompanion$cell).getLightBlock(world, shaderscompanion$cell) - 1);
        }
        int reach = steps + shaderscompanion$extraLoss;
        if (block <= reach && sky <= reach
                || !(DoorLight.closedDoorAt(shaderscompanion$cell.asLong()) || shaderscompanion$shapeNear(world))) {
            return lightmap;
        }
        LightLeaks.closedSamples.increment();
        if (LightLeaks.logBudget.get() > 0 && LightLeaks.logBudget.getAndDecrement() > 0) {
            ShadersCompanion.LOGGER.info("Light leak fix: sample {} of the {} face in front of {} ({}, block {} sky {}) left out: block +{} sky +{}", sample,
                    shaderscompanion$direction, shaderscompanion$cell.toShortString(), world.getBlockState(shaderscompanion$cell),
                    shaderscompanion$block, shaderscompanion$sky, block, sky);
        }
        return 0;
    }

    /** Whether a block that closes sides by its shape is one of the eight cells around the face's cell in its plane. */
    @Unique
    private boolean shaderscompanion$shapeNear(BlockAndTintGetter world) {
        if (shaderscompanion$shapeNear < 0) {
            shaderscompanion$shapeNear = 0;
            Direction.Axis axis = shaderscompanion$direction.getAxis();
            for (int a = -1; a <= 1 && shaderscompanion$shapeNear == 0; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (a == 0 && b == 0) {
                        continue;
                    }
                    int dx = axis == Direction.Axis.X ? 0 : a;
                    int dy = axis == Direction.Axis.Y ? 0 : axis == Direction.Axis.X ? a : b;
                    int dz = axis == Direction.Axis.Z ? 0 : b;
                    shaderscompanion$near.setWithOffset(shaderscompanion$cell, dx, dy, dz);
                    if (world.getBlockState(shaderscompanion$near).useShapeForLightOcclusion()) {
                        shaderscompanion$shapeNear = 1;
                        break;
                    }
                }
            }
        }
        return shaderscompanion$shapeNear == 1;
    }
}
