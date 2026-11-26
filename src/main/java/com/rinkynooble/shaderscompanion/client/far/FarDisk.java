package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import net.minecraft.world.level.ChunkPos;

/**
 * The far store's folder for one world and dimension ({@code <game dir>/shaderscompanion/lod/<world>/<dimension>/},
 * M1-PLAN "Disk"): {@code format.txt}, {@code states.txt}, {@code biomes.txt} and the region files
 * {@code L<level>/r.<rx>.<rz>.scf} ({@link FarRegion}). Nodes to write are encoded at once ({@link NodeCodec}) and wait
 * in a batch, written together (new ids first, then every region's blobs and entries), when the batch is large, every
 * few seconds and when the store closes; a read looks in the batch first. On the worker thread only.
 */
final class FarDisk {
    /** The folder's format: another number in format.txt starts the folder over (region files and ids). */
    static final int FORMAT = 1;
    /** Bytes per sector of the region files (part of the format; research/lod/M1.md compares 4 KiB). */
    static final int SECTOR_BYTES = 512;
    /** Region files kept open (least recently used closed first). */
    private static final int OPEN_REGIONS = 48;
    /** A batch is written once its blobs hold this much. */
    static final long BATCH_BYTES = 16L << 20;
    /** Force each batch to disk between the blobs and the entries (a power cut can't damage stored nodes either). */
    private static final boolean FORCE = true;

    /** A node waiting in the batch: its blob, or the values of a uniform node, and its section hashes at level 0. */
    record Pending(byte[] blob, int uniformState, short uniformBiome, byte uniformLight, long[] hashes) {
        long bytes() {
            return 96 + (blob != null ? blob.length : 0);
        }
    }

    final Path folder;
    private final StateIds states;
    private final BiomeIds biomes;
    private final int minY;
    private final int maxY;
    final NodeCodec codec = new NodeCodec(NodeCodec.DEFAULT_LEVEL);
    private final Long2ObjectLinkedOpenHashMap<FarRegion> open = new Long2ObjectLinkedOpenHashMap<>();
    /** Region files on disk, by {@link FarRegion#key}. */
    private final LongOpenHashSet existing = new LongOpenHashSet();
    private final Long2ObjectLinkedOpenHashMap<Pending> batch = new Long2ObjectLinkedOpenHashMap<>();
    private long batchBytes;
    private final boolean[] needsNewline = new boolean[2];

    // What the folder held when opened (FarNodes takes these over)
    long sectionsOnDisk;
    final LongOpenHashSet exploredOnDisk = new LongOpenHashSet();

    // Statistics
    final long[] blobs = new long[Node.LEVELS];
    final long[] blobBytes = new long[Node.LEVELS];
    final long[] uniform = new long[Node.LEVELS];
    /** Sectors the blobs take at 512 bytes and at 4 KiB a sector (to compare sector sizes from the same blobs). */
    final long[] sectorsSmall = new long[Node.LEVELS];
    final long[] sectorsLarge = new long[Node.LEVELS];
    long fileBytes;
    int regionFiles;
    final long[] levelFileBytes = new long[Node.LEVELS];
    final int[] levelFiles = new int[Node.LEVELS];
    int damaged;
    long encodes;
    long encodeNanos;
    long decodes;
    long decodeNanos;
    long readNanos;
    long batches;
    long batchNodes;
    long writeNanos;
    long batchMaxNanos;
    /** Dev checks: the longest batch since the counters were last read (FarStats.counters). */
    long windowBatchMaxNanos;

    FarDisk(Path folder, StateIds states, BiomeIds biomes, int minY, int maxY) {
        this.folder = folder;
        this.states = states;
        this.biomes = biomes;
        this.minY = minY;
        this.maxY = maxY;
    }

    static int nodeY0(int minY, int level) {
        return Math.floorDiv(minY, Node.SIZE << level);
    }

    int nodeY0(int level) {
        return nodeY0(minY, level);
    }

    int ys(int level) {
        return Math.floorDiv(maxY - 1, Node.SIZE << level) - nodeY0(level) + 1;
    }

