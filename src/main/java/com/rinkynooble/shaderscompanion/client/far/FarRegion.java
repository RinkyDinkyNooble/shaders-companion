package com.rinkynooble.shaderscompanion.client.far;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.List;

/**
 * One region file of the far store ({@code L<level>/r.<rx>.<rz>.scf}): the nodes of 8 x 8 node columns at one level,
 * every node height of the dimension (M1-PLAN "Disk"). Front: a fixed header, then a table of 16-byte slots (sector,
 * length, and the three values of a uniform node, which needs no blob), then at level 0 the section hashes of every
 * slot (8 longs). Body: sectors of {@link #sectorBytes} bytes, each blob in whole sectors.
 * <p>
 * A blob is never written over the sectors its slot points at: a new blob goes to free sectors first (the first free run
 * that fits, else the end of the file), the file is forced to disk, and only then is the slot's entry written (and its
 * hashes after it), so a crash at any point leaves every other node as it was and this one either old or new. The old
 * sectors are freed after the entries are on disk. Only the far store's worker thread uses region files.
 */
final class FarRegion implements AutoCloseable {
    static final int MAGIC = 0x53434631;
    static final int VERSION = 1;
    private static final int FIXED = 64;
    static final int ENTRY_BYTES = 16;
    static final byte NONE = 0;
    static final byte BLOB = 1;
    static final byte UNIFORM = 2;

    final Path path;
    final int level;
    final int rx;
    final int rz;
    final int nodeY0;
    final int ys;
    final int slots;
    final int sectorBytes;
    final int headerSectors;
    private final long hashesAt;
    private final FileChannel channel;
    // The table as it is on disk
    final byte[] kind;
    final int[] sector;
    final int[] length;
    final int[] uniformState;
    final short[] uniformBiome;
    final byte[] uniformLight;
    /** Level 0: the stored hashes, 8 per slot (as on disk). */
    final long[] hashes;
    private final BitSet used = new BitSet();
    /** Slots whose entry pointed outside the file or over another blob when it was opened (dropped). */
    int damaged;

