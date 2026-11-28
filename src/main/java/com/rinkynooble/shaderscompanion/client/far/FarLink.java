package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.client.Config;
import com.rinkynooble.shaderscompanion.far.ColumnWire;
import com.rinkynooble.shaderscompanion.far.net.FarNet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * The client's end of the far terrain channel ({@link FarNet}): a server with Shaders Companion says hello for each level
 * the player enters, with its world's id (the far store keys the world by it: {@link #identity}) and whether it shares
 * its saved chunks. With {@code farServerTerrain} on, the far store running and the player not having turned this
 * server down ({@link FarChoices}), the client takes them: it replies with its radius (its farRange) and rate
 * ({@code farServerKbps}), and the store's worker answers offers and stores columns ({@link FarFeed}). The first time a
 * server shares, a chat line says so and how to turn it down. Main thread, but {@link #column} (the network thread,
 * straight to the worker) and the queues the worker fills for the main thread to send.
 * <p>
 * In multiplayer the store waits for the hello up to {@link #HELLO_WAIT} after a level appears (a server without the mod
 * never says it; its world is keyed by its address alone).
 */
public final class FarLink implements FarNet.ClientSide {
    /** How long a new level waits for the server's hello before the far store starts without a world id (nanoseconds). */
    static final long HELLO_WAIT = 3_000_000_000L;
    /** Most bytes of columns waiting for the worker; past it a column is dropped unacknowledged (the server's window stops it). */
    private static final long MAX_QUEUED = 32L << 20;
    /** The window asked for: KB of columns sent but not yet handled. */
    private static final int WINDOW_KB = 2048;

    /** The world identity for the far store's key: still waiting for the hello, or the world id (null: none). */
    record Identity(boolean waiting, String worldId) {
    }

    private static ClientLevel seenLevel;
    private static long seenAt;
    private static FarNet.Hello hello;
    private static ClientLevel helloLevel;
    /** The current hello's epoch, for the network thread (-1: none). */
    private static volatile int epoch = -1;
    private static boolean replied;
    private static boolean accepted;
    private static int radius;
    /** Why far terrain isn't taken from this server, or null while it is (or before the hello). */
    private static String declined;
    /** The player's chunk, for the worker's offers. */
    static volatile int playerChunkX;
    static volatile int playerChunkZ;
    /** Messages the worker made, for the main thread to send. */
    static final ConcurrentLinkedQueue<Object> outbox = new ConcurrentLinkedQueue<>();
    /** Bytes of columns handled since the last acknowledgement. */
    static final AtomicLong ackBytes = new AtomicLong();
    private static final AtomicLong queuedBytes = new AtomicLong();
    private static long lastAck;
    private static long dropped;
    /** Offers waiting for the worker; past {@link #MAX_OFFERS_WAITING} one is dropped (a server sends one at a time). */
    private static final java.util.concurrent.atomic.AtomicInteger offersWaiting = new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_OFFERS_WAITING = 8;
    /**
     * The world ids this connection's server has named (each one a store of its own); past {@link #MAX_WORLD_IDS} a new
     * one is ignored and that level is keyed by the address alone, so no server can make endless store folders.
     */
    private static final java.util.Set<String> worldIds = new java.util.HashSet<>();
    private static Object worldIdsOf;
    private static final int MAX_WORLD_IDS = 8;
    /** When the client last told the server to stop (a new reply waits out the server's limit on repeated replies). */
    private static long stoppedAt;

    /** Made by the client's init (FarNet.client). */
    public FarLink() {
    }

    @Override
    public void hello(FarNet.Hello message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (worldIdsOf != mc.getConnection()) {
            worldIdsOf = mc.getConnection();
            worldIds.clear();
        }
        if (!message.worldId().isEmpty() && !worldIds.contains(message.worldId())) {
            if (worldIds.size() >= MAX_WORLD_IDS) {
                com.rinkynooble.shaderscompanion.ShadersCompanion.LOGGER.warn("Shaders Companion far terrain: the server named more than {} worlds on this "
                        + "connection; this level's far terrain is kept under its address alone", MAX_WORLD_IDS);
                message = new FarNet.Hello(message.protocol(), message.epoch(), "", message.shares(), message.maxRadius(), message.maxKbps());
            } else {
                worldIds.add(message.worldId());
            }
        }
        hello = message;
        helloLevel = mc.level;
        epoch = message.epoch();
        replied = false;
        accepted = false;
        declined = null;
        outbox.clear();
        ackBytes.set(0);
    }

