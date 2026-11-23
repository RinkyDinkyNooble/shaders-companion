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
 * The mesh map (research/lod/M2-PLAN.md, "Check without a GPU"): one level's nodes over a square meshed on the worker
 * (the mesher's code, run inline), their up-facing quads of the main ranges rasterised top-down, one pixel per voxel
 * column, north up, coloured from the colour table ({@link StateColors}, the map colour while a state has none yet),
 * shaded by height against the pixel to the north (water by its depth), as {@link LodMap} does for the same square.
 * Each pixel is also checked against the store's voxels: the top voxel the mesher's rules draw an up face for (an
 * unknown voxel over a solid one hides it) must be the top quad's height and state; and against LodMap's own rule (the
 * top known voxel that isn't air or see-through by its map colour), whose expected differences are counted by cause.
 */
final class FarMeshMap {
    private static final int UNKNOWN_COLOUR = 0x3A3A3A;
    private static final int NONE = Integer.MIN_VALUE;

    private FarMeshMap() {
    }

    static String write(FarNodes store, int level, int minX, int minZ, int widthX, int widthZ, Path file) throws IOException {
        long start = System.nanoTime();
        FarMesher.Mesher mesher = new FarMesher.Mesher(4L << 20);
        FarMesher.Pool pool = new FarMesher.Pool();
        int voxel = 1 << level;
        int vx0 = Math.floorDiv(minX, voxel);
        int vz0 = Math.floorDiv(minZ, voxel);
        int w = Math.max(1, Math.floorDiv(minX + widthX - 1, voxel) - vx0 + 1);
        int h = Math.max(1, Math.floorDiv(minZ + widthZ - 1, voxel) - vz0 + 1);
        int nodeBlocks = Node.SIZE << level;
        int nodeY0 = Math.floorDiv(store.minY, nodeBlocks);
        int nodeY1 = Math.floorDiv(store.maxY - 1, nodeBlocks);
        int n = w * h;
        // The mesh: the top up face's height (voxels) and palette entry, and the highest up face that isn't water
        int[] meshTop = new int[n];
        int[] meshEntry = new int[n];
        int[] meshFloor = new int[n];
        Arrays.fill(meshTop, NONE);
        Arrays.fill(meshFloor, NONE);
        // The store by the mesher's rules: the top drawn voxel's height and id (NONE: none; -1 height: unknown over it)
        int[] expectTop = new int[n];
        int[] expectId = new int[n];
        int[] lastClass = new int[n];
        boolean[] underUnknown = new boolean[n];
        Arrays.fill(expectTop, NONE);
        Arrays.fill(lastClass, FarMesher.AIR);
        // LodMap's rule: the top known voxel that isn't air or see-through
        int[] lodId = new int[n];
        int[] lodClass = new int[n];
        Arrays.fill(lodId, -1);
        byte[] classes = store.meshClasses();
        int classCount = store.states.size();
        int waterId = store.waterId();
        boolean fill = level >= FarMesher.fillFromLevel;
        int[] mapColours = new int[classCount + 16];
        Arrays.fill(mapColours, -1);
        int nx0 = Math.floorDiv(vx0, Node.SIZE);
        int nz0 = Math.floorDiv(vz0, Node.SIZE);
        int nx1 = Math.floorDiv(vx0 + w - 1, Node.SIZE);
        int nz1 = Math.floorDiv(vz0 + h - 1, Node.SIZE);
        int meshed = 0;
        int empty = 0;
        int absent = 0;
        long quads = 0;
        long upQuads = 0;
        long meshNanos = 0;
        for (int nz = nz0; nz <= nz1; nz++) {
            for (int nx = nx0; nx <= nx1; nx++) {
                for (int ny = nodeY1; ny >= nodeY0; ny--) {
                    long key = Node.key(level, nx, ny, nz);
                    MeshJob job = MeshJob.make(store, key, 0, k -> 0, pool);
                    if (job == MeshJob.ABSENT) {
                        absent++;
                    } else if (job == MeshJob.EMPTY) {
                        empty++;
                    } else {
                        long t0 = System.nanoTime();
                        NodeMesh mesh;
                        try {
                            mesh = mesher.mesh(job);
                        } catch (java.util.zip.DataFormatException e) {
                            throw new IOException(e);
                        }
                        meshNanos += System.nanoTime() - t0;
                        pool.give(job);
                        meshed++;
                        quads += mesh.quadCount;
                        int from = mesh.rangeStart[2];
                        int to = from + mesh.rangeCount[2];
                        upQuads += to - from;
                        for (int q = from; q < to; q++) {
                            int w0 = mesh.quads[q * 2];
                            int entry = mesh.palette[NodeMesh.quadPalette(mesh.quads[q * 2 + 1])];
                            int y = ny * Node.SIZE + NodeMesh.quadY(w0);
                            boolean water = NodeMesh.paletteState(entry) == waterId;
                            int qx = nx * Node.SIZE + NodeMesh.quadX(w0) - vx0;
                            int qz = nz * Node.SIZE + NodeMesh.quadZ(w0) - vz0;
                            for (int dz = 0; dz < NodeMesh.quadHeight(w0); dz++) {
                                int pz = qz + dz;
                                if (pz < 0 || pz >= h) {
                                    continue;
                                }
                                for (int dx = 0; dx < NodeMesh.quadWidth(w0); dx++) {
                                    int px = qx + dx;
                                    if (px < 0 || px >= w) {
                                        continue;
                                    }
                                    int p = px + w * pz;
                                    if (y > meshTop[p]) {
                                        meshTop[p] = y;
                                        meshEntry[p] = entry;
                                    }
                                    if (!water && y > meshFloor[p]) {
                                        meshFloor[p] = y;
                                    }
                                }
                            }
                        }
                    }
                    // The store's voxels of the same node, top down
                    Node node = store.read(level, nx, ny, nz);
                    for (int lz = 0; lz < Node.SIZE; lz++) {
                        int pz = nz * Node.SIZE + lz - vz0;
                        if (pz < 0 || pz >= h) {
                            continue;
                        }
                        for (int lx = 0; lx < Node.SIZE; lx++) {
                            int px = nx * Node.SIZE + lx - vx0;
                            if (px < 0 || px >= w) {
                                continue;
                            }
                            int p = px + w * pz;
                            if (expectTop[p] != NONE && lodId[p] >= 0) {
                                continue;
                            }
                            for (int ly = Node.SIZE - 1; ly >= 0; ly--) {
                                int i = Node.index(lx, ly, lz);
                                int id = node == null ? StateIds.UNKNOWN : node.state(i);
                                int light = node == null ? 0 : node.light(i);
                                int c = FarMesher.voxelClass(id, light, classes, classCount, fill);
                                if (expectTop[p] == NONE) {
                                    if (FarMesher.face(c, lastClass[p])) {
                                        // The first voxel from the top whose up face is drawn
                                        expectTop[p] = ny * Node.SIZE + ly;
                                        expectId[p] = c == FarMesher.WATER ? waterId : c == FarMesher.FILL ? store.stoneId() : id;
                                    } else if (FarMesher.draws(c) && lastClass[p] == FarMesher.UNKNOWN) {
                                        underUnknown[p] = true;
                                    }
                                    lastClass[p] = c;
                                }
                                if (lodId[p] < 0 && id != StateIds.UNKNOWN && id != StateIds.AIR && mapColour(store, id, mapColours) != -2) {
                                    lodId[p] = id;
                                    lodClass[p] = c;
                                }
                                if (expectTop[p] != NONE && lodId[p] >= 0) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        // Compare
        int match = 0;
        int missing = 0;
        int extra = 0;
        int height = 0;
        int state = 0;
        int unknownOver = 0;
        int lodSame = 0;
        int lodDecoration = 0;
        int lodSeeThrough = 0;
        int lodOther = 0;
        for (int p = 0; p < n; p++) {
            int e = expectTop[p];
            if (underUnknown[p]) {
                unknownOver++;
            }
            boolean hasMesh = meshTop[p] != NONE;
            boolean hasExpect = e != NONE;
            if (!hasMesh && !hasExpect) {
                match++;
            } else if (!hasMesh) {
                missing++;
            } else if (!hasExpect) {
                extra++;
            } else if (meshTop[p] != e) {
                height++;
            } else if (NodeMesh.paletteState(meshEntry[p]) != expectId[p]) {
                state++;
            } else {
                match++;
            }
            if (hasMesh && lodId[p] >= 0) {
                int lod = lodClass[p] == FarMesher.WATER ? waterId : lodId[p];
                int ms = NodeMesh.paletteState(meshEntry[p]);
                if (lod == ms) {
                    lodSame++;
                } else if (lodClass[p] == FarMesher.AIR) {
                    lodDecoration++;
                } else if (mapColour(store, ms, mapColours) == -2) {
                    lodSeeThrough++;
                } else {
                    lodOther++;
                }
            }
        }
        // The picture
        int fromTable = 0;
        try (NativeImage image = new NativeImage(w, h, false)) {
            for (int pz = 0; pz < h; pz++) {
                for (int px = 0; px < w; px++) {
                    int p = px + w * pz;
                    int rgb;
                    if (meshTop[p] == NONE) {
                        rgb = expectTop[p] == NONE && lodId[p] < 0 ? UNKNOWN_COLOUR : 0;
                    } else {
                        int entry = meshEntry[p];
                        int s = NodeMesh.paletteState(entry);
                        int colour = StateColors.upColour(s, NodeMesh.paletteBiome(entry));
                        if (colour >= 0) {
                            fromTable++;
                        } else {
                            colour = mapColour(store, s, mapColours);
                            colour = colour < 0 ? 0xFF00FF : colour;
                        }
                        if (s == waterId) {
                            int blocks = meshFloor[p] == NONE ? 64 : (meshTop[p] - meshFloor[p]) * voxel;
                            rgb = LodMapShade.shade(colour, blocks < 5 ? 255 : blocks < 10 ? 220 : blocks < 20 ? 180 : 135);
                        } else {
                            int north = pz > 0 ? meshTop[p - w] : NONE;
                            int brightness = north == NONE || north == meshTop[p] ? 220 : meshTop[p] > north ? 255 : 180;
                            rgb = LodMapShade.shade(colour, brightness);
                        }
                    }
                    image.setPixelRGBA(px, pz, 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF));
                }
            }
            Files.createDirectories(file.getParent());
            image.writeToFile(file);
        }
        return String.format("mesh map level %d: %d x %d voxels of %d blocks from %d, %d: %d nodes meshed (%d empty or uniform, %d not stored), %d quads (%d up), "
                        + "%.0f us a node; against the store's voxels: %d of %d pixels agree, %d missing, %d extra, %d at another height, %d another state (%d under unknown); "
                        + "against LodMap's top: %d the same, %d decorations the mesh leaves out, %d see-through blocks LodMap looks through, %d other; %d%% coloured from "
                        + "the colour table; %.0f ms", level, w, h, voxel, minX, minZ, meshed, empty, absent, quads, upQuads, meshed == 0 ? 0.0 : meshNanos / 1.0e3 / meshed,
                match, n, missing, extra, height, state, unknownOver, lodSame, lodDecoration, lodSeeThrough, lodOther, Math.round(100.0 * fromTable / Math.max(1, n)),
                (System.nanoTime() - start) / 1.0e6);
    }

    /** A state's map colour (0xRRGGBB), -2 for see-through (no map colour), worked out once per id. */
    private static int mapColour(FarNodes store, int id, int[] cache) {
        if (id < cache.length && cache[id] != -1) {
            return cache[id];
        }
        BlockState state = store.states.state(id);
        int colour;
        if (state == null) {
            colour = 0xFF00FF;
        } else {
            MapColor map = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            colour = map == MapColor.NONE ? -2 : map.col;
        }
        if (id < cache.length) {
            cache[id] = colour;
        }
        return colour;
    }

    /** LodMap's shading, shared. */
    private static final class LodMapShade {
        static int shade(int rgb, int brightness) {
            int r = (rgb >> 16 & 0xFF) * brightness / 255;
            int g = (rgb >> 8 & 0xFF) * brightness / 255;
            int b = (rgb & 0xFF) * brightness / 255;
            return r << 16 | g << 8 | b;
        }
    }
}
