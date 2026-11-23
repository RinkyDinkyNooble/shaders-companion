package com.rinkynooble.shaderscompanion.client.far;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;

/** The {@code /shaderscompanion lod stats} lines, worked out on the worker from its store, and the dev checks' compression measurement. */
final class FarStats {
    private FarStats() {
    }

    static String[] lines(FarNodes store, long sectionsCaptured, double mainMicrosPerSection) {
        List<String> lines = new ArrayList<>();
        long explored = Math.max(1, store.explored.size());
        FarDisk disk = store.disk;
        lines.add(String.format("Far store %s %s: %d sections stored in %d chunk columns (this session %d new, %d changed, %d unchanged, %d of them by the stored hash "
                        + "alone; %d copied), %d block states, %d biomes", store.world, store.dimension, store.sectionsStored, store.explored.size(), store.sessionNew,
                store.sessionChanged, store.sessionUnchanged, store.unchangedOnDisk, sectionsCaptured, store.states.size() - 2, store.biomes.size() - 1));
        lines.add(String.format("Changed sections: %d with other block states, %d only the light, %d only the biomes", store.changedStates,
                store.changedLightOnly, store.changedBiomesOnly));
        lines.add(String.format("Time: main thread %.1f us a section copied; worker %.1f us a section stored, %.0f us a coarser node rebuilt (%.0f us an octant), %d rebuilds",
                mainMicrosPerSection, store.sectionsIngested == 0 ? 0.0 : store.ingestNanos / 1.0e3 / store.sectionsIngested,
                store.rebuilds == 0 ? 0.0 : store.rebuildNanos / 1.0e3 / store.rebuilds, store.folds == 0 ? 0.0 : store.foldNanos / 1.0e3 / store.folds, store.rebuilds));
        if (disk != null) {
            lines.add(String.format("Disk time: %.0f us a node encoded (%d), %.0f us decoded (%d), %.0f us read; %d batches written, %.0f nodes and %.1f ms each (at most %.1f ms)",
                    disk.encodes == 0 ? 0.0 : disk.encodeNanos / 1.0e3 / disk.encodes, disk.encodes, disk.decodes == 0 ? 0.0 : disk.decodeNanos / 1.0e3 / disk.decodes,
                    disk.decodes, disk.decodes == 0 ? 0.0 : disk.readNanos / 1.0e3 / disk.decodes, disk.batches,
                    disk.batches == 0 ? 0.0 : (double) disk.batchNodes / disk.batches, disk.batches == 0 ? 0.0 : disk.writeNanos / 1.0e6 / disk.batches,
                    disk.batchMaxNanos / 1.0e6));
        }
        int[] inMemory = new int[Node.LEVELS];
        int[] full = new int[Node.LEVELS];
        int[] compactWithBiomes = new int[Node.LEVELS];
        int[] notStored = new int[Node.LEVELS];
        long[] knownVoxels = new long[Node.LEVELS];
        store.forEachNode(node -> {
            inMemory[node.level]++;
            knownVoxels[node.level] += node.known;
            if (!node.compact()) {
                full[node.level]++;
            } else if (node.biome != null) {
                compactWithBiomes[node.level]++;
            }
            if (!node.stored && node.known > 0) {
                notStored[node.level]++;
            }
        });
        long totalDisk = 0;
        for (int level = 0; level < Node.LEVELS; level++) {
            long blobs = disk == null ? 0 : disk.blobs[level];
            long uniform = disk == null ? 0 : disk.uniform[level];
            long blobBytes = disk == null ? 0 : disk.blobBytes[level];
            long fileBytes = disk == null ? 0 : disk.levelFileBytes[level];
            totalDisk += fileBytes;
            lines.add(String.format("Level %d (%d blocks a voxel): %d nodes stored (%d blobs averaging %.0f bytes, %d uniform), %.1f MB on disk in %d files "
                            + "(blobs %.1f MB; in 4 KiB sectors they would take %.1f MB), %.0f bytes on disk per explored chunk column; %d nodes in memory "
                            + "(%d full, %d compact, %d of them with biome arrays; %.0f%% of their voxels known)",
                    level, 1 << level, blobs + uniform + notStored[level], blobs, blobs == 0 ? 0.0 : (double) blobBytes / blobs, uniform, fileBytes / 1048576.0,
                    disk == null ? 0 : disk.levelFiles[level], blobBytes / 1048576.0, disk == null ? 0.0 : disk.sectorsLarge[level] * 4096 / 1048576.0,
                    (double) fileBytes / explored, inMemory[level], full[level], inMemory[level] - full[level], compactWithBiomes[level],
                    inMemory[level] == 0 ? 0.0 : 100.0 * knownVoxels[level] / ((double) inMemory[level] * Node.VOXELS)));
        }
        long lookups = store.hits + store.misses;
        lines.add(String.format("Memory: %d nodes, %.1f MB of %d MB (a full node %.0f KB, a compact one %d bytes, %d with a biome array); cache hit rate %.1f%% "
                        + "(%d lookups, %d read from disk, %d made new); %d nodes written; %d coarser nodes waiting to rebuild",
                store.cachedNodes(), store.bytesInMemory() / 1048576.0, store.cacheMb(), Node.FULL_BYTES / 1000.0, Node.COMPACT_BYTES,
                Node.COMPACT_BYTES + Node.BIOME_BYTES, lookups == 0 ? 0.0 : 100.0 * store.hits / lookups, lookups, store.loads, store.creates, store.written,
                store.rebuildsWaiting()));
        if (disk != null) {
            lines.add(String.format("Disk: %.1f MB in %d region files (%.0f bytes per explored chunk column, all levels; Distant Horizons: 3,277 at 4,096 blocks), "
                            + "%d damaged slots dropped", totalDisk / 1048576.0, disk.regionFiles, (double) totalDisk / explored, disk.damaged));
        } else if (store.diskError != null) {
            lines.add("Disk: none (" + store.diskError + "); nodes leaving memory are lost");
        }
        return lines.toArray(new String[0]);
    }

