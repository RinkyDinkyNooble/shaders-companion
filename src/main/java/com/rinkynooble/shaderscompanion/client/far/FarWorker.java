package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The far store's one thread ("Shaders Companion far store", lowest priority, a daemon), for the whole game: it owns
 * the store ({@link FarNodes}) and does everything but the copying, from a queue of captured sections and tasks.
 * Captured sections come first; the coarser levels' rebuilds run when their time has come, between sections at least
 * every 100 ms and freely while the queue is empty. With nothing to do it waits on the queue. A new world (or the
 * store turned off) replaces the store; sections captured for an earlier one are let go by their generation.
 */
final class FarWorker {
    private static final long REBUILD_PASS_NANOS = 100_000_000L;

    private final LinkedBlockingQueue<Object> tasks = new LinkedBlockingQueue<>();
    private FarNodes store;
    private long lastRebuildPass;
    private long lastPublish;

    // Published for the main thread's status line and the dev checks
    volatile boolean busy;
    volatile long sectionsStored;
    volatile int nodesInMemory;
    volatile long bytesInMemory;
    volatile int rebuildsWaiting;
    volatile long nodesDropped;
    volatile double ingestMicros;
    volatile double rebuildMicros;

    FarWorker() {
        Thread thread = new Thread(this::run, "Shaders Companion far store");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** Queues a captured section (main thread). */
    void post(CapturedSection section) {
        tasks.add(section);
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

    /** Lets go of everything queued (a new world, or the store turned off). */
    void clear() {
        tasks.clear();
    }

    /** Replaces the store (worker thread; null turns it off). M1b writes the old one's dirty nodes first. */
    void replaceStore(FarNodes next) {
        store = next;
        lastPublish = 0;
        publish(System.nanoTime());
    }

    FarNodes store() {
        return store;
    }

    @SuppressWarnings("unchecked")
    private void run() {
        while (true) {
            Object task;
            try {
                long now = System.nanoTime();
                long wait = store == null ? Long.MAX_VALUE : store.nanosUntilRebuild(now);
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
                }
                publish(now);
            } catch (Throwable t) {
                ShadersCompanion.LOGGER.error("Shaders Companion far store: the worker stopped the store after an error", t);
                store = null;
                tasks.clear();
                publish(System.nanoTime());
                FarStore.workerFailed(t);
            }
        }
    }

    /** The statistics for the main thread, at most 20 times a second (and whenever the queue runs dry). */
    private void publish(long now) {
        if (now - lastPublish < 50_000_000L && !tasks.isEmpty()) {
            return;
        }
        lastPublish = now;
        FarNodes s = store;
        if (s == null) {
            sectionsStored = 0;
            nodesInMemory = 0;
            bytesInMemory = 0;
            rebuildsWaiting = 0;
            nodesDropped = 0;
            return;
        }
        sectionsStored = s.sectionsStored;
        nodesInMemory = s.cachedNodes();
        bytesInMemory = s.bytesInMemory();
        rebuildsWaiting = s.rebuildsWaiting();
        long dropped = 0;
        for (long d : s.dropped) {
            dropped += d;
        }
        nodesDropped = dropped;
        ingestMicros = s.ingestMicros;
        rebuildMicros = s.rebuildMicros;
    }
}
