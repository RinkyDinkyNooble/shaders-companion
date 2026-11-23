package com.rinkynooble.shaderscompanion.client.far;

import java.util.ArrayList;
import java.util.List;

/** The {@code /shaderscompanion lod stats} lines, worked out on the worker from its store. */
final class FarStats {
    private FarStats() {
    }

    static String[] lines(FarNodes store, long sectionsCaptured, double mainMicrosPerSection) {
        List<String> lines = new ArrayList<>();
        long explored = Math.max(1, store.explored.size());
        lines.add(String.format("Far store %s %s: %d sections stored (%d copied, %d of them unchanged), %d chunk columns explored, %d block states, %d biomes",
                store.world, store.dimension, store.sectionsStored, sectionsCaptured, store.sectionsUnchanged, store.explored.size(), store.states.size() - 2,
                store.biomes.size() - 1));
        lines.add(String.format("Time: main thread %.1f us a section copied; worker %.1f us a section stored, %.0f us a coarser node rebuilt (%.0f us an octant), %d rebuilds",
                mainMicrosPerSection, store.sectionsIngested == 0 ? 0.0 : store.ingestNanos / 1.0e3 / store.sectionsIngested,
                store.rebuilds == 0 ? 0.0 : store.rebuildNanos / 1.0e3 / store.rebuilds, store.folds == 0 ? 0.0 : store.foldNanos / 1.0e3 / store.folds, store.rebuilds));
        int[] inMemory = new int[Node.LEVELS];
        long[] knownVoxels = new long[Node.LEVELS];
        int[] uniform = new int[Node.LEVELS];
        int[] allAir = new int[Node.LEVELS];
        store.forEachNode(node -> {
            inMemory[node.level]++;
            knownVoxels[node.level] += node.known;
            // Nodes of one state and one light (all air in the sky, all stone deep down): what a compact form would save
            int first = node.state[0];
            byte light = node.light[0];
            boolean same = true;
            for (int i = 1; i < Node.VOXELS && same; i++) {
                same = node.state[i] == first && node.light[i] == light;
            }
            if (same) {
                uniform[node.level]++;
                if (first == StateIds.AIR) {
                    allAir[node.level]++;
                }
            }
        });
        for (int level = 0; level < Node.LEVELS; level++) {
            long bytes = inMemory[level] * Node.BYTES;
            lines.add(String.format("Level %d (%d blocks a voxel): %d nodes stored, 0 bytes on disk, %d nodes in memory (%.1f MB, %.0f%% of their voxels known, "
                            + "%d of one state and light, %d of them air), %d dropped, average blob n/a (no disk yet), %.1f KiB in memory per explored chunk column",
                    level, 1 << level, inMemory[level], inMemory[level], bytes / 1048576.0,
                    inMemory[level] == 0 ? 0.0 : 100.0 * knownVoxels[level] / ((double) inMemory[level] * Node.VOXELS), uniform[level], allAir[level],
                    store.dropped[level], bytes / 1024.0 / explored));
        }
        lines.add(String.format("Memory: %d nodes, %.1f MB of %d MB; %d coarser nodes waiting to rebuild", store.cachedNodes(), store.bytesInMemory() / 1048576.0,
                store.cacheMb(), store.rebuildsWaiting()));
        return lines.toArray(new String[0]);
    }
}