    /**
     * Dev checks: the stored nodes of every level (level 0 every 16th, level 1 every 4th) decoded and encoded again with
     * Deflater levels 1, 4 and 6 and index bits rounded to powers of two or exact; per level the bytes before and after
     * the deflater and the encoding time, each node encoded by every setting in turn.
     */
    static String[] compression(FarNodes store) throws IOException {
        List<String> lines = new ArrayList<>();
        if (store.disk == null) {
            lines.add("No disk");
            return lines.toArray(new String[0]);
        }
        int[] deflaterLevels = {1, 4, 6};
        boolean[] pow2 = {true, false};
        int configs = deflaterLevels.length * pow2.length;
        NodeCodec[] codecs = new NodeCodec[configs];
        for (int c = 0; c < configs; c++) {
            codecs[c] = new NodeCodec(deflaterLevels[c / 2]);
            codecs[c].pow2Bits = pow2[c % 2];
        }
        NodeCodec decoder = new NodeCodec(NodeCodec.DEFAULT_LEVEL);
        for (int level = 0; level < Node.LEVELS; level++) {
            long[] bytes = new long[configs];
            long[] raw = new long[configs];
            long[] nanos = new long[configs];
            int[] count = new int[1];
            int[] uniform = new int[1];
            int lvl = level;
            store.disk.forEachStored(level, level == 0 ? 16 : level == 1 ? 4 : 1, (key, blob) -> {
                if (blob == null) {
                    uniform[0]++;
                    return;
                }
                Node node = new Node(lvl, Node.keyX(key), Node.keyY(key), Node.keyZ(key));
                try {
                    decoder.decode(blob, node);
                } catch (DataFormatException e) {
                    return;
                }
                for (int c = 0; c < configs; c++) {
                    long start = System.nanoTime();
                    byte[] encoded = codecs[c].encode(node);
                    nanos[c] += System.nanoTime() - start;
                    bytes[c] += encoded == null ? 0 : encoded.length;
                    raw[c] += codecs[c].lastRawLength();
                }
                count[0]++;
            });
            if (count[0] == 0) {
                continue;
            }
            StringBuilder line = new StringBuilder(String.format("Compression, level %d (%d blobs measured, %d uniform skipped):", level, count[0], uniform[0]));
            for (int c = 0; c < configs; c++) {
                line.append(String.format(" deflater %d %s bits %.0f -> %.0f bytes in %.0f us;", deflaterLevels[c / 2], pow2[c % 2] ? "pow2" : "exact",
                        (double) raw[c] / count[0], (double) bytes[c] / count[0], nanos[c] / 1.0e3 / count[0]));
            }
            lines.add(line.toString());
        }
        return lines.toArray(new String[0]);
    }
}