    @Override
    public void offer(FarNet.Offer message) {
        int e = epoch;
        FarWorker w = FarStore.worker;
        if (message.epoch() != e || w == null || offersWaiting.incrementAndGet() > MAX_OFFERS_WAITING) {
            offersWaiting.decrementAndGet();
            return;
        }
        int r = radius;
        w.post(worker -> {
            offersWaiting.decrementAndGet();
            FarFeed feed = worker.feed;
            FarNodes store = worker.store();
            FarNet.Want want = feed != null && store != null && feed.epoch == e
                    ? feed.offer(store, message, playerChunkX, playerChunkZ, r, System.nanoTime())
                    : new FarNet.Want(e, message.id(), new long[0]);
            outbox.add(want);
        });
    }

    @Override
    public void column(FarNet.Column message) {
        FarWorker w = FarStore.worker;
        int size = message.data().length + 16;
        if (message.epoch() != epoch || w == null) {
            return;
        }
        if (queuedBytes.addAndGet(size) > MAX_QUEUED) {
            // Turned down unread, and acknowledged like any handled column, so the server's window moves on
            queuedBytes.addAndGet(-size);
            dropped++;
            ackBytes.addAndGet(size);
            return;
        }
        w.post(worker -> {
            queuedBytes.addAndGet(-size);
            FarFeed feed = worker.feed;
            FarNodes store = worker.store();
            if (feed != null && store != null && feed.epoch == message.epoch()) {
                long now = System.nanoTime();
                feed.column(store, message, now);
                String stop = feed.stopNow(store, now);
                if (stop != null) {
                    outbox.add(new FarNet.Stop(feed.epoch, stop.length() > 60 ? stop.substring(0, 60) : stop));
                }
            }
            ackBytes.addAndGet(size);
        });
    }

    /** The far store's world identity in multiplayer (main thread). */
    static Identity identity(Minecraft mc) {
        long now = System.nanoTime();
        if (mc.level != seenLevel) {
            seenLevel = mc.level;
            seenAt = now;
        }
        if (hello != null && helloLevel == mc.level) {
            String id = hello.worldId();
            return new Identity(false, id == null || id.isEmpty() ? null : id);
        }
        return new Identity(now - seenAt < HELLO_WAIT, null);
    }

    /** Each client tick: forgets a hello of a level gone, replies once the store runs, sends what waits. */
    public static void tick(Minecraft mc) {
        if (mc.player != null) {
            playerChunkX = mc.player.chunkPosition().x;
            playerChunkZ = mc.player.chunkPosition().z;
        }
        if (helloLevel != null && helloLevel != mc.level) {
            hello = null;
            helloLevel = null;
            epoch = -1;
            accepted = false;
            declined = null;
            outbox.clear();
        }
        if (mc.getConnection() == null) {
            return;
        }
        boolean running = hello != null && FarStore.runningFor(mc.level, FarFolders.serverKey(mc.getCurrentServer(), identity(mc).worldId()));
        long now = System.nanoTime();
        if (accepted && !running) {
            // The far store stopped (turned off, or it failed): the server is told, and asked again once it runs
            outbox.add(new FarNet.Stop(epoch, "the far store is off"));
            replied = false;
        }
        if (hello != null && !replied && running && now - stoppedAt > 6_000_000_000L) {
            reply(mc);
        }
        Object message;
        while ((message = outbox.poll()) != null) {
            if (messageEpoch(message) == epoch) {
                FarNet.CHANNEL.sendToServer(message);
                if (message instanceof FarNet.Stop stop) {
                    accepted = false;
                    stoppedAt = now;
                    declined = "stopped: " + stop.reason();
                    if (!stop.reason().equals("the far store is off")) {
                        mc.gui.getChat().addMessage(Component.literal("Shaders Companion: no more far terrain from this server (" + stop.reason() + ")")
                                .withStyle(ChatFormatting.GRAY));
                    }
                }
            }
        }
        long bytes = ackBytes.get();
        if (bytes > 0 && (bytes >= 256 * 1024 || now - lastAck > 250_000_000L)) {
            ackBytes.addAndGet(-bytes);
            lastAck = now;
            FarNet.CHANNEL.sendToServer(new FarNet.Ack(epoch, (int) Math.min(bytes, 1 << 30)));
        }
    }

    private static int messageEpoch(Object message) {
        if (message instanceof FarNet.Want want) {
            return want.epoch();
        }
        if (message instanceof FarNet.Stop stop) {
            return stop.epoch();
        }
        return -2;
    }

