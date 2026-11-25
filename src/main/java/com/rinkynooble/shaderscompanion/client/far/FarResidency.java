package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.util.Mth;

/**
 * The far terrain's resident set (research/lod/M2-PLAN.md, "Residency"), owned by the store's worker: which nodes of
 * which level must have a mesh, their mesh jobs for {@link FarMesher}, and the finished meshes for the render thread.
 * <p>
 * <b>Levels and rings</b> come from the screen (DESIGN "Nodes and levels"): with H the window height, FOV the vertical
 * field of view and P the pixels a voxel may cover ({@code farPixelsPerVoxel}), level L is fine enough from
 * {@code K * 2^L} blocks out, {@code K = (H / 2) / tan(FOV / 2) / P} (252 at 1009 pixels, FOV 90, P 2). Level 0's ring
 * runs from the camera to {@code 2K}, level L's from {@code K * 2^L} to {@code K * 2^(L+1)}, level 6's to the far
 * range, and every ring stops at the far range ({@code farRange}). Distances are 3D, from the camera to the nearest
 * (or farthest) point of the node's box.
 * <p>
 * The rings are drawn as an <b>octree cut</b> (M2b, the GPU's selection in {@code far_select.csh}): a node of level L
 * (L at least 1) is split into its children while the camera is nearer to its box than {@code K * 2^L}; a node is
 * drawn when it isn't split and its parent is (or it is at the top level, the last whose {@code K * 2^L} is within the
 * far range), within the far range. That tiles space with no overlap and no gap, so level seams line up. A node is
 * <b>resident</b> at level L when that rule holds with every distance test widened by a margin (half a node, at least
 * 16 blocks) and it isn't entirely inside the chunk terrain (Embeddium's cylinder of the render distance around the
 * camera, less one chunk); it
 * <b>leaves</b> only once the rule fails by a whole node more, or it is inside the cylinder less two chunks (hysteresis;
 * a change of the settings the rings come from takes the set afresh). The set is worked out again when the camera has
 * moved 8 blocks since (the main thread posts it at most 4 times a second, and at once on a jump), so the GPU's exact
 * rule always finds a resident node within the margin.
 * <p>
 * <b>Meshing</b>: nearest first, coarser levels first at the same distance, at most {@link #MAX_IN_FLIGHT} jobs at the
 * mesher at a time (so the worker makes a job only when the mesher is about to need one). A node whose voxels change
 * ({@link FarNodes#changeListener}) is meshed again, and so are its six neighbours at its level (their border faces read
 * its face layer). A result older than the node's version is dropped. Only the worker adds to {@link #outbox}, so the
 * render thread sees each node's meshes and drops in order.
 */
final class FarResidency {
    static final int MAX_IN_FLIGHT = 4;
    /** Camera moves smaller than this don't change the set. */
    private static final double MOVE_BLOCKS = 8;
    /** A move this large restarts the fill clock (a teleport, or the first view). */
    private static final double JUMP_BLOCKS = 256;
    /** Most worker time a pump spends on nodes that need no job (not stored, uniform air). */
    private static final long PUMP_NANOS = 2_000_000L;
    /**
     * Each residency's versions and change stamps start at its own base, so a stamp is never reused by a later residency
     * (the mesher's layer cache outlives residencies and goes by stamp).
     */
    private static final java.util.concurrent.atomic.AtomicLong BASES = new java.util.concurrent.atomic.AtomicLong(1L << 40);

    /**
     * The camera and the settings the rings come from (posted by the main thread). keepInside: the nodes inside the chunk
     * terrain's cylinder stay resident too (the far shadow map draws them as shadow casters, FarShadow).
     */
    record View(double x, double y, double z, int renderDistance, int windowHeight, double fovDegrees, int pixelsPerVoxel, int farRange, boolean keepInside) {
        /** Blocks from which a level-0 voxel covers at most the allowed pixels (K). */
        double k() {
            return windowHeight / 2.0 / Math.tan(Math.toRadians(fovDegrees) / 2.0) / pixelsPerVoxel;
        }

        boolean sameRings(View o) {
            return o != null && renderDistance == o.renderDistance && windowHeight == o.windowHeight && fovDegrees == o.fovDegrees
                    && pixelsPerVoxel == o.pixelsPerVoxel && farRange == o.farRange && keepInside == o.keepInside;
        }

