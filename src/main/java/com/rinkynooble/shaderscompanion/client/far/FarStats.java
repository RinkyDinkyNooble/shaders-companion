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
        lines.add(String.format("Changed sections: %d with other block states, %d only the light, %d only the biomes; %d voxels left out (farHiddenBlocks)", store.changedStates,
                store.changedLightOnly, store.changedBiomesOnly, store.configHiddenVoxels));
        lines.add(String.format("Hidden voxels: %d sections looked at, %d voxels replaced (%.0f a section), %d more in the faces of sections next to new ones, "
                        + "%.1f us a section; %d sections marked stale (a voxel next to them opened), %d of them stored again; node borders: %d face masks read "
                        + "from disk, %d kept as nodes left memory, %d reused", store.hiddenSections, store.hiddenReplaced, store.hiddenSections == 0 ? 0.0 : (double) store.hiddenReplaced / store.hiddenSections,
                store.hiddenLate, store.hiddenSections == 0 ? 0.0 : store.hiddenNanos / 1.0e3 / store.hiddenSections, store.staleMarked, store.staleRestored,
                store.faceMaskReads, store.faceMasksKept, store.faceMaskHits));
        lines.add(String.format("Sky light repair: %d voxels raised in sections as stored, %d in stored sections next to them, %.1f us a section stored", store.skyRaised,
                store.skyRaisedNext, store.sectionsIngested == 0 ? 0.0 : store.skyNanos / 1.0e3 / store.sectionsIngested));
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
        lines.addAll(hiddenEstimate(store));
        return lines.toArray(new String[0]);
    }

    /**
     * Dev checks (M1c): the worker's running counters, in the order of {@link FarStore#countersSummary}'s reading; the
     * longest batch is the longest since the last call (then reset).
     */
    static long[] counters(FarNodes store) {
        FarDisk disk = store.disk;
        long[] c = {store.sectionsIngested, store.ingestNanos, store.sessionNew, store.sessionChanged, store.sessionUnchanged, store.rebuilds,
                store.rebuildNanos, store.folds, store.foldNanos, store.hits, store.misses, store.loads, store.creates, store.written,
                disk == null ? 0 : disk.encodes, disk == null ? 0 : disk.encodeNanos, disk == null ? 0 : disk.decodes, disk == null ? 0 : disk.decodeNanos,
                disk == null ? 0 : disk.readNanos, disk == null ? 0 : disk.batches, disk == null ? 0 : disk.batchNodes, disk == null ? 0 : disk.writeNanos,
                disk == null ? 0 : disk.windowBatchMaxNanos, store.hiddenReplaced, store.hiddenNanos, store.hiddenSections};
        if (disk != null) {
            disk.windowBatchMaxNanos = 0;
        }
        return c;
    }

    /**
     * Dev checks (M1c): every 4th stored level-0 node against the same node of a store written without replacing hidden
     * voxels (another folder, an import of the same save; its own ids, so states are compared, not hashes). Chunk
     * columns in the square {@code skip} (x0, z0, x1, z1: the ones copied live, where the world moves on between runs)
     * are left out. Every voxel whose state or light differs must have six opaque neighbours in the store as captured
     * (a neighbour in the skipped square makes it "unverifiable"); one with an open face is a fault, logged.
     */
    static String[] compareWithRaw(FarNodes store, java.nio.file.Path rawFolder, int[] skip) throws IOException {
        List<String> lines = new ArrayList<>();
        StateIds rawStates = new StateIds();
        FarDisk raw = new FarDisk(rawFolder, rawStates, new BiomeIds(), store.minY, store.maxY);
        raw.open();
        long[] counts = new long[8];
        // 0 nodes compared, 1 sections compared, 2 sections whose content differs, 3 voxels differing, 4 of them hidden
        // in the raw store, 5 with an open face (faults), 6 unverifiable, 7 sections stale in the store
        java.util.Map<Long, Node> rawCache = new java.util.LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Long, Node> eldest) {
                return size() > 48;
            }
        };
        byte[] rawOpaque = new byte[rawStates.size()];
        try {
            store.disk.forEachStored(0, 4, (key, blob) -> {
                int nx = Node.keyX(key);
                int ny = Node.keyY(key);
                int nz = Node.keyZ(key);
                Node norm = store.read(0, nx, ny, nz);
                Node r = raw.load(key);
                if (norm == null || r == null) {
                    return;
                }
                counts[0]++;
                for (int slot = 0; slot < 8; slot++) {
                    long h = norm.sectionHash[slot];
                    if (h == 0 || r.sectionHash[slot] == 0) {
                        continue;
                    }
                    if (h == FarNodes.STALE) {
                        counts[7]++;
                        continue;
                    }
                    if (inSquare(skip, nx * 2 + (slot & 1), nz * 2 + (slot >> 2 & 1))) {
                        counts[2]++;
                        continue;
                    }
                    counts[1]++;
                    int ox = (slot & 1) * 16;
                    int oy = (slot >> 1 & 1) * 16;
                    int oz = (slot >> 2 & 1) * 16;
                    for (int y = oy; y < oy + 16; y++) {
                        for (int z = oz; z < oz + 16; z++) {
                            for (int x = ox; x < ox + 16; x++) {
                                int i = Node.index(x, y, z);
                                net.minecraft.world.level.block.state.BlockState a = rawStates.state(r.state(i));
                                net.minecraft.world.level.block.state.BlockState b = store.states.state(norm.state(i));
                                boolean sameState = a == b || a != null && b != null && a.isAir() && b.isAir();
                                if (sameState && r.light(i) == norm.light(i)) {
                                    continue;
                                }
                                counts[3]++;
                                // The six neighbours in the store as captured
                                int verdict = 1;
                                int[][] dirs = DIRS;
                                for (int[] d : dirs) {
                                    int wx = nx * 32 + x + d[0];
                                    int wy = ny * 32 + y + d[1];
                                    int wz = nz * 32 + z + d[2];
                                    Node rn = nodeFor(raw, null, rawCache, wx, wy, wz, r, nx, ny, nz);
                                    if (inSquare(skip, wx >> 4, wz >> 4)) {
                                        verdict = Math.min(verdict, 0);
                                        continue;
                                    }
                                    if (rn == null) {
                                        verdict = -1;
                                        break;
                                    }
                                    int id = rn.state(Node.index(wx & 31, wy & 31, wz & 31));
                                    if (!rawOpaque(rawStates, rawOpaque, id)) {
                                        verdict = -1;
                                        break;
                                    }
                                }
                                if (verdict > 0) {
                                    counts[4]++;
                                } else if (verdict == 0) {
                                    counts[6]++;
                                } else {
                                    counts[5]++;
                                    if (counts[5] <= 20) {
                                        lines.add(String.format("Fault: %d %d %d stored %s (light %d), as captured %s (light %d)", nx * 32 + x, ny * 32 + y, nz * 32 + z, b,
                                                norm.light(i), a, r.light(i)));
                                    }
                                }
                            }
                        }
                    }
                }
            });
        } finally {
            raw.close();
        }
        lines.add(0, String.format("Hidden voxels against the store as captured (%s, every 4th level-0 node): %d nodes, %d sections compared (%d left out: copied "
                        + "live; %d stale); %d voxels differ: %d hidden as captured, %d with an open face, %d next to a section copied live",
                rawFolder.getFileName(), counts[0], counts[1], counts[2], counts[7], counts[3], counts[4], counts[5], counts[6]));
        return lines.toArray(new String[0]);
    }

    private static boolean inSquare(int[] square, int chunkX, int chunkZ) {
        return chunkX >= square[0] && chunkX <= square[2] && chunkZ >= square[1] && chunkZ <= square[3];
    }

    /** The level-0 node holding a block position, from {@code here} when it is that node, else a small cache, else the store or the disk. */
    private static Node nodeFor(FarDisk disk, FarNodes store, java.util.Map<Long, Node> cache, int wx, int wy, int wz, Node here, int nx, int ny, int nz) {
        int x = wx >> 5;
        int y = wy >> 5;
        int z = wz >> 5;
        if (x == nx && y == ny && z == nz) {
            return here;
        }
        long key = Node.key(0, x, y, z);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Node node;
        try {
            node = disk != null ? disk.load(key) : store.read(0, x, y, z);
        } catch (IOException e) {
            node = null;
        }
        cache.put(key, node);
        return node;
    }

    private static boolean rawOpaque(StateIds states, byte[] cache, int id) {
        if (id <= 0 || id >= cache.length) {
            return false;
        }
        if (cache[id] == 0) {
            net.minecraft.world.level.block.state.BlockState state = states.state(id);
            boolean opaque;
            try {
                opaque = state != null && state.isSolidRender(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
            } catch (RuntimeException e) {
                opaque = false;
            }
            cache[id] = (byte) (opaque ? 1 : 2);
        }
        return cache[id] == 1;
    }

    /**
     * Dev checks (M1c): stored sections against the client's world (main thread's copies: each section's states and,
     * per voxel, whether a face of it is open in the world). A voxel stored as another state than the world's must have
     * no open face; one that has is a fault (logged; a block that changed since it was copied shows up here too).
     */
    static String[] compareWithWorld(FarNodes store, List<long[]> positions, List<net.minecraft.world.level.block.state.BlockState[]> worldStates,
                                     List<long[]> exposedBits) {
        List<String> lines = new ArrayList<>();
        long sections = 0;
        long missing = 0;
        long differ = 0;
        long hiddenDiffer = 0;
        long faults = 0;
        for (int k = 0; k < positions.size(); k++) {
            long[] p = positions.get(k);
            int sx = (int) p[0];
            int sy = (int) p[1];
            int sz = (int) p[2];
            Node node = store.read(0, sx >> 1, sy >> 1, sz >> 1);
            if (node == null || node.sectionHash[Node.sectionSlot(sx, sy, sz)] == 0) {
                missing++;
                continue;
            }
            sections++;
            net.minecraft.world.level.block.state.BlockState[] world = worldStates.get(k);
            long[] exposed = exposedBits.get(k);
            int ox = (sx & 1) * 16;
            int oy = (sy & 1) * 16;
            int oz = (sz & 1) * 16;
            int i = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++, i++) {
                        net.minecraft.world.level.block.state.BlockState stored = store.states.state(node.state(Node.index(ox + x, oy + y, oz + z)));
                        net.minecraft.world.level.block.state.BlockState w = world[i];
                        if (stored == w || stored != null && stored.isAir() && w.isAir()) {
                            continue;
                        }
                        differ++;
                        if ((exposed[i >> 6] & 1L << (i & 63)) == 0) {
                            hiddenDiffer++;
                        } else {
                            faults++;
                            if (faults <= 10) {
                                lines.add(String.format("Fault: %d %d %d stored %s, in the world %s (a face open)", sx * 16 + x, sy * 16 + y, sz * 16 + z, stored, w));
                            }
                        }
                    }
                }
            }
        }
        lines.add(0, String.format("Hidden voxels against the world: %d sections compared (%d not stored); %d voxels differ: %d with no open face, %d with an open face",
                sections, missing, differ, hiddenDiffer, faults));
        return lines.toArray(new String[0]);
    }

    private static final String[] HIDDEN_MODES = {"as stored", "within the section", "and the sections above and below", "within the node",
            "and the nodes around", "and the nodes west, below and north"};

    /**
     * Dev checks (M1c): every 4th stored level-0 blob encoded again with the voxels whose six neighbours are all opaque
     * replaced by the most common such state of their section (light 0), for each way of treating section borders: a
     * neighbour outside the voxel's section unknown (not opaque); the sections above and below known too; every
     * section of the node known; the nodes around known as well (read from disk). Bytes after the deflater, the share
     * of voxels replaced and the palette size.
     */
    static List<String> hiddenEstimate(FarNodes store) throws IOException {
        List<String> lines = new ArrayList<>();
        int modes = HIDDEN_MODES.length;
        long[] bytes = new long[modes];
        long[] exactBytes = new long[modes];
        long[] wide = new long[modes];
        long[] replaced = new long[modes];
        long[] palette = new long[modes];
        int[] count = new int[1];
        NodeCodec decoder = new NodeCodec(NodeCodec.DEFAULT_LEVEL);
        NodeCodec encoder = new NodeCodec(NodeCodec.DEFAULT_LEVEL);
        NodeCodec exact = new NodeCodec(NodeCodec.DEFAULT_LEVEL);
        exact.pow2Bits = false;
        int pad = Node.SIZE + 2;
        boolean[] opaque = new boolean[pad * pad * pad];
        int[] state = new int[Node.VOXELS];
        byte[] light = new byte[Node.VOXELS];
        int[] votes = new int[Math.max(256, store.states.size())];
        long start = System.nanoTime();
        store.disk.forEachStored(0, 4, (key, blob) -> {
            if (blob == null) {
                return;
            }
            Node node = new Node(0, Node.keyX(key), Node.keyY(key), Node.keyZ(key));
            try {
                decoder.decode(blob, node);
            } catch (DataFormatException e) {
                return;
            }
            node.expand();
            // Opacity with a one-voxel border from the six nodes around (false where none is stored)
            java.util.Arrays.fill(opaque, false);
            for (int y = 0; y < Node.SIZE; y++) {
                for (int z = 0; z < Node.SIZE; z++) {
                    for (int x = 0; x < Node.SIZE; x++) {
                        opaque[(x + 1) + pad * ((z + 1) + pad * (y + 1))] = (store.flags(node.state[Node.index(x, y, z)]) & FarNodes.OPAQUE) != 0;
                    }
                }
            }
            int[][] dirs = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
            for (int[] d : dirs) {
                Node next = store.read(0, node.x + d[0], node.y + d[1], node.z + d[2]);
                if (next == null) {
                    continue;
                }
                for (int a = 0; a < Node.SIZE; a++) {
                    for (int b = 0; b < Node.SIZE; b++) {
                        int x = d[0] < 0 ? 31 : d[0] > 0 ? 0 : a;
                        int y = d[1] < 0 ? 31 : d[1] > 0 ? 0 : d[0] != 0 ? a : b;
                        int z = d[2] < 0 ? 31 : d[2] > 0 ? 0 : b;
                        int px = d[0] < 0 ? 0 : d[0] > 0 ? pad - 1 : x + 1;
                        int py = d[1] < 0 ? 0 : d[1] > 0 ? pad - 1 : y + 1;
                        int pz = d[2] < 0 ? 0 : d[2] > 0 ? pad - 1 : z + 1;
                        opaque[px + pad * (pz + pad * py)] = (store.flags(next.state(Node.index(x, y, z))) & FarNodes.OPAQUE) != 0;
                    }
                }
            }
            for (int mode = 0; mode < modes; mode++) {
                System.arraycopy(node.state, 0, state, 0, Node.VOXELS);
                System.arraycopy(node.light, 0, light, 0, Node.VOXELS);
                if (mode > 0) {
                    for (int s = 0; s < 8; s++) {
                        int sx = (s & 1) * 16;
                        int sy = (s >> 1 & 1) * 16;
                        int sz = (s >> 2 & 1) * 16;
                        int fill = 0;
                        int best = 0;
                        // Twice: count the hidden opaque states, then replace with the most common
                        for (int pass = 0; pass < 2; pass++) {
                            for (int y = sy; y < sy + 16; y++) {
                                for (int z = sz; z < sz + 16; z++) {
                                    for (int x = sx; x < sx + 16; x++) {
                                        if (!hiddenFor(opaque, pad, mode, x, y, z)) {
                                            continue;
                                        }
                                        int i = Node.index(x, y, z);
                                        if (pass == 0) {
                                            int id = node.state[i];
                                            if ((store.flags(id) & FarNodes.OPAQUE) != 0) {
                                                if (id >= votes.length) {
                                                    continue;
                                                }
                                                if (++votes[id] > best) {
                                                    best = votes[id];
                                                    fill = id;
                                                }
                                            }
                                        } else if (fill != 0) {
                                            if (state[i] != fill || light[i] != 0) {
                                                replaced[mode]++;
                                            }
                                            state[i] = fill;
                                            light[i] = 0;
                                        }
                                    }
                                }
                            }
                            if (pass == 0) {
                                java.util.Arrays.fill(votes, 0);
                            }
                        }
                    }
                }
                Node copy = new Node(0, node.x, node.y, node.z);
                copy.state = state;
                copy.light = light;
                copy.biome = node.biome;
                copy.uniformBiome = node.uniformBiome;
                byte[] encoded = encoder.encode(copy);
                bytes[mode] += encoded == null ? 0 : encoded.length;
                byte[] encodedExact = exact.encode(copy);
                exactBytes[mode] += encodedExact == null ? 0 : encodedExact.length;
                java.util.BitSet seen = new java.util.BitSet();
                for (int i = 0; i < Node.VOXELS; i++) {
                    seen.set(state[i]);
                }
                palette[mode] += seen.cardinality();
                if (seen.cardinality() > 16) {
                    wide[mode]++;
                }
            }
            count[0]++;
        });
        if (count[0] == 0) {
            return lines;
        }
        StringBuilder line = new StringBuilder(String.format("Hidden voxels, level 0 (%d blobs, %.1f s):", count[0], (System.nanoTime() - start) / 1.0e9));
        for (int mode = 0; mode < modes; mode++) {
            line.append(String.format(" %s %.0f bytes, exact bits %.0f (%.1f%% replaced, %.1f states a node, %.1f%% of nodes over 16);", HIDDEN_MODES[mode],
                    (double) bytes[mode] / count[0], (double) exactBytes[mode] / count[0], 100.0 * replaced[mode] / ((double) count[0] * Node.VOXELS),
                    (double) palette[mode] / count[0], 100.0 * wide[mode] / count[0]));
        }
        lines.add(line.toString());
        return lines;
    }

    /** Whether all six neighbours of a node voxel are opaque, counting only the neighbours the mode knows. */
    private static final int[][] DIRS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    private static boolean hiddenFor(boolean[] opaque, int pad, int mode, int x, int y, int z) {
        for (int[] d : DIRS) {
            int nx = x + d[0];
            int ny = y + d[1];
            int nz = z + d[2];
            boolean inNode = nx >= 0 && nx < 32 && ny >= 0 && ny < 32 && nz >= 0 && nz < 32;
            boolean sameSection = inNode && nx >> 4 == x >> 4 && ny >> 4 == y >> 4 && nz >> 4 == z >> 4;
            boolean known = switch (mode) {
                case 1 -> sameSection;
                case 2 -> sameSection || d[1] != 0;
                case 3 -> inNode;
                case 5 -> inNode || d[0] < 0 || d[1] < 0 || d[2] < 0;
                default -> true;
            };
            if (!known || !opaque[(nx + 1) + pad * ((nz + 1) + pad * (ny + 1))]) {
                return false;
            }
        }
        return true;
    }
}