    /** The reply to the hello: taken, or declined (with the reason kept for the status line). */
    private static void reply(Minecraft mc) {
        replied = true;
        FarNet.Hello h = hello;
        String server = FarFolders.serverBase(mc.getCurrentServer());
        java.nio.file.Path lodRoot = FarFolders.lodRoot(mc.gameDirectory.toPath());
        String choice = FarChoices.get(lodRoot, server);
        declined = null;
        if (!h.shares()) {
            declined = "this server doesn't share its far terrain here";
        } else if (h.protocol() != FarNet.PROTOCOL) {
            declined = "this server speaks far terrain protocol " + h.protocol() + ", this game " + FarNet.PROTOCOL + " (update the mod on one side)";
        } else if (!Config.FAR_SERVER_TERRAIN.get()) {
            declined = "turned off (farServerTerrain)";
        } else if (FarChoices.DENY.equals(choice)) {
            declined = "turned down for this server (/shaderscompanion far server allow)";
        }
        boolean take = declined == null;
        radius = Math.max(1, Math.min(h.maxRadius(), FarTerrain.farRange() / 16 + 1));
        int kbps = Math.max(16, Math.min(h.maxKbps(), Config.FAR_SERVER_KBPS.get()));
        if (take) {
            FarStore.startFeed(h.epoch(), new ColumnWire.Expect(mc.level.getMinSection(), mc.level.getSectionsCount(),
                    mc.level.registryAccess().registryOrThrow(Registries.BIOME).asHolderIdMap()), server);
            accepted = true;
            if (choice == null) {
                FarChoices.set(lodRoot, server, FarChoices.ALLOW);
                notice(mc, radius);
            }
        }
        FarNet.CHANNEL.sendToServer(new FarNet.Reply(FarNet.PROTOCOL, h.epoch(), take, radius, kbps, WINDOW_KB));
    }

    /** The chat line the first time a server shares, with the command that turns it down. */
    private static void notice(Minecraft mc, int radius) {
        Component deny = Component.literal("[turn it off for this server]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/shaderscompanion far server deny"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/shaderscompanion far server deny"))));
        mc.gui.getChat().addMessage(Component.literal(String.format("Shaders Companion: this server shares its far terrain. The world's saved chunks within %d blocks "
                + "of you come in the background (at most %d MB on disk for this server). ", radius * 16, Config.FAR_SERVER_DISK_MB.get())).withStyle(ChatFormatting.GRAY)
                .append(deny));
    }

    /** {@code far server allow|deny}: the choice for the server played on now (saved); applies at once. */
    public static String choose(boolean allow) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null || mc.getCurrentServer() == null) {
            return "This is for servers: in singleplayer the far store reads the world itself";
        }
        String server = FarFolders.serverBase(mc.getCurrentServer());
        FarChoices.set(FarFolders.lodRoot(mc.gameDirectory.toPath()), server, allow ? FarChoices.ALLOW : FarChoices.DENY);
        if (!allow && accepted && hello != null) {
            outbox.add(new FarNet.Stop(hello.epoch(), "turned off by the player"));
            FarStore.stopFeed();
        } else if (allow && hello != null && !accepted) {
            // Asked again: the reply goes out on the next tick
            replied = false;
        }
        return allow ? "Far terrain from this server: on (it comes in the background while the far store runs)"
                : "Far terrain from this server: off (what it sent stays stored; /shaderscompanion far server allow turns it back on)";
    }

    /** The status line ("Server far terrain: ..."), with the worker's part. */
    public static String status() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            return "singleplayer (the far store reads the world itself)";
        }
        if (mc.level == null) {
            return "no world";
        }
        if (hello == null) {
            return identity(mc).waiting() ? "waiting for the server's hello" : "the server doesn't have Shaders Companion (far terrain is what you load)";
        }
        String world = "world " + (hello.worldId().isEmpty() ? "without an id" : FarFolders.hash(hello.worldId(), 8));
        if (!replied) {
            return world + "; waiting for the far store (farStore)";
        }
        if (!accepted) {
            return world + "; not taken: " + declined;
        }
        FarWorker w = FarStore.worker;
        String feed = w == null ? null : w.feedStatus;
        return String.format("%s; taking it, radius %d chunks: %s%s", world, radius, feed == null ? "starting" : feed, dropped > 0 ? ", " + dropped + " dropped (the worker was behind)" : "");
    }
}
