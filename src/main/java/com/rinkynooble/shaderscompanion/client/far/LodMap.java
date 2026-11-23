package com.rinkynooble.shaderscompanion.client.far;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * The {@code /shaderscompanion lod map} picture of one level of the far store, for checking the coarser levels by eye:
 * one pixel per voxel column, north up, coloured like a vanilla map by the top known voxel that isn't air (its map
 * colour, a stand-in for M2's colour table; see-through blocks such as glass are looked through), shaded by its height
 * against the pixel to the north, water by its depth, unknown columns dark grey. Built and written on the worker
 * thread, from the nodes in memory.
 */
final class LodMap {
    private static final int UNKNOWN_COLOUR = 0x3A3A3A;
    private static final int EMPTY_COLOUR = 0x000000;
    private static final int UNKNOWN_BLOCK_COLOUR = 0xFF00FF;
    /** A state with no map colour (glass, barriers): the map looks through it. */
    private static final int SEE_THROUGH = -2;

    private LodMap() {
    }

    /**
     * Writes the map of a level over the blocks from (minX, minZ), widthX by widthZ blocks (whole voxels), to a PNG;
     * returns a line for the chat.
     */
    static String write(FarNodes nodes, int level, int minX, int minZ, int widthX, int widthZ, Path file) throws IOException {
        int voxel = 1 << level;
        int vx0 = Math.floorDiv(minX, voxel);
        int vz0 = Math.floorDiv(minZ, voxel);
        int w = Math.max(1, Math.floorDiv(minX + widthX - 1, voxel) - vx0 + 1);
        int h = Math.max(1, Math.floorDiv(minZ + widthZ - 1, voxel) - vz0 + 1);
        int nodeBlocks = Node.SIZE << level;
        int nodeY0 = Math.floorDiv(nodes.minY, nodeBlocks);
        int nodeY1 = Math.floorDiv(nodes.maxY - 1, nodeBlocks);
        // Per pixel: the top voxel's y (in voxels) and id, or MIN_VALUE when nothing known was found
        int[] top = new int[w * h];
        int[] topId = new int[w * h];
        boolean[] known = new boolean[w * h];
        Arrays.fill(top, Integer.MIN_VALUE);
        // Map colours by id (the worker assigns no ids while this runs)
        int[] colours = new int[nodes.states.size()];
        Arrays.fill(colours, -1);
        int nx0 = Math.floorDiv(vx0, Node.SIZE);
        int nz0 = Math.floorDiv(vz0, Node.SIZE);
        int nx1 = Math.floorDiv(vx0 + w - 1, Node.SIZE);
        int nz1 = Math.floorDiv(vz0 + h - 1, Node.SIZE);
        int columnsWithNodes = 0;
        for (int nz = nz0; nz <= nz1; nz++) {
            for (int nx = nx0; nx <= nx1; nx++) {
                boolean any = false;
                for (int ny = nodeY1; ny >= nodeY0; ny--) {
                    Node node = nodes.peek(level, nx, ny, nz);
                    if (node == null || node.known == 0) {
                        continue;
                    }
                    any = true;
                    for (int lz = 0; lz < Node.SIZE; lz++) {
                        int pz = nz * Node.SIZE + lz - vz0;
                        if (pz < 0 || pz >= h) {
                            continue;
                        }
                        for (int lx = 0; lx < Node.SIZE; lx++) {
                            int px = nx * Node.SIZE + lx - vx0;
                            if (px < 0 || px >= w || top[px + w * pz] != Integer.MIN_VALUE) {
                                continue;
                            }
                            for (int ly = Node.SIZE - 1; ly >= 0; ly--) {
                                int id = node.state[Node.index(lx, ly, lz)];
                                if (id == StateIds.UNKNOWN) {
                                    continue;
                                }
                                known[px + w * pz] = true;
                                if (id == StateIds.AIR || colour(nodes, id, colours) == SEE_THROUGH) {
                                    continue;
                                }
                                top[px + w * pz] = ny * Node.SIZE + ly;
                                topId[px + w * pz] = id;
                                break;
                            }
                        }
                    }
                }
                if (any) {
                    columnsWithNodes++;
                }
            }
        }
        int unknown = 0;
        int water = 0;
        try (NativeImage image = new NativeImage(w, h, false)) {
            for (int pz = 0; pz < h; pz++) {
                for (int px = 0; px < w; px++) {
                    int i = px + w * pz;
                    int rgb;
                    if (top[i] == Integer.MIN_VALUE) {
                        rgb = known[i] ? EMPTY_COLOUR : UNKNOWN_COLOUR;
                        if (!known[i]) {
                            unknown++;
                        }
                    } else if ((nodes.flags(topId[i]) & FarNodes.WATER) != 0) {
                        water++;
                        int depth = waterDepth(nodes, level, vx0 + px, top[i], vz0 + pz) * voxel;
                        rgb = shade(colour(nodes, topId[i], colours), depth < 5 ? 255 : depth < 10 ? 220 : depth < 20 ? 180 : 135);
                    } else {
                        int north = pz > 0 ? top[i - w] : Integer.MIN_VALUE;
                        int brightness = north == Integer.MIN_VALUE || north == top[i] ? 220 : top[i] > north ? 255 : 180;
                        rgb = shade(colour(nodes, topId[i], colours), brightness);
                    }
                    // NativeImage takes ABGR
                    image.setPixelRGBA(px, pz, 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF));
                }
            }
            Files.createDirectories(file.getParent());
            image.writeToFile(file);
        }
        return String.format("level %d: %d x %d voxels of %d blocks from %d, %d (%d node columns in memory): %d%% unknown, %d%% water",
                level, w, h, voxel, minX, minZ, columnsWithNodes, Math.round(100.0 * unknown / (w * h)), Math.round(100.0 * water / (w * h)));
    }

    /** Water voxels from the top one down (at most 64 blocks' worth), in voxels. */
    private static int waterDepth(FarNodes nodes, int level, int vx, int vy, int vz) {
        int depth = 0;
        int limit = Math.max(2, 64 >> level);
        while (depth < limit) {
            int y = vy - depth;
            Node node = nodes.peek(level, Math.floorDiv(vx, Node.SIZE), Math.floorDiv(y, Node.SIZE), Math.floorDiv(vz, Node.SIZE));
            if (node == null || (nodes.flags(node.state[Node.index(Math.floorMod(vx, Node.SIZE), Math.floorMod(y, Node.SIZE), Math.floorMod(vz, Node.SIZE))]) & FarNodes.WATER) == 0) {
                break;
            }
            depth++;
        }
        return depth;
    }

    /** A state's map colour (0xRRGGBB, or SEE_THROUGH), worked out once per id. */
    private static int colour(FarNodes nodes, int id, int[] colours) {
        if (colours[id] == -1) {
            colours[id] = colourOf(nodes, id);
        }
        return colours[id];
    }

    private static int colourOf(FarNodes nodes, int id) {
        BlockState state = nodes.states.state(id);
        if (state == null) {
            return UNKNOWN_BLOCK_COLOUR;
        }
        MapColor colour = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return colour == MapColor.NONE ? SEE_THROUGH : colour.col;
    }

    private static int shade(int rgb, int brightness) {
        int r = (rgb >> 16 & 0xFF) * brightness / 255;
        int g = (rgb >> 8 & 0xFF) * brightness / 255;
        int b = (rgb & 0xFF) * brightness / 255;
        return r << 16 | g << 8 | b;
    }
}