        double distance(View o) {
            double dx = x - o.x;
            double dy = y - o.y;
            double dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** For the render thread: this node's mesh is gone (it left the set, or its mesh became empty). */
    record Drop(long key) {
    }

    static final byte WAITING = 0;
    static final byte ABSENT = 1;
    static final byte EMPTY = 2;
    static final byte MESHED = 3;

    private static final class Entry {
        final long key;
        final int level;
        long version;
        long jobVersion = -1;
        long meshedVersion = -1;
        boolean queued;
        boolean inFlight;
        /** A non-empty mesh of it went to the outbox and wasn't dropped since. */
        boolean sent;
        double distance;
        int pass;
        byte status = WAITING;
        int quads;
        int border;
        int dark;
        int translucent;
        int palette;
        long bytes;

        Entry(long key) {
            this.key = key;
            this.level = Node.keyLevel(key);
        }
    }

    private static final Comparator<Entry> PRIORITY = (a, b) -> {
        int c = Double.compare(a.distance, b.distance);
        return c != 0 ? c : Integer.compare(b.level, a.level);
    };

    final FarNodes store;
    private final FarMesher mesher;
    private final Runnable wake;
    private final Long2ObjectOpenHashMap<Entry> resident = new Long2ObjectOpenHashMap<>();
    /** Per node changed this session, the counter at its last change (the mesher's layer cache checks it). */
    private final Long2LongOpenHashMap stamps = new Long2LongOpenHashMap();
    private long counter;
    private final PriorityQueue<Entry> queue = new PriorityQueue<>(PRIORITY);
    private int inFlight;
    /** Finished meshes from the mesher thread. */
    private final ConcurrentLinkedQueue<NodeMesh> done = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean wakePosted = new AtomicBoolean();
    /** For the render thread, in order: {@link NodeMesh}es to show and {@link Drop}s. Only the worker adds. */
    final ConcurrentLinkedQueue<Object> outbox = new ConcurrentLinkedQueue<>();
    private View setView;
    private int pass;
    private boolean closed;

    // The fill: from the first view or a jump until nothing waits
    private long fillStart;
    private boolean filling;
    private int fillJobs;
    private long fillMesherCpuStart;
    private long fillWorkerCpuStart;
    /** The last fill: its time, jobs, and the mesher's and the worker's processor time during it (-1 before one). */
    volatile long lastFillNanos = -1;
    volatile int lastFillJobs;
    volatile long lastFillMesherCpu;
    volatile long lastFillWorkerCpu;
    /** Fills finished so far. */
    volatile int fills;

    // Statistics (worker), per level
    private final int[] residentCount = new int[Node.LEVELS];
    private final int[] statusCount = new int[Node.LEVELS * 4];
    private final long[] quads = new long[Node.LEVELS];
    private final long[] borderQuads = new long[Node.LEVELS];
    private final long[] darkQuads = new long[Node.LEVELS];
    private final long[] translucentQuads = new long[Node.LEVELS];
    private final long[] paletteEntries = new long[Node.LEVELS];
    private final long[] bytes = new long[Node.LEVELS];
    private long meshes;
    private long meshNanos;
    private long meshMaxNanos;
    private long[] meshNanosPerLevel = new long[Node.LEVELS];
    private long[] meshesPerLevel = new long[Node.LEVELS];
    private long jobs;
    private long jobNanos;
    private long noJob;
    private long stale;
    private long recomputes;
    private long recomputeNanos;

    FarResidency(FarNodes store, FarMesher mesher, Runnable wake) {
        this.store = store;
        this.mesher = mesher;
        this.wake = wake;
        this.counter = BASES.getAndAdd(1L << 40);
        // Nodes that haven't changed in this residency have its base as their stamp
        this.stamps.defaultReturnValue(this.counter);
        store.changeListener = this::changed;
    }

    /** The camera moved, or the settings changed (worker thread). */
    void camera(View view) {
        if (closed) {
            return;
        }
        boolean rings = view.sameRings(setView);
        if (rings && setView.distance(view) < MOVE_BLOCKS) {
            return;
        }
        boolean jump = !rings || setView.distance(view) >= JUMP_BLOCKS;
        setView = view;
        long start = System.nanoTime();
        // A settings change takes the set afresh; hysteresis is for camera movement
        recompute(view, !rings);
        recomputes++;
        recomputeNanos += System.nanoTime() - start;
        if (jump) {
            fillStart = System.nanoTime();
            filling = true;
            fillJobs = 0;
            fillMesherCpuStart = cpu(mesher.thread);
            fillWorkerCpuStart = cpu(Thread.currentThread());
        }
    }

    private static long cpu(Thread thread) {
        return java.lang.management.ManagementFactory.getThreadMXBean().getThreadCpuTime(thread.getId());
    }

    private void recompute(View v, boolean fresh) {
        pass++;
        double k = v.k();
        double far = v.farRange;
        int top = 0;
        for (int level = 1; level < Node.LEVELS; level++) {
            if (k * (1 << level) < far) {
                top = level;
            }
        }
        // The chunk terrain: Embeddium's cylinder of the render distance around the camera less one chunk (entering), less
        // two (leaving), as FarRenderer.chunkRadius; none while the far shadow map wants the nodes there as casters
        double enterRadius = v.keepInside ? 0 : FarRenderer.chunkRadius(v.renderDistance);
        double leaveRadius = Math.max(0, enterRadius - 16);
        for (int level = 0; level <= top; level++) {
            int size = Node.SIZE << level;
            double enterMargin = Math.max(16.0, size / 2.0);
            double stayMargin = enterMargin + size;
            // Split below this distance (the node's own level), and the parent split below that one
            double split = level == 0 ? 0 : k * (1 << level);
            double parentSplit = k * (2 << level);
            double enterFar = far + enterMargin;
            double stayFar = far + stayMargin;
            double reach = level == top ? stayFar : Math.min(stayFar, parentSplit + stayMargin + 2.0 * size * Math.sqrt(3.0));
            double reach2 = reach * reach;
            int y0 = Math.floorDiv(store.minY, size);
            int y1 = Math.floorDiv(store.maxY - 1, size);
            int nx0 = Math.floorDiv(Mth.floor(v.x - reach), size);
            int nx1 = Math.floorDiv(Mth.floor(v.x + reach), size);
            int nz0 = Math.floorDiv(Mth.floor(v.z - reach), size);
            int nz1 = Math.floorDiv(Mth.floor(v.z + reach), size);
            for (int nz = nz0; nz <= nz1; nz++) {
                double bz0 = (double) nz * size;
                double bz1 = bz0 + size;
                double dz = Math.max(0, Math.max(bz0 - v.z, v.z - bz1));
                double pz0 = (double) Math.floorDiv(nz, 2) * size * 2;
                double pdz = Math.max(0, Math.max(pz0 - v.z, v.z - pz0 - size * 2));
                double fz = Math.max(Math.abs(v.z - bz0), Math.abs(v.z - bz1));
                for (int nx = nx0; nx <= nx1; nx++) {
                    double bx0 = (double) nx * size;
                    double bx1 = bx0 + size;
                    double dx = Math.max(0, Math.max(bx0 - v.x, v.x - bx1));
                    double horizontal2 = dx * dx + dz * dz;
                    if (horizontal2 > reach2) {
                        continue;
                    }
                    double px0 = (double) Math.floorDiv(nx, 2) * size * 2;
                    double pdx = Math.max(0, Math.max(px0 - v.x, v.x - px0 - size * 2));
                    double fx = Math.max(Math.abs(v.x - bx0), Math.abs(v.x - bx1));
                    double far2 = fx * fx + fz * fz;
                    for (int ny = y0; ny <= y1; ny++) {
                        double by0 = (double) ny * size;
                        double by1 = by0 + size;
                        double dy = Math.max(0, Math.max(by0 - v.y, v.y - by1));
                        double min2 = horizontal2 + dy * dy;
                        double min = Math.sqrt(min2);
                        double py0 = (double) Math.floorDiv(ny, 2) * size * 2;
                        double pdy = Math.max(0, Math.max(py0 - v.y, v.y - py0 - size * 2));
                        double parentMin = Math.sqrt(pdx * pdx + pdy * pdy + pdz * pdz);
                        boolean insideEnter = far2 < enterRadius * enterRadius && by0 > v.y - enterRadius && by1 < v.y + enterRadius;
                        boolean insideLeave = far2 < leaveRadius * leaveRadius && by0 > v.y - leaveRadius && by1 < v.y + leaveRadius;
                        long key = Node.key(level, nx, ny, nz);
                        Entry e = resident.get(key);
                        double margin = e != null && !fresh ? stayMargin : enterMargin;
                        boolean keep = (level == top || parentMin < parentSplit + margin) && (level == 0 || min >= split - margin)
                                && min < (e != null && !fresh ? stayFar : enterFar) && !(e != null && !fresh ? insideLeave : insideEnter);
                        if (!keep) {
                            continue;
                        }
                        if (e == null) {
                            e = new Entry(key);
                            e.version = ++counter;
                            resident.put(key, e);
                            residentCount[level]++;
                            statusCount[level * 4 + WAITING]++;
                        }
                        e.pass = pass;
                        e.distance = min;
                    }
                }
            }
        }
        LongArrayList gone = new LongArrayList();
        for (Entry e : resident.values()) {
            if (e.pass != pass) {
                gone.add(e.key);
            }
        }
        for (int i = 0; i < gone.size(); i++) {
            remove(resident.get(gone.getLong(i)));
        }
        // Priorities follow the camera
        queue.clear();
        for (Entry e : resident.values()) {
            e.queued = false;
            if (needsMesh(e)) {
                e.queued = true;
                queue.add(e);
            }
        }
    }

    private static boolean needsMesh(Entry e) {
        return e.version != e.meshedVersion && !e.inFlight;
    }

    private void remove(Entry e) {
        resident.remove(e.key);
        residentCount[e.level]--;
        statusCount[e.level * 4 + e.status]--;
        subtract(e);
        if (e.sent) {
            outbox.add(new Drop(e.key));
            e.sent = false;
        }
    }

    /** A node's voxels changed (FarNodes' listener, worker thread): it and its neighbours at its level are meshed again. */
    private void changed(long key) {
        stamps.put(key, ++counter);
        bump(key);
        int level = Node.keyLevel(key);
        int x = Node.keyX(key);
        int y = Node.keyY(key);
        int z = Node.keyZ(key);
        for (int d = 0; d < 6; d++) {
            bump(Node.key(level, x + FarMesher.STEP_X[d], y + FarMesher.STEP_Y[d], z + FarMesher.STEP_Z[d]));
        }
    }

    private void bump(long key) {
        Entry e = resident.get(key);
        if (e == null) {
            return;
        }
        e.version = ++counter;
        if (!e.queued && !e.inFlight) {
            e.queued = true;
            queue.add(e);
        }
    }

    private long stamp(long key) {
        return stamps.get(key);
    }

    /** A finished mesh (the mesher thread): queued for the worker, which is woken if it waits. */
    void meshed(NodeMesh mesh) {
        done.add(mesh);
        if (wakePosted.compareAndSet(false, true)) {
            wake.run();
        }
    }

    /** Nanoseconds until there is work here: 0 when results wait or a job can be made, else Long.MAX_VALUE. */
    long nanosUntilWork() {
        return !done.isEmpty() || inFlight < MAX_IN_FLIGHT && !queue.isEmpty() ? 0 : Long.MAX_VALUE;
    }

    /** Takes finished meshes and makes jobs while the mesher has room (worker thread, after every task). */
    void pump(long now) {
        if (closed) {
            return;
        }
        wakePosted.set(false);
        NodeMesh mesh;
        while ((mesh = done.poll()) != null) {
            inFlight--;
            accept(mesh);
        }
        long start = System.nanoTime();
        while (inFlight < MAX_IN_FLIGHT && !queue.isEmpty() && System.nanoTime() - start < PUMP_NANOS) {
            Entry e = queue.poll();
            e.queued = false;
            if (resident.get(e.key) != e || !needsMesh(e)) {
                continue;
            }
            long t0 = System.nanoTime();
            MeshJob job = MeshJob.make(store, e.key, e.version, this::stamp, mesher.pool);
            jobNanos += System.nanoTime() - t0;
            if (job == MeshJob.ABSENT || job == MeshJob.EMPTY) {
                noJob++;
                setResult(e, job == MeshJob.ABSENT ? ABSENT : EMPTY, null);
                continue;
            }
            job.sink = this;
            e.inFlight = true;
            e.jobVersion = e.version;
            inFlight++;
            jobs++;
            fillJobs++;
            mesher.submit(job);
        }
        if (filling && inFlight == 0 && queue.isEmpty()) {
            filling = false;
            lastFillNanos = System.nanoTime() - fillStart;
            lastFillJobs = fillJobs;
            lastFillMesherCpu = cpu(mesher.thread) - fillMesherCpuStart;
            lastFillWorkerCpu = cpu(Thread.currentThread()) - fillWorkerCpuStart;
            fills++;
            ShadersCompanion.LOGGER.info("Shaders Companion far terrain: {} resident nodes meshed in {} ms ({} jobs; the mesher's processor {} ms, the worker's {} ms)",
                    resident.size(), lastFillNanos / 1_000_000, fillJobs, lastFillMesherCpu / 1_000_000, lastFillWorkerCpu / 1_000_000);
        }
    }

    private void accept(NodeMesh mesh) {
        meshes++;
        meshNanos += mesh.nanos;
        meshMaxNanos = Math.max(meshMaxNanos, mesh.nanos);
        meshNanosPerLevel[mesh.level] += mesh.nanos;
        meshesPerLevel[mesh.level]++;
        Entry e = resident.get(mesh.key);
        if (e == null || e.jobVersion != mesh.version) {
            stale++;
            return;
        }
        e.inFlight = false;
        if (e.version != mesh.version) {
            // Changed while it was meshed: again
            stale++;
            if (!e.queued) {
                e.queued = true;
                queue.add(e);
            }
            return;
        }
        setResult(e, mesh.empty() ? EMPTY : MESHED, mesh);
    }

    private void setResult(Entry e, byte status, NodeMesh mesh) {
        statusCount[e.level * 4 + e.status]--;
        subtract(e);
        e.meshedVersion = e.version;
        e.status = status;
        statusCount[e.level * 4 + status]++;
        if (mesh != null && !mesh.empty()) {
            e.quads = mesh.quadCount;
            e.border = mesh.borderQuads();
            e.dark = mesh.darkQuads;
            e.translucent = mesh.translucentQuads();
            e.palette = mesh.palette.length;
            e.bytes = mesh.bytes();
            quads[e.level] += e.quads;
            borderQuads[e.level] += e.border;
            darkQuads[e.level] += e.dark;
            translucentQuads[e.level] += e.translucent;
            paletteEntries[e.level] += e.palette;
            bytes[e.level] += e.bytes;
            outbox.add(mesh);
            e.sent = true;
        } else if (e.sent) {
            outbox.add(new Drop(e.key));
            e.sent = false;
        }
    }

    private void subtract(Entry e) {
        quads[e.level] -= e.quads;
        borderQuads[e.level] -= e.border;
        darkQuads[e.level] -= e.dark;
        translucentQuads[e.level] -= e.translucent;
        paletteEntries[e.level] -= e.palette;
        bytes[e.level] -= e.bytes;
        e.quads = 0;
        e.border = 0;
        e.dark = 0;
        e.translucent = 0;
        e.palette = 0;
        e.bytes = 0;
    }

    /** The store closed or meshing was turned off: no more jobs, results or changes (worker thread). */
    void close() {
        closed = true;
        if (store.changeListener != null) {
            store.changeListener = null;
        }
        mesher.forget(this);
        done.clear();
    }

    // Status and statistics (worker thread)

    /** The status line's part: "R resident nodes (W waiting for a mesh), G with geometry, Q quads (B MB), X us a node". */
    String status() {
        int total = resident.size();
        long q = 0;
        long b = 0;
        int geometry = 0;
        for (int level = 0; level < Node.LEVELS; level++) {
            q += quads[level];
            b += bytes[level];
            geometry += statusCount[level * 4 + MESHED];
        }
        String fill = filling ? String.format(", filling for %.1f s", (System.nanoTime() - fillStart) / 1.0e9)
                : lastFillNanos >= 0 ? String.format(", filled in %.1f s", lastFillNanos / 1.0e9) : "";
        return String.format("%d resident nodes (%d waiting for a mesh, %d at the mesher), %d with geometry, %d quads (%.1f MB), %.0f us a node meshed%s", total,
                queue.size(), inFlight, geometry, q, b / 1048576.0, meshes == 0 ? 0.0 : meshNanos / 1.0e3 / meshes, fill);
    }

    /** The {@code far stats} lines. */
    String[] lines() {
        List<String> lines = new ArrayList<>();
        View v = setView;
        long q = 0;
        long b = 0;
        long border = 0;
        long dark = 0;
        int geometry = 0;
        for (int level = 0; level < Node.LEVELS; level++) {
            q += quads[level];
            b += bytes[level];
            border += borderQuads[level];
            dark += darkQuads[level];
            geometry += statusCount[level * 4 + MESHED];
        }
        lines.add(String.format("Far terrain: %d resident nodes, %d with geometry, %d quads (%d border, %d dark), %.1f MB of quads and palettes; far range %d, K %.0f, "
                        + "camera %.0f %.0f %.0f, render distance %d%s", resident.size(), geometry, q, border, dark, b / 1048576.0, v == null ? 0 : v.farRange,
                v == null ? 0.0 : v.k(), v == null ? 0.0 : v.x, v == null ? 0.0 : v.y, v == null ? 0.0 : v.z, v == null ? 0 : v.renderDistance,
                v != null && v.keepInside ? ", the chunk terrain's nodes kept for the far shadow map" : ""));
        for (int level = 0; level < Node.LEVELS; level++) {
            if (residentCount[level] == 0) {
                continue;
            }
            double inner = v == null ? 0 : level == 0 ? 0 : v.k() * (1 << level);
            double outer = v == null ? 0 : level == Node.LEVELS - 1 ? v.farRange : Math.min(v.k() * (2 << level), v.farRange);
            lines.add(String.format("  level %d (%.0f to %.0f blocks): %d resident, %d not stored, %d empty, %d waiting, %d with geometry; %d quads (%d border, %d dark, %d translucent), "
                            + "%d palette entries, %.2f MB; %.0f us a node meshed (%d meshes)", level, inner, outer, residentCount[level],
                    statusCount[level * 4 + ABSENT], statusCount[level * 4 + EMPTY], statusCount[level * 4 + WAITING], statusCount[level * 4 + MESHED], quads[level],
                    borderQuads[level], darkQuads[level], translucentQuads[level], paletteEntries[level], bytes[level] / 1048576.0,
                    meshesPerLevel[level] == 0 ? 0.0 : meshNanosPerLevel[level] / 1.0e3 / meshesPerLevel[level], meshesPerLevel[level]));
        }
        lines.add(String.format("Meshing: %d meshes, %.0f us each on average, at most %.0f us; %d jobs made, %.0f us of the worker each; %d nodes needed no job (not stored "
                        + "or uniform air); %d stale results; the set worked out %d times, %.2f ms each; disk: %d blobs read for jobs, %.0f us each", meshes,
                meshes == 0 ? 0.0 : meshNanos / 1.0e3 / meshes, meshMaxNanos / 1.0e3, jobs, jobs == 0 ? 0.0 : jobNanos / 1.0e3 / jobs, noJob, stale, recomputes,
                recomputes == 0 ? 0.0 : recomputeNanos / 1.0e6 / recomputes, store.disk == null ? 0 : store.disk.rawReads,
                store.disk == null || store.disk.rawReads == 0 ? 0.0 : store.disk.rawReadNanos / 1.0e3 / store.disk.rawReads));
        if (lastFillNanos >= 0) {
            lines.add(String.format("Last fill: %.1f s, %d jobs; the mesher's processor %.1f s, the worker's %.1f s", lastFillNanos / 1.0e9, lastFillJobs,
                    lastFillMesherCpu / 1.0e9, lastFillWorkerCpu / 1.0e9));
        }
        return lines.toArray(new String[0]);
    }

    /**
     * Dev checks: per level resident, with geometry, quads, border quads, bytes; then meshes, mesh nanos, max mesh nanos,
     * jobs, job nanos, whether a fill is running (1), the last fill's nanos, fills finished, the last fill's mesher and
     * worker processor nanos and its jobs.
     */
    long[] numbers() {
        long[] out = new long[Node.LEVELS * 5 + 11];
        for (int level = 0; level < Node.LEVELS; level++) {
            out[level * 5] = residentCount[level];
            out[level * 5 + 1] = statusCount[level * 4 + MESHED];
            out[level * 5 + 2] = quads[level];
            out[level * 5 + 3] = borderQuads[level];
            out[level * 5 + 4] = bytes[level];
        }
        int at = Node.LEVELS * 5;
        out[at] = meshes;
        out[at + 1] = meshNanos;
        out[at + 2] = meshMaxNanos;
        out[at + 3] = jobs;
        out[at + 4] = jobNanos;
        out[at + 5] = filling || setView == null ? 1 : 0;
        out[at + 6] = lastFillNanos;
        out[at + 7] = fills;
        out[at + 8] = lastFillMesherCpu;
        out[at + 9] = lastFillWorkerCpu;
        out[at + 10] = lastFillJobs;
        return out;
    }
}
