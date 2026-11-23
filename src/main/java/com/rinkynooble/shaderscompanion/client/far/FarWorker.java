package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The far store's one thread ("Shaders Companion far store", lowest priority, a daemon), for the whole game: it owns
 * the store ({@link FarNodes}), its disk and the import, and does everything but the copying, from a queue of captured
 * sections, imported chunks and tasks. The coarser levels' rebuilds run when their time has come, between sections at
 * least every 100 ms and freely while the queue is empty; changed nodes are written every 10 seconds. With nothing to
 * do it waits on the queue. A new world (or the store turned off) replaces the store, the old one closed first (every
 * change written); sections captured for an earlier one are let go by their generation. At the game's end a shutdown
 * hook waits for the store to close, so a normal quit loses nothing.
 * <p>
 * While the far terrain meshes ({@link #meshingWanted}), the worker also keeps the store's {@link FarResidency}: the
 * resident set from the camera the main thread posts, and the mesh jobs for {@link FarMesher}'s thread, made after
 * every task while the mesher has room.
 */
final class FarWorker {
    private static final long REBUILD_PASS_NANOS = 100_000_000L;

    private final LinkedBlockingQueue<Object> tasks = new LinkedBlockingQueue<>();
    final Thread thread;
    private FarNodes store;
    private FarImport importer;
    private long lastRebuildPass;
    private long lastPublish;

    // Published for the main thread's status line and the dev checks
    volatile boolean busy;
    volatile long sectionsStored;
    volatile long sessionNew;
    volatile long sessionChanged;
    volatile long sessionUnchanged;
    volatile int nodesInMemory;
    volatile int compactInMemory;
    volatile long bytesInMemory;
    volatile int rebuildsWaiting;
    volatile long nodesDropped;
    volatile double ingestMicros;
    volatile double rebuildMicros;
    volatile long diskBytes;
    volatile int regionFiles;
    volatile String diskError;
    volatile FarImport.Progress importProgress;
    /** The store's sections to copy again (FarNodes.recaptures), for the main thread; null without a store. */
    volatile java.util.Queue<Long> recaptures;
    /** Lines for the chat (an import's start and end), taken by the main thread. */
    final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

    /** The far terrain meshes (set by the main thread): the residency runs while the store is open. */
    volatile boolean meshingWanted;
    /** The current residency, for the render thread's outbox and the status line; null while nothing meshes. */
    volatile FarResidency residency;
    /** The mesher thread, made the first time something meshes. */
    volatile FarMesher mesher;
    /** The last camera the main thread posted (a new residency starts from it). */
    private FarResidency.View lastView;
    /** The residency's status line part, published with the statistics. */
    volatile String meshStatus;
    /** The store's ids, for the render thread's colour table ({@link StateColors}); null without a store. */
    volatile StateIds publishedStates;
    volatile BiomeIds publishedBiomes;

    FarWorker() {
        thread = new Thread(this::run, "Shaders Companion far store");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeAtExit, "Shaders Companion far store shutdown"));
    }

    /** Queues a captured section (main thread). */
    void post(CapturedSection section) {
        tasks.add(section);
    }

    /** Queues a chunk the import read (the server's IO worker). */
    void post(FarImport.Loaded loaded) {
        tasks.add(loaded);
    }

    /** Queues a task to run on the worker with the store (null when there is none). */
    void post(Consumer<FarWorker> task) {
        tasks.add(task);
    }

    /** Runs a function on the worker with the store, completing the future with its result (null store: "off"). */
    <T> CompletableFuture<T> ask(Function<FarNodes, T> function) {
        CompletableFuture<T> future = new CompletableFuture<>();
        post(worker -> {
            try {
                future.complete(function.apply(worker.store));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    int queued() {
        return tasks.size();
    }

    /**
     * Closes the store (every change written) and opens the next one (worker thread; null turns it off). An import of
     * the old store stops.
     */
    void replaceStore(Supplier<FarNodes> next) {
        if (importer != null) {
            importer.stopped = true;
            endImport();
        }
        FarNodes old = store;
        store = null;
        if (old != null) {
            try {
                old.close();
            } catch (RuntimeException e) {
                ShadersCompanion.LOGGER.error("Shaders Companion far store: closing {} {} failed", old.world, old.dimension, e);
            }
        }
        store = next.get();
        lastPublish = 0;
        publish(System.nanoTime());
    }

    FarNodes store() {
        return store;
    }

    /** Starts an import (worker thread), stopping one that runs. */
    void startImport(FarImport next) {
        if (importer != null) {
            importer.stopped = true;
            endImport();
        }
        if (store == null || store.generation != next.generation) {
            messages.add("Far store import not started: the store is off");
            return;
        }
        try {
            next.plan(store);
        } catch (java.io.IOException e) {
            messages.add("Far store import not started: " + e);
            return;
        }
        importer = next;
        importProgress = next.progress(null);
        String line = String.format("Far store import: %d chunks to read within %d chunks of %d, %d", next.total(), next.radius, next.centerX, next.centerZ);
        ShadersCompanion.LOGGER.info("Shaders Companion {}", line);
        messages.add(line);
    }

    /** Stops the import (worker thread). */
    void stopImport() {
        if (importer != null) {
            importer.stopped = true;
            endImport();
        } else {
            messages.add("No far store import is running");
        }
    }

    private void endImport() {
        String summary = store != null ? importer.summary(store) : "Far store import stopped";
        ShadersCompanion.LOGGER.info("Shaders Companion {}", summary);
        importProgress = importer.progress(summary);
        messages.add(summary);
        importer = null;
    }

    @SuppressWarnings("unchecked")
    private void run() {
        while (true) {
            Object task;
            try {
                long now = System.nanoTime();
                long wait = store == null ? Long.MAX_VALUE : store.nanosUntilWork(now);
                FarResidency r = residency;
                if (r != null) {
                    wait = Math.min(wait, r.nanosUntilWork());
                }
                if (wait == Long.MAX_VALUE) {
                    busy = false;
                    task = tasks.take();
                } else if (wait <= 0) {
                    task = tasks.poll();
                } else {
                    busy = false;
                    task = tasks.poll(Math.min(wait, 1_000_000_000L), TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException e) {
                continue;
            }
            busy = true;
            try {
                long now = System.nanoTime();
                if (task instanceof CapturedSection section) {
                    if (store != null && section.generation == store.generation) {
                        store.ingest(section, now);
                    }
                } else if (task instanceof FarImport.Loaded loaded) {
                    if (importer != null && loaded.owner() == importer && store != null) {
                        importer.accept(loaded, store, now);
                    }
                } else if (task != null) {
                    ((Consumer<FarWorker>) task).accept(this);
                }
                if (store != null) {
                    // Rebuilds between sections at least every 100 ms, freely when nothing else waits
                    if (task == null || tasks.isEmpty() || now - lastRebuildPass > REBUILD_PASS_NANOS) {
                        lastRebuildPass = now;
                        store.runDueRebuilds(now, task == null || tasks.isEmpty() ? 50_000_000L : 10_000_000L);
                    }
                    store.trim(now);
                    store.writeWhenDue(now);
                    if (importer != null) {
                        importer.pump(this);
                        if (importer.finished()) {
                            endImport();
                        } else if (now - lastPublish >= 50_000_000L) {
                            importProgress = importer.progress(null);
                        }
                    }
                }
                syncResidency();
                if (residency != null) {
                    residency.pump(now);
                }
                publish(now);
            } catch (Throwable t) {
                ShadersCompanion.LOGGER.error("Shaders Companion far store: the worker stopped the store after an error", t);
                if (store != null && store.disk != null) {
                    // Its files closed without writing (what waits may be what failed)
                    store.disk.abandon();
                }
                store = null;
                importer = null;
                tasks.clear();
                syncResidency();
                publish(System.nanoTime());
                FarStore.workerFailed(t);
            }
        }
    }

    /** The camera and ring settings from the main thread (at most 4 times a second, and on a jump). */
    void postView(FarResidency.View view) {
        post(w -> {
            w.lastView = view;
            if (w.residency != null) {
                w.residency.camera(view);
            }
        });
    }

    /** Starts or stops the residency to match the store and {@link #meshingWanted} (worker thread). */
    private void syncResidency() {
        boolean want = meshingWanted && store != null;
        FarResidency r = residency;
        if (r != null && (!want || r.store != store)) {
            r.close();
            residency = null;
            r = null;
        }
        if (want && r == null) {
            if (mesher == null) {
                mesher = new FarMesher();
            }
            r = new FarResidency(store, mesher, () -> post(w -> {
            }));
            residency = r;
            if (lastView != null) {
                r.camera(lastView);
            }
        }
    }

    /** The statistics for the main thread, at most 20 times a second (and whenever the queue runs dry). */
    private void publish(long now) {
        if (now - lastPublish < 50_000_000L && !tasks.isEmpty()) {
            return;
        }
        lastPublish = now;
        FarResidency r = residency;
        meshStatus = r == null ? null : r.status();
        FarNodes s = store;
        recaptures = s == null ? null : s.recaptures;
        publishedStates = s == null ? null : s.states;
        publishedBiomes = s == null ? null : s.biomes;
        if (s == null) {
            sectionsStored = 0;
            sessionNew = 0;
            sessionChanged = 0;
            sessionUnchanged = 0;
            nodesInMemory = 0;
            compactInMemory = 0;
            bytesInMemory = 0;
            rebuildsWaiting = 0;
            nodesDropped = 0;
            diskBytes = 0;
            regionFiles = 0;
            diskError = null;
            return;
        }
        sectionsStored = s.sectionsStored;
        sessionNew = s.sessionNew;
        sessionChanged = s.sessionChanged;
        sessionUnchanged = s.sessionUnchanged;
        nodesInMemory = s.cachedNodes();
        compactInMemory = s.compactNodes();
        bytesInMemory = s.bytesInMemory();
        rebuildsWaiting = s.rebuildsWaiting();
        long dropped = 0;
        for (long d : s.dropped) {
            dropped += d;
        }
        nodesDropped = dropped;
        ingestMicros = s.ingestMicros;
        rebuildMicros = s.rebuildMicros;
        diskBytes = s.disk == null ? 0 : s.disk.fileBytes;
        regionFiles = s.disk == null ? 0 : s.disk.regionFiles;
        diskError = s.diskError;
    }

    /** The JVM is exiting (a shutdown hook): closes the store on the worker and waits for it, at most a minute. */
    private void closeAtExit() {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        post(worker -> {
            try {
                worker.replaceStore(() -> null);
            } finally {
                closed.complete(null);
            }
        });
        try {
            closed.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            // The worker is gone or stuck: nothing more can be written
        }
    }
}