    /** Opens or creates a region file; a file made for another layout (format, height) is started over. */
    FarRegion(Path path, int level, int rx, int rz, int nodeY0, int ys, int sectorBytes) throws IOException {
        this.path = path;
        this.level = level;
        this.rx = rx;
        this.rz = rz;
        this.nodeY0 = nodeY0;
        this.ys = ys;
        this.slots = 64 * ys;
        this.sectorBytes = sectorBytes;
        this.hashesAt = FIXED + (long) slots * ENTRY_BYTES;
        long headerBytes = hashesAt + (level == 0 ? slots * 64L : 0);
        this.headerSectors = (int) ((headerBytes + sectorBytes - 1) / sectorBytes);
        kind = new byte[slots];
        sector = new int[slots];
        length = new int[slots];
        uniformState = new int[slots];
        uniformBiome = new short[slots];
        uniformLight = new byte[slots];
        hashes = level == 0 ? new long[slots * 8] : null;
        channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        try {
            long size = channel.size();
            if (size == 0 || !readHeader(size)) {
                writeFreshHeader();
            }
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        used.set(0, headerSectors);
    }

    static long key(int level, int rx, int rz) {
        return (long) level << 56 | (long) (rx & 0xFFFFFFF) << 28 | (rz & 0xFFFFFFF);
    }

    /** The slot of a node in this region, or -1 when its height is outside the dimension's. */
    int slot(int nx, int ny, int nz) {
        int y = ny - nodeY0;
        return y < 0 || y >= ys ? -1 : ((nz & 7) * 8 + (nx & 7)) * ys + y;
    }

    private boolean readHeader(long size) throws IOException {
        ByteBuffer fixed = ByteBuffer.allocate(FIXED).order(ByteOrder.LITTLE_ENDIAN);
        readFully(fixed, 0);
        fixed.flip();
        if (fixed.getInt() != MAGIC || fixed.getInt() != VERSION || fixed.getInt() != level || fixed.getInt() != nodeY0 || fixed.getInt() != ys
                || fixed.getInt() != sectorBytes) {
            return false;
        }
        if (size < hashesAt + (hashes != null ? slots * 64L : 0)) {
            // Shorter than its own header: damaged, started over
            return false;
        }
        ByteBuffer table = ByteBuffer.allocate((int) (hashesAt - FIXED)).order(ByteOrder.LITTLE_ENDIAN);
        readFully(table, FIXED);
        table.flip();
        long fileSectors = (size + sectorBytes - 1) / sectorBytes;
        BitSet taken = new BitSet();
        taken.set(0, headerSectors);
        for (int s = 0; s < slots; s++) {
            byte k = table.get(s * ENTRY_BYTES + 14);
            int at = table.getInt(s * ENTRY_BYTES);
            int len = table.getInt(s * ENTRY_BYTES + 4);
            if (k == BLOB) {
                int count = (len + sectorBytes - 1) / sectorBytes;
                if (len <= 0 || at < headerSectors || at + (long) count > fileSectors || taken.nextSetBit(at) >= 0 && taken.nextSetBit(at) < at + count) {
                    damaged++;
                    continue;
                }
                taken.set(at, at + count);
                kind[s] = BLOB;
                sector[s] = at;
                length[s] = len;
            } else if (k == UNIFORM) {
                kind[s] = UNIFORM;
                uniformState[s] = table.getInt(s * ENTRY_BYTES + 8);
                uniformBiome[s] = table.getShort(s * ENTRY_BYTES + 12);
                uniformLight[s] = table.get(s * ENTRY_BYTES + 15);
            }
        }
        used.or(taken);
        if (hashes != null) {
            ByteBuffer h = ByteBuffer.allocate(slots * 64).order(ByteOrder.LITTLE_ENDIAN);
            readFully(h, hashesAt);
            h.flip();
            h.asLongBuffer().get(hashes);
        }
        return true;
    }

    private void writeFreshHeader() throws IOException {
        channel.truncate(0);
        ByteBuffer header = ByteBuffer.allocate(headerSectors * sectorBytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC).putInt(VERSION).putInt(level).putInt(nodeY0).putInt(ys).putInt(sectorBytes).putInt(rx).putInt(rz);
        header.clear();
        writeFully(header, 0);
        java.util.Arrays.fill(kind, NONE);
        if (hashes != null) {
            java.util.Arrays.fill(hashes, 0L);
        }
    }

    /** A blob's bytes, or null when the slot has none. */
    byte[] read(int slot) throws IOException {
        if (kind[slot] != BLOB) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(length[slot]);
        readFully(buffer, (long) sector[slot] * sectorBytes);
        return buffer.array();
    }

    /** One slot's new content: a blob, or a uniform node (blob null), and at level 0 its 8 section hashes. */
    record Write(int slot, byte[] blob, int uniformState, short uniformBiome, byte uniformLight, long[] hashes) {
    }

    /**
     * Writes a batch of slots: every blob to free sectors, the file forced to disk (when {@code force}), then the entries
     * (and hashes), forced again, and only then the old sectors freed.
     */
    void write(List<Write> writes, boolean force) throws IOException {
        int[] newSector = new int[writes.size()];
        boolean anyBlob = false;
        for (int i = 0; i < writes.size(); i++) {
            Write w = writes.get(i);
            if (w.blob() != null) {
                int count = (w.blob().length + sectorBytes - 1) / sectorBytes;
                int at = allocate(count);
                used.set(at, at + count);
                writeFully(ByteBuffer.wrap(w.blob()), (long) at * sectorBytes);
                newSector[i] = at;
                anyBlob = true;
            }
        }
        if (anyBlob && force) {
            channel.force(false);
        }
        ByteBuffer entry = ByteBuffer.allocate(ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer hash = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        int[] freeAt = new int[writes.size()];
        int[] freeCount = new int[writes.size()];
        for (int i = 0; i < writes.size(); i++) {
            Write w = writes.get(i);
            int s = w.slot();
            if (kind[s] == BLOB) {
                freeAt[i] = sector[s];
                freeCount[i] = (length[s] + sectorBytes - 1) / sectorBytes;
            }
            entry.clear();
            if (w.blob() != null) {
                entry.putInt(newSector[i]).putInt(w.blob().length).putInt(0).putShort((short) 0).put(BLOB).put((byte) 0);
                kind[s] = BLOB;
                sector[s] = newSector[i];
                length[s] = w.blob().length;
            } else {
                entry.putInt(0).putInt(0).putInt(w.uniformState()).putShort(w.uniformBiome()).put(UNIFORM).put(w.uniformLight());
                kind[s] = UNIFORM;
                sector[s] = 0;
                length[s] = 0;
                uniformState[s] = w.uniformState();
                uniformBiome[s] = w.uniformBiome();
                uniformLight[s] = w.uniformLight();
            }
            entry.flip();
            writeFully(entry, FIXED + (long) s * ENTRY_BYTES);
            if (hashes != null && w.hashes() != null) {
                hash.clear();
                for (long h : w.hashes()) {
                    hash.putLong(h);
                }
                hash.flip();
                writeFully(hash, hashesAt + s * 64L);
                System.arraycopy(w.hashes(), 0, hashes, s * 8, 8);
            }
        }
        if (force) {
            channel.force(false);
        }
        for (int i = 0; i < writes.size(); i++) {
            if (freeCount[i] > 0) {
                used.clear(freeAt[i], freeAt[i] + freeCount[i]);
            }
        }
    }

    /** The first run of free sectors that fits (past the header), else the end of the file. */
    private int allocate(int count) {
        int at = used.nextClearBit(headerSectors);
        while (true) {
            int next = used.nextSetBit(at);
            if (next < 0 || next - at >= count) {
                return at;
            }
            at = used.nextClearBit(next);
        }
    }

    /** The file's size on disk. */
    long size() throws IOException {
        return channel.size();
    }

    /** Sectors in use (header and blobs) and the file's length in sectors: the rest are free sectors inside the file. */
    int usedSectors() {
        return used.cardinality();
    }

    private void readFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, position);
            if (n < 0) {
                throw new IOException("unexpected end of " + path);
            }
            position += n;
        }
    }

    private void writeFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
