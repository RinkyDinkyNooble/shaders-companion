package com.rinkynooble.shaderscompanion.server;

import com.rinkynooble.shaderscompanion.far.SavedChunks;
import com.rinkynooble.shaderscompanion.far.net.FarNet;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * One player's far terrain sharing for the level they are in ({@link FarShare}): reset by every hello (joining, a
 * dimension change, a respawn), started by the client's reply. Owned by the server thread, but for the two queues the
 * worker fills ({@link #plans}, {@link #ready}) and the reads in flight. Everything it holds is bounded: the columns to
 * offer by the radius, the pushes by {@link #MAX_PUSHES}, the offer by {@link FarNet#MAX_OFFER}, the wanted columns by
 * {@link #MAX_WANTED}, the packed ones by {@link #MAX_READY}; what a client sends can only pick from the one offer it
 * was sent, so a client can never make the server read anything it didn't offer.
 */
final class ShareSession {
    /** Columns wanted but not read yet, at most (the next offer waits while more wait). */
    static final int MAX_WANTED = 512;
    /** Columns packed and waiting to be sent, at most (reads wait while more wait). */
    static final int MAX_READY = 32;
    /** Reads of one player in flight, at most. */
    static final int MAX_READS = 16;
    /** Saved chunks waiting to be offered as pushes (new or changed), at most. */
    static final int MAX_PUSHES = 4096;
    /** A column is offered again as changed at most this often, in seconds (a farm saves every 10 s). */
    static final int PUSH_INTERVAL = 30;
    /** The player moved this many chunks from where the offers were planned: planned again. */
    static final int REPLAN_CHUNKS = 8;
    /** Columns are sent from this long after the reply, so the game's own chunks load first (nanoseconds). */
    static final long START_DELAY = 3_000_000_000L;
    /** Replies that start sending, at most this often (nanoseconds). */
    static final long REPLY_INTERVAL = 5_000_000_000L;
    /** An offer not answered for this long is given up (nanoseconds). */
    static final long OFFER_TIMEOUT = 30_000_000_000L;

    /** A plan from the worker: the saved columns around a centre, nearest first, with their save times. */
    record Plan(int epoch, int centerX, int centerZ, long[] chunks, int[] stamps) {
    }

    /** A packed column from the worker (empty data: not complete or not saved after all). */
    record Ready(int epoch, long pos, int stamp, byte[] data) {
    }

    final java.util.UUID id;
    ServerPlayer player;
    int epoch;
    ResourceKey<Level> dimension;
    SavedChunks saved;
    boolean shares;
    boolean accepted;
    int radius;
    long bytesPerSecond;
    long window;
    long acceptedAt;
    /** The last reply that started sending (a client can't restart the planning more often than {@link #REPLY_INTERVAL}). */
    long lastReplyAt;
    int lastReplyEpoch = -1;

    // Planning
    boolean planning;
    int planCenterX;
    int planCenterZ;
    final LongArrayFIFOQueue toOffer = new LongArrayFIFOQueue();
    final IntArrayFIFOQueue toOfferStamps = new IntArrayFIFOQueue();
    /** What was offered this level, by column: the save time it was offered with. */
    final Long2IntOpenHashMap offered = new Long2IntOpenHashMap();
    /** New and changed columns to offer before the plan's, with their save times. */
    final Long2IntLinkedOpenHashMap pushes = new Long2IntLinkedOpenHashMap();

    // The offer out
    int offerId;
    long[] offerChunks;
    int[] offerStamps;
    long offerSentAt;

    // Wanted, read, sent
    final LongArrayFIFOQueue wanted = new LongArrayFIFOQueue();
    final IntArrayFIFOQueue wantedStamps = new IntArrayFIFOQueue();
    final AtomicInteger reads = new AtomicInteger();
    final ConcurrentLinkedQueue<Plan> plans = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();
    long inFlight;
    double tokens;
    long lastRefill;

    // Statistics for /farshare
    long columnsSent;
    long bytesSent;
    long notComplete;
    long offeredTotal;
    long wantedTotal;
    long pushesOffered;
    int lastPlan = -1;
    long ticked;
    long skippedNotFar;
    long skippedOffered;

    ShareSession(java.util.UUID id) {
        this.id = id;
        offered.defaultReturnValue(Integer.MIN_VALUE);
    }

    /** A new hello: everything of the level before is let go (what the worker still sends back carries the old epoch). */
    void reset(ServerPlayer player, SavedChunks saved, boolean shares) {
        this.player = player;
        this.epoch++;
        this.dimension = player.level().dimension();
        this.saved = saved;
        this.shares = shares;
        accepted = false;
        planning = false;
        toOffer.clear();
        toOfferStamps.clear();
        offered.clear();
        pushes.clear();
        offerChunks = null;
        offerStamps = null;
        wanted.clear();
        wantedStamps.clear();
        plans.clear();
        ready.clear();
        inFlight = 0;
        tokens = 0;
    }

    /**
     * The client stopped taking far terrain for this level: nothing more is offered or sent until it replies again (or the
     * next hello). What was offered is forgotten, so a new reply offers the whole radius again: the client may have reset
     * its far store meanwhile ({@code far reset}), and it turns down what it already holds.
     */
    void stopSending() {
        accepted = false;
        toOffer.clear();
        toOfferStamps.clear();
        offered.clear();
        pushes.clear();
        offerChunks = null;
        offerStamps = null;
        wanted.clear();
        wantedStamps.clear();
        ready.clear();
    }

    int chunkX() {
        return player.chunkPosition().x;
    }

    int chunkZ() {
        return player.chunkPosition().z;
    }

    /**
     * Whether a column is far terrain for the player: inside the radius (a circle) and outside the game's own chunks, less
     * two chunks (the client may hold the outer ring without having stored it; it turns down what it holds itself).
     */
    boolean farFor(long pos, int viewDistance) {
        int cx = ChunkPos.getX(pos);
        int cz = ChunkPos.getZ(pos);
        long dx = cx - chunkX();
        long dz = cz - chunkZ();
        if (dx * dx + dz * dz > (long) radius * radius) {
            return false;
        }
        return !ChunkMap.isChunkInRange(cx, cz, chunkX(), chunkZ(), Math.max(1, viewDistance - 2));
    }

    /** A new or changed column of the player's level, saved now: offered before the plan's (at most {@link #MAX_PUSHES} wait). */
    void push(long pos, int stamp) {
        if (pushes.containsKey(pos) || pushes.size() < MAX_PUSHES) {
            pushes.put(pos, stamp);
        }
    }

    /**
     * The next offer: pushes first (each at most every {@link #PUSH_INTERVAL} seconds), then the plan's columns, each
     * only if it is far terrain for the player now and not offered before with that save time. Null when none is due.
     */
    FarNet.Offer nextOffer(int viewDistance, int nowSeconds) {
        long[] chunks = new long[FarNet.MAX_OFFER];
        int[] stamps = new int[FarNet.MAX_OFFER];
        int n = 0;
        if (!pushes.isEmpty()) {
            var it = pushes.long2IntEntrySet().fastIterator();
            while (it.hasNext() && n < FarNet.MAX_OFFER) {
                var e = it.next();
                long pos = e.getLongKey();
                int last = offered.get(pos);
                if (last != Integer.MIN_VALUE && nowSeconds - last < PUSH_INTERVAL) {
                    // Offered not long ago: it waits (the newest save time kept)
                    continue;
                }
                it.remove();
                if (farFor(pos, viewDistance) && last != e.getIntValue()) {
                    chunks[n] = pos;
                    stamps[n++] = e.getIntValue();
                    offered.put(pos, e.getIntValue());
                    pushesOffered++;
                }
            }
        }
        while (n < FarNet.MAX_OFFER && !toOffer.isEmpty()) {
            long pos = toOffer.dequeueLong();
            int stamp = toOfferStamps.dequeueInt();
            if (offered.get(pos) == stamp) {
                skippedOffered++;
                continue;
            }
            if (!farFor(pos, viewDistance)) {
                skippedNotFar++;
                continue;
            }
            chunks[n] = pos;
            stamps[n++] = stamp;
            offered.put(pos, stamp);
        }
        if (n == 0) {
            return null;
        }
        offerId++;
        offerChunks = java.util.Arrays.copyOf(chunks, n);
        offerStamps = java.util.Arrays.copyOf(stamps, n);
        offeredTotal += n;
        return new FarNet.Offer(epoch, offerId, offerChunks, offerStamps);
    }

    /** The client's answer to the offer out: its wanted columns join the reads' queue. */
    void want(FarNet.Want message) {
        if (offerChunks == null || message.id() != offerId) {
            return;
        }
        for (int i = 0; i < offerChunks.length; i++) {
            if (message.wants(i)) {
                wanted.enqueue(offerChunks[i]);
                wantedStamps.enqueue(offerStamps[i]);
                wantedTotal++;
            }
        }
        offerChunks = null;
        offerStamps = null;
    }

    boolean offerOut() {
        return offerChunks != null;
    }
}