    /**
     * Opens the folder: starts it over when its format differs, loads the ids and reads every region file's table
     * (the statistics, and at level 0 which sections and chunk columns are stored).
     */
    void open() throws IOException {
        Files.createDirectories(folder);
        Path format = folder.resolve("format.txt");
        String wanted = Integer.toString(FORMAT);
        String found = Files.exists(format) ? firstLine(format) : null;
        boolean anything;
        try (Stream<Path> list = Files.list(folder)) {
            anything = list.anyMatch(path -> !path.getFileName().toString().equals("format.txt"));
        }
        if (wanted.equals(found) && !Files.exists(folder.resolve("states.txt")) && Files.isDirectory(folder.resolve("L0"))) {
            // Nodes without the list of what their ids mean can't be read
            ShadersCompanion.LOGGER.info("Shaders Companion far store: {} has region files but no states.txt; starting it over", folder);
            wipe();
        } else if (!wanted.equals(found)) {
            if (anything) {
                ShadersCompanion.LOGGER.info("Shaders Companion far store: {} has format {} (this version writes {}); starting it over", folder, found, wanted);
                wipe();
            }
            Files.writeString(format, wanted + "\nShaders Companion far store (research/lod/M1-PLAN.md, \"Disk\"): region files of 8 x 8 node columns, "
                    + SECTOR_BYTES + "-byte sectors, Deflater blobs; states.txt and biomes.txt number the block states and biomes (line n = id n).\n");
        }
        states.load(readLines(folder.resolve("states.txt"), 0));
        biomes.load(readLines(folder.resolve("biomes.txt"), 1));
        for (int level = 0; level < Node.LEVELS; level++) {
            Path dir = folder.resolve("L" + level);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "r.*.scf")) {
                for (Path file : files) {
                    String[] parts = file.getFileName().toString().split("\\.");
                    if (parts.length != 4) {
                        continue;
                    }
                    int rx;
                    int rz;
                    try {
                        rx = Integer.parseInt(parts[1]);
                        rz = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    try (FarRegion region = new FarRegion(file, level, rx, rz, nodeY0(level), ys(level), SECTOR_BYTES)) {
                        existing.add(FarRegion.key(level, rx, rz));
                        regionFiles++;
                        fileBytes += region.size();
                        levelFiles[level]++;
                        levelFileBytes[level] += region.size();
                        damaged += region.damaged;
                        count(region, +1);
                        if (level == 0) {
                            scanHashes(region);
                        }
                    }
                }
            }
        }
        if (damaged > 0) {
            ShadersCompanion.LOGGER.warn("Shaders Companion far store: {} damaged slots in {} dropped (their nodes are built again)", damaged, folder);
        }
    }

    /** Counts a region's slots into the statistics (sign -1 takes them out). */
    private void count(FarRegion region, int sign) {
        int level = region.level;
        for (int s = 0; s < region.slots; s++) {
            countSlot(level, region.kind[s], region.length[s], sign);
        }
    }

    private void countSlot(int level, byte kind, int length, int sign) {
        if (kind == FarRegion.BLOB) {
            blobs[level] += sign;
            blobBytes[level] += sign * (long) length;
            sectorsSmall[level] += sign * (long) ((length + 511) / 512);
            sectorsLarge[level] += sign * (long) ((length + 4095) / 4096);
        } else if (kind == FarRegion.UNIFORM) {
            uniform[level] += sign;
        }
    }

    /** Level 0: the sections stored and the chunk columns they belong to. */
    private void scanHashes(FarRegion region) {
        for (int s = 0; s < region.slots; s++) {
            int column = s / region.ys;
            int nx = region.rx * 8 + (column & 7);
            int nz = region.rz * 8 + (column >> 3);
            for (int i = 0; i < 8; i++) {
                if (region.hashes[s * 8 + i] != 0) {
                    sectionsOnDisk++;
                    exploredOnDisk.add(ChunkPos.asLong(2 * nx + (i & 1), 2 * nz + (i >> 2 & 1)));
                }
            }
        }
    }

    private void wipe() throws IOException {
        for (int level = 0; level < 16; level++) {
            Path dir = folder.resolve("L" + level);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        }
        Files.deleteIfExists(folder.resolve("states.txt"));
        Files.deleteIfExists(folder.resolve("biomes.txt"));
        Files.deleteIfExists(folder.resolve(IMPORTED));
        Files.deleteIfExists(folder.resolve(SERVED));
    }

    /**
     * The stamp files, per chunk column a time in seconds: when the import last read the column's saved copy
     * ({@link FarNodes#importedAt}), and the save time of the column the server last sent ({@link FarNodes#servedAt}).
     * Each is "SCI1", the count, then per column its ChunkPos long and the time.
     */
    static final String IMPORTED = "imported.bin";
    static final String SERVED = "served.bin";
    private static final int IMPORTED_MAGIC = 0x53434931;

    /** A stamp file as last written (none when it is missing or damaged: the columns are then read or asked for again once). */
    it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap readStamps(String file) {
        Path path = folder.resolve(file);
        if (Files.exists(path)) {
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(path)))) {
                int count = in.readInt() == IMPORTED_MAGIC ? in.readInt() : -1;
                // A damaged count must not size the map (12 bytes an entry after the 8-byte header)
                if (count >= 0 && count <= (Files.size(path) - 8) / 12) {
                    it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap stamps = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(Math.max(16, count));
                    for (int i = 0; i < count; i++) {
                        stamps.put(in.readLong(), in.readInt());
                    }
                    return stamps;
                }
            } catch (IOException e) {
                ShadersCompanion.LOGGER.warn("Shaders Companion far store: {} unreadable ({}); the auto import reads the saved chunks again", path, e.toString());
            }
        }
        return new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    }

    /** Writes a stamp file (to a new file, then moved over the old one). */
    void writeStamps(String file, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap stamps) throws IOException {
        Path temporary = folder.resolve(file + ".tmp");
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(temporary)))) {
            out.writeInt(IMPORTED_MAGIC);
            out.writeInt(stamps.size());
            for (var entry : stamps.long2IntEntrySet()) {
                out.writeLong(entry.getLongKey());
                out.writeInt(entry.getIntValue());
            }
        }
        Files.move(temporary, folder.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static String firstLine(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        return lines.isEmpty() ? "" : lines.get(0).trim();
    }

    /** The lines of an ids file (empty when it doesn't exist); notes whether the next append must start a new line. */
    private List<String> readLines(Path path, int which) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        byte[] bytes = Files.readAllBytes(path);
        needsNewline[which] = bytes.length > 0 && bytes[bytes.length - 1] != '\n';
        List<String> lines = new ArrayList<>(List.of(new String(bytes, StandardCharsets.UTF_8).split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        lines.replaceAll(line -> line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        return lines;
    }

    /** An open region file, or null when it doesn't exist and isn't to be made. */
    private FarRegion region(int level, int rx, int rz, boolean create) throws IOException {
        long key = FarRegion.key(level, rx, rz);
        FarRegion region = open.getAndMoveToLast(key);
        if (region != null) {
            return region;
        }
        if (!create && !existing.contains(key)) {
            return null;
        }
        Path dir = folder.resolve("L" + level);
        Files.createDirectories(dir);
        region = new FarRegion(dir.resolve("r." + rx + "." + rz + ".scf"), level, rx, rz, nodeY0(level), ys(level), SECTOR_BYTES);
        if (existing.add(key)) {
            regionFiles++;
            fileBytes += region.size();
            levelFiles[level]++;
            levelFileBytes[level] += region.size();
        }
        open.putAndMoveToLast(key, region);
        while (open.size() > OPEN_REGIONS) {
            open.removeFirst().close();
        }
        return region;
    }

    /** The stored hash of one section of a level-0 node that isn't in memory (0: none). */
    long storedHash(long key, int section) throws IOException {
        Pending pending = batch.get(key);
        if (pending != null) {
            return pending.hashes()[section];
        }
        int nx = Node.keyX(key);
        int nz = Node.keyZ(key);
        FarRegion region = region(0, nx >> 3, nz >> 3, false);
        if (region == null) {
            return 0;
        }
        int slot = region.slot(nx, Node.keyY(key), nz);
        return slot < 0 ? 0 : region.hashes[slot * 8 + section];
    }

    /** A stored node, decoded (marked stored, not dirty), or null when none is stored or its blob is damaged. */
    Node load(long key) throws IOException {
        int level = Node.keyLevel(key);
        Node node = new Node(level, Node.keyX(key), Node.keyY(key), Node.keyZ(key));
        Pending pending = batch.get(key);
        byte[] blob;
        if (pending != null) {
            blob = pending.blob();
            if (blob == null) {
                setUniform(node, pending.uniformState(), pending.uniformBiome(), pending.uniformLight());
            }
            if (pending.hashes() != null) {
                System.arraycopy(pending.hashes(), 0, node.sectionHash, 0, 8);
            }
        } else {
            FarRegion region = region(level, node.x >> 3, node.z >> 3, false);
            if (region == null) {
                return null;
            }
            int slot = region.slot(node.x, node.y, node.z);
            if (slot < 0 || region.kind[slot] == FarRegion.NONE) {
                return null;
            }
            if (region.kind[slot] == FarRegion.UNIFORM) {
                blob = null;
                setUniform(node, region.uniformState[slot], region.uniformBiome[slot], region.uniformLight[slot]);
            } else {
                long start = System.nanoTime();
                blob = region.read(slot);
                readNanos += System.nanoTime() - start;
            }
            if (level == 0) {
                System.arraycopy(region.hashes, slot * 8, node.sectionHash, 0, 8);
            }
        }
        if (blob != null) {
            long start = System.nanoTime();
            try {
                codec.decode(blob, node);
            } catch (DataFormatException | RuntimeException e) {
                // A blob that inflates but doesn't decode (its palette or indices out of range) is damaged too: the
                // store goes on without it rather than stopping
                damaged++;
                ShadersCompanion.LOGGER.warn("Shaders Companion far store: node {} {} {} at level {} is damaged ({}); it is built again", node.x, node.y, node.z, level,
                        e.getMessage());
                return null;
            }
            decodes++;
            decodeNanos += System.nanoTime() - start;
        }
        node.stored = true;
        return node;
    }

    /** A stored node as it is on disk: its blob, or the values of a uniform node (blob null). */
    record Raw(byte[] blob, int uniformState, byte uniformLight, short uniformBiome) {
    }

    /** Blobs read without decoding them ({@link #raw}), and the time it took. */
    long rawReads;
    long rawReadNanos;

    /**
     * A stored node without decoding it (the far mesher's jobs: the mesher decodes), from the batch or its region file,
     * or null when none is stored.
     */
    Raw raw(long key) throws IOException {
        Pending pending = batch.get(key);
        if (pending != null) {
            return new Raw(pending.blob(), pending.uniformState(), pending.uniformLight(), pending.uniformBiome());
        }
        int level = Node.keyLevel(key);
        int nx = Node.keyX(key);
        int nz = Node.keyZ(key);
        FarRegion region = region(level, nx >> 3, nz >> 3, false);
        if (region == null) {
            return null;
        }
        int slot = region.slot(nx, Node.keyY(key), nz);
        if (slot < 0 || region.kind[slot] == FarRegion.NONE) {
            return null;
        }
        if (region.kind[slot] == FarRegion.UNIFORM) {
            return new Raw(null, region.uniformState[slot], region.uniformLight[slot], region.uniformBiome[slot]);
        }
        long start = System.nanoTime();
        byte[] blob = region.read(slot);
        rawReads++;
        rawReadNanos += System.nanoTime() - start;
        return new Raw(blob, 0, (byte) 0, (short) 0);
    }

    private static void setUniform(Node node, int state, short biome, byte light) {
        node.uniformState = state;
        node.uniformBiome = biome;
        node.uniformLight = light;
        node.known = state != StateIds.UNKNOWN ? Node.VOXELS : 0;
    }

    /**
     * Encodes a node into the batch (replacing an earlier copy waiting there) and marks it written; returns whether it is
     * uniform (one state, light and biome: it can be kept compact).
     */
    boolean put(Node node) {
        long start = System.nanoTime();
        byte[] blob = codec.encode(node);
        encodeNanos += System.nanoTime() - start;
        encodes++;
        long[] hashes = node.sectionHash == null ? null : node.sectionHash.clone();
        Pending pending = blob != null ? new Pending(blob, 0, (short) 0, (byte) 0, hashes)
                : new Pending(null, codec.uniformState, codec.uniformBiome, codec.uniformLight, hashes);
        Pending old = batch.put(node.key, pending);
        batchBytes += pending.bytes() - (old != null ? old.bytes() : 0);
        node.dirty = false;
        node.stored = true;
        return blob == null;
    }

    boolean batchFull() {
        return batchBytes >= BATCH_BYTES;
    }

    boolean batchEmpty() {
        return batch.isEmpty();
    }

    /** Writes the batch: new ids first (a node never points at an id the files don't have), then region by region. */
    void writeBatch() throws IOException {
        List<String> newStates = states.unsaved();
        List<String> newBiomes = biomes.unsaved();
        if (batch.isEmpty() && newStates.isEmpty() && newBiomes.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        append(folder.resolve("states.txt"), newStates, 0);
        states.markSaved(newStates.size());
        append(folder.resolve("biomes.txt"), newBiomes, 1);
        biomes.markSaved(newBiomes.size());
        // The batch by region file
        Long2ObjectOpenHashMap<List<Long2ObjectMap.Entry<Pending>>> byRegion = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<Pending> entry : batch.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            byRegion.computeIfAbsent(FarRegion.key(Node.keyLevel(key), Node.keyX(key) >> 3, Node.keyZ(key) >> 3), k -> new ArrayList<>()).add(entry);
        }
        int nodes = 0;
        for (List<Long2ObjectMap.Entry<Pending>> entries : byRegion.values()) {
            long first = entries.get(0).getLongKey();
            int level = Node.keyLevel(first);
            FarRegion region = region(level, Node.keyX(first) >> 3, Node.keyZ(first) >> 3, true);
            List<FarRegion.Write> writes = new ArrayList<>(entries.size());
            for (Long2ObjectMap.Entry<Pending> entry : entries) {
                long key = entry.getLongKey();
                int slot = region.slot(Node.keyX(key), Node.keyY(key), Node.keyZ(key));
                if (slot < 0) {
                    continue;
                }
                Pending p = entry.getValue();
                countSlot(level, region.kind[slot], region.length[slot], -1);
                writes.add(new FarRegion.Write(slot, p.blob(), p.uniformState(), p.uniformBiome(), p.uniformLight(), p.hashes()));
            }
            long before = region.size();
            region.write(writes, FORCE);
            fileBytes += region.size() - before;
            levelFileBytes[level] += region.size() - before;
            for (FarRegion.Write w : writes) {
                countSlot(level, region.kind[w.slot()], region.length[w.slot()], +1);
            }
            nodes += writes.size();
        }
        batch.clear();
        batchBytes = 0;
        long nanos = System.nanoTime() - start;
        batches++;
        batchNodes += nodes;
        writeNanos += nanos;
        batchMaxNanos = Math.max(batchMaxNanos, nanos);
        windowBatchMaxNanos = Math.max(windowBatchMaxNanos, nanos);
    }

    private void append(Path path, List<String> lines, int which) throws IOException {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        if (needsNewline[which]) {
            text.append('\n');
            needsNewline[which] = false;
        }
        for (String line : lines) {
            text.append(line).append('\n');
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            if (FORCE) {
                channel.force(true);
            }
        }
    }

    /**
     * Closes the region files without writing anything (after the worker failed: what waits may be what failed). Never
     * throws; the files must not stay open, since Windows keeps an open file locked.
     */
    void abandon() {
        for (FarRegion region : open.values()) {
            try {
                region.close();
            } catch (IOException | RuntimeException e) {
                // Closing is all that is left to try
            }
        }
        open.clear();
    }

    /** Writes what waits and closes the region files. */
    void close() throws IOException {
        try {
            writeBatch();
        } finally {
            for (FarRegion region : open.values()) {
                region.close();
            }
            open.clear();
        }
    }

    /** Dev checks: every stored node's key and blob (null for uniform ones), region file by region file, reading as it goes. */
    void forEachStored(int level, int everyNth, NodeVisitor visitor) throws IOException {
        writeBatch();
        int n = 0;
        for (long regionKey : existing.toLongArray()) {
            if ((int) (regionKey >>> 56) != level) {
                continue;
            }
            int rx = (int) (regionKey << 8 >> 36);
            int rz = (int) (regionKey << 36 >> 36);
            FarRegion region = region(level, rx, rz, false);
            if (region == null) {
                continue;
            }
            for (int s = 0; s < region.slots; s++) {
                if (region.kind[s] == FarRegion.NONE || n++ % everyNth != 0) {
                    continue;
                }
                int column = s / region.ys;
                long key = Node.key(level, rx * 8 + (column & 7), region.nodeY0 + s % region.ys, rz * 8 + (column >> 3));
                visitor.visit(key, region.kind[s] == FarRegion.BLOB ? region.read(s) : null);
            }
        }
    }

    interface NodeVisitor {
        void visit(long key, byte[] blob) throws IOException;
    }

    /** Bytes in the batch, waiting to be written. */
    long batchBytes() {
        return batchBytes;
    }
}
