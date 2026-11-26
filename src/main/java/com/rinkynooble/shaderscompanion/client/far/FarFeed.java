package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.far.CapturedSection;
import com.rinkynooble.shaderscompanion.far.ColumnWire;
import com.rinkynooble.shaderscompanion.far.net.FarNet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.level.ChunkPos;

/**
 * The far terrain a server sends ({@link FarLink}), the worker's side: one per store, while the server shares. It
 * answers the server's offers (a column is wanted when it lies within the radius of where the player is now, the
 * client hasn't shown it live this session, the store doesn't already hold the copy saved at that time, and the disk
 * allows: {@link #capReason}), and stores the columns it wanted exactly as the import stores saved chunks
 * ({@link FarNodes#ingest}, so a section unchanged by its hash writes nothing). A column it didn't ask for, or that
 * fails a check ({@link ColumnWire#decode}), is dropped whole. Every column's bytes are acknowledged once handled,
 * which lets the server send the next ones: the server can't send faster than this worker stores.
 * <p>
 * Disk: what one server's stores take together (all its worlds and dimensions, {@code mp-<address>*}) is capped by
 * {@code farServerDiskMb}; past it nothing more is asked for and the server is told to stop. Taking from a server also
 * pauses while the disk has less than {@link #LOW_DISK} free, and goes on once it has {@link #DISK_OK}.
 */
final class FarFeed {
    /** Columns asked for and not arrived yet, at most (an offer past it is answered with nothing). */
    static final int MAX_WANTED = 2048;
    static final long LOW_DISK = 2L << 30;
    static final long DISK_OK = 3L << 30;
    private static final long FREE_CHECK_NANOS = 30_000_000_000L;

    final int epoch;
    private final ColumnWire.Expect expect;
    private final long capBytes;
    /** The bytes of this server's other stores (its other worlds and dimensions), measured when the store opened. */
    private final long otherBytes;
    private final Path folder;
    private final Long2IntOpenHashMap wanted = new Long2IntOpenHashMap();
    private boolean lowDisk;
    private long freeCheckedAt;
    private boolean stopSent;

    // Statistics (worker thread; published by FarWorker)
    long offered;
    long asked;
    long received;
    long receivedBytes;
    long notComplete;
    long rejected;
    long unasked;
    long liveSkipped;
    String lastRejection;

    FarFeed(int epoch, ColumnWire.Expect expect, long capBytes, long otherBytes, Path folder) {
        this.epoch = epoch;
        this.expect = expect;
        this.capBytes = capBytes;
        this.otherBytes = otherBytes;
        this.folder = folder;
        wanted.defaultReturnValue(Integer.MIN_VALUE);
    }

    /** Why nothing more is asked for, or null: the server's disk cap, or a nearly full disk. */
    String capReason(FarNodes store, long now) {
        long used = otherBytes + (store.disk == null ? 0 : store.disk.fileBytes);
        if (capBytes > 0 && used >= capBytes) {
            return String.format("this server's far terrain has reached farServerDiskMb (%d MB)", capBytes >> 20);
        }
        if (store.disk == null) {
            return "the far store has no disk";
        }
        if (now - freeCheckedAt > FREE_CHECK_NANOS || freeCheckedAt == 0) {
            freeCheckedAt = now;
            try {
                long free = Files.getFileStore(folder).getUsableSpace();
                lowDisk = lowDisk ? free < DISK_OK : free < LOW_DISK;
            } catch (java.io.IOException | RuntimeException e) {
                lowDisk = false;
            }
        }
        return lowDisk ? "the disk has less than 2 GB free" : null;
    }

    /**
     * An offer (worker thread): the bits of the columns wanted, for the main thread to send. {@code centerX}, {@code
     * centerZ}: the player's chunk now; {@code radius}: the client's in chunks.
     */
    FarNet.Want offer(FarNodes store, FarNet.Offer offer, int centerX, int centerZ, int radius, long now) {
        long[] bits = new long[(offer.chunks().length + 63) / 64];
        offered += offer.chunks().length;
        String cap = capReason(store, now);
        if (cap == null) {
            long r = radius + 2L;
            for (int i = 0; i < offer.chunks().length && wanted.size() < MAX_WANTED; i++) {
                long pos = offer.chunks()[i];
                long dx = ChunkPos.getX(pos) - centerX;
                long dz = ChunkPos.getZ(pos) - centerZ;
                if (dx * dx + dz * dz > r * r || store.liveColumns.contains(pos) || store.servedAt.get(pos) == offer.stamps()[i]) {
                    continue;
                }
                bits[i >> 6] |= 1L << (i & 63);
                wanted.put(pos, offer.stamps()[i]);
                asked++;
            }
        }
        return new FarNet.Want(epoch, offer.id(), bits);
    }

    /** Whether the server should be told to stop now (the cap was reached; told once). */
    String stopNow(FarNodes store, long now) {
        if (stopSent) {
            return null;
        }
        String cap = capReason(store, now);
        if (cap != null && !lowDisk) {
            stopSent = true;
            return cap;
        }
        return null;
    }

    /** A column arrived (worker thread): stored when it was asked for and reads cleanly. */
    void column(FarNodes store, FarNet.Column column, long now) {
        long pos = ChunkPos.asLong(column.x(), column.z());
        int askedStamp = wanted.get(pos);
        if (askedStamp == Integer.MIN_VALUE) {
            unasked++;
            return;
        }
        // A column asked for twice (offered, then saved again meanwhile) waits for the later one too; either is read
        // at the time it is sent, so both are current
        if (askedStamp == column.stamp()) {
            wanted.remove(pos);
        }
        if (column.data().length == 0) {
            // Not complete on the server: nothing to store, and not asked for again until it is saved again
            notComplete++;
            store.markServed(pos, column.stamp());
            return;
        }
        if (store.liveColumns.contains(pos)) {
            // Shown live meanwhile: what the client saw is as new
            liveSkipped++;
            return;
        }
        List<CapturedSection> sections;
        try {
            sections = ColumnWire.decode(column.data(), column.x(), column.z(), store.generation, expect);
        } catch (ColumnWire.Rejected e) {
            rejected++;
            if (lastRejection == null || rejected % 100 == 1) {
                ShadersCompanion.LOGGER.warn("Shaders Companion far terrain: turned down the server's column {}, {}: {}", column.x(), column.z(), e.getMessage());
            }
            lastRejection = e.getMessage();
            return;
        }
        for (CapturedSection section : sections) {
            store.ingest(section, now);
        }
        store.markServed(pos, column.stamp());
        received++;
        receivedBytes += column.data().length;
    }

    /** The status line's part (worker thread). */
    String status(FarNodes store) {
        String cap = capReason(store, System.nanoTime());
        return String.format("%d columns stored (%.1f MB from the server), %d not complete on the server, %d waiting; %d offered, %d asked for%s%s%s", received,
                receivedBytes / 1048576.0, notComplete, wanted.size(), offered, asked, rejected > 0 ? ", " + rejected + " turned down (" + lastRejection + ")" : "",
                unasked > 0 ? ", " + unasked + " not asked for (dropped)" : "", cap != null ? "; paused: " + cap : "");
    }
}
