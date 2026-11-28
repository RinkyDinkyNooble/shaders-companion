package com.rinkynooble.shaderscompanion.server;

import com.mojang.brigadier.CommandDispatcher;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.far.SavedChunks;
import com.rinkynooble.shaderscompanion.far.WorldIds;
import com.rinkynooble.shaderscompanion.far.net.FarNet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/**
 * Far terrain sharing, the server's side (research/lod/MULTIPLAYER.md; settings {@link ShareConfig}): a dedicated server,
 * or a singleplayer world opened to LAN, sends the players who have the mod the world's saved chunks around them for
 * their far terrain, over {@link FarNet}. Every player who enters a level gets a hello (with the world's id, whether the
 * level is shared and the limits); a client that takes it replies, and from then on its {@link ShareSession} offers it
 * the saved columns within its radius nearest first, then what the server saves new or changed, and sends the ones it
 * wants within its window and both rate limits ({@link #tick}). The heavy part (region tables, reading, packing) runs on
 * {@link ShareWorker}; the server thread only keeps the books. The host of a LAN world is never sent anything (its own
 * game reads the save itself).
 */
public final class FarShare implements FarNet.ServerSide {
    private static MinecraftServer server;
    private static String worldId;
    private static ShareWorker worker;
    private static final Map<UUID, ShareSession> sessions = new HashMap<>();
    private static final Map<ResourceKey<Level>, SavedChunks> saved = new HashMap<>();
    private static double totalTokens;
    private static long lastTotalRefill;
    private static boolean warnedProtocol;

    private FarShare() {
    }

    /** Registers the server side's events (at mod construction, on both sides: an integrated server shares on LAN). */
    public static void init() {
        FarNet.server = new FarShare();
        MinecraftForge.EVENT_BUS.addListener(FarShare::onStarted);
        MinecraftForge.EVENT_BUS.addListener(FarShare::onStopping);
        MinecraftForge.EVENT_BUS.addListener(FarShare::onLogin);
        MinecraftForge.EVENT_BUS.addListener(FarShare::onLogout);
        MinecraftForge.EVENT_BUS.addListener(FarShare::onChangedDimension);
        MinecraftForge.EVENT_BUS.addListener(FarShare::onRespawn);
        MinecraftForge.EVENT_BUS.addListener(FarShare::onChunkSave);
        MinecraftForge.EVENT_BUS.addListener(FarShare::onTick);
        MinecraftForge.EVENT_BUS.addListener(FarShare::onCommands);
    }

    private static void onStarted(ServerStartedEvent event) {
        server = event.getServer();
        worldId = null;
        worker = new ShareWorker();
        sessions.clear();
        saved.clear();
    }

    private static void onStopping(ServerStoppingEvent event) {
        if (worker != null) {
            worker.stop();
        }
        worker = null;
        sessions.clear();
        saved.clear();
        server = null;
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            hello(player);
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        sessions.remove(event.getEntity().getUUID());
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            hello(player);
        }
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            hello(player);
        }
    }

    /** Whether this server shares at all: a dedicated server, or an integrated one opened to LAN (with farShare.lan). */
    private static boolean sharing() {
        return server != null && ShareConfig.SHARE.get() && (server.isDedicatedServer() || server.isPublished() && ShareConfig.LAN.get());
    }

    private static boolean excluded(ResourceKey<Level> dimension) {
        String id = dimension.location().toString();
        for (String entry : ShareConfig.EXCLUDED_DIMENSIONS.get()) {
            if (entry.trim().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** A player entered a level: a new session epoch, and the hello (to games with the mod; never the LAN host). */
    private static void hello(ServerPlayer player) {
        if (server == null || worker == null || server.isSingleplayerOwner(player.getGameProfile()) || !FarNet.present(player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        boolean shares = sharing() && !excluded(level.dimension());
        SavedChunks levelChunks = shares ? saved.computeIfAbsent(level.dimension(), key -> new SavedChunks(level)) : null;
        if (worldId == null) {
            // Made the first time a player with the mod joins (a singleplayer world gets one only once opened to LAN)
            try {
                worldId = WorldIds.readOrCreate(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
            } catch (java.io.IOException e) {
                // Without an id the hello still goes out (players key the world by the address alone)
                worldId = "";
                ShadersCompanion.LOGGER.warn("Shaders Companion far share: couldn't read or write the world's id: {}", e.toString());
            }
        }
        ShareSession session = sessions.computeIfAbsent(player.getUUID(), ShareSession::new);
        session.reset(player, levelChunks, shares);
        FarNet.send(player, new FarNet.Hello(FarNet.PROTOCOL, session.epoch, worldId, shares, ShareConfig.RADIUS.get(), ShareConfig.KBPS_PER_PLAYER.get()));
    }

    private static ShareSession session(ServerPlayer player, int epoch) {
        ShareSession session = sessions.get(player.getUUID());
        return session != null && session.epoch == epoch && session.player == player ? session : null;
    }

    @Override
    public void reply(ServerPlayer player, FarNet.Reply message) {
        ShareSession session = session(player, message.epoch());
        long now = System.nanoTime();
        // The first reply of a hello always counts; another in the same one (the player turned it off and on) at most every 5 s
        if (session == null || session.accepted || !session.shares || !message.accept()
                || session.lastReplyEpoch == session.epoch && now - session.lastReplyAt < ShareSession.REPLY_INTERVAL) {
            return;
        }
        if (message.protocol() != FarNet.PROTOCOL) {
            if (!warnedProtocol) {
                warnedProtocol = true;
                ShadersCompanion.LOGGER.info("Shaders Companion far share: {} speaks far terrain protocol {}, this server {}: nothing is shared with such players",
                        player.getGameProfile().getName(), message.protocol(), FarNet.PROTOCOL);
            }
            return;
        }
        session.accepted = true;
        session.lastReplyAt = now;
        session.lastReplyEpoch = session.epoch;
        session.radius = Math.max(1, Math.min(message.radius(), ShareConfig.RADIUS.get()));
        session.bytesPerSecond = Math.max(16, Math.min(message.kbps(), ShareConfig.KBPS_PER_PLAYER.get())) * 1024L;
        session.window = Math.max(64, Math.min(message.windowKb(), 8192)) * 1024L;
        session.acceptedAt = System.nanoTime();
        session.lastRefill = session.acceptedAt;
        plan(session);
        ShadersCompanion.LOGGER.info("Shaders Companion far share: sharing {}'s far terrain with {} (radius {} chunks, {} KB a second)", session.dimension.location(),
                player.getGameProfile().getName(), session.radius, session.bytesPerSecond / 1024);
    }

    @Override
    public void want(ServerPlayer player, FarNet.Want message) {
        ShareSession session = session(player, message.epoch());
        if (session != null && session.accepted) {
            session.want(message);
        }
    }

    @Override
    public void ack(ServerPlayer player, FarNet.Ack message) {
        ShareSession session = session(player, message.epoch());
        if (session != null) {
            session.inFlight = Math.max(0, session.inFlight - message.bytes());
        }
    }

    @Override
    public void stop(ServerPlayer player, FarNet.Stop message) {
        ShareSession session = session(player, message.epoch());
        if (session != null && session.accepted) {
            ShadersCompanion.LOGGER.info("Shaders Companion far share: {} stopped taking far terrain ({})", player.getGameProfile().getName(), message.reason());
            session.stopSending();
        }
    }

    private static void plan(ShareSession session) {
        session.planning = true;
        session.planCenterX = session.chunkX();
        session.planCenterZ = session.chunkZ();
        worker.plan(session, session.epoch, session.saved, session.planCenterX, session.planCenterZ, session.radius);
    }

    /** A chunk was saved (the server thread): a complete chunk is offered to the players for whom it is far terrain. */
    private static void onChunkSave(ChunkDataEvent.Save event) {
        if (server == null || sessions.isEmpty() || !ShareConfig.UPDATES.get() || !(event.getLevel() instanceof ServerLevel level)
                || event.getChunk().getStatus() != ChunkStatus.FULL || !server.isSameThread()) {
            return;
        }
        long pos = event.getChunk().getPos().toLong();
        int now = (int) (System.currentTimeMillis() / 1000L);
        int viewDistance = server.getPlayerList().getViewDistance();
        for (ShareSession session : sessions.values()) {
            if (session.accepted && session.dimension == level.dimension() && session.farFor(pos, viewDistance)) {
                session.push(pos, now);
            }
        }
    }

    /** Each server tick: plans, offers, reads and sends, every session in turn. */
    private static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null || worker == null || sessions.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        long totalRate = ShareConfig.KBPS_TOTAL.get() * 1024L;
        if (totalRate > 0) {
            totalTokens = Math.min(totalRate, totalTokens + totalRate * ((now - lastTotalRefill) / 1.0e9));
        } else {
            // No total limit: nothing is drawn from it (a limit set later starts from empty, not from a debt)
            totalTokens = 0;
        }
        lastTotalRefill = now;
        int viewDistance = server.getPlayerList().getViewDistance();
        int nowSeconds = (int) (System.currentTimeMillis() / 1000L);
        boolean hide = ShareConfig.HIDE_ENCLOSED.get();
        for (ShareSession session : sessions.values()) {
            if (!session.accepted || session.player.isRemoved()) {
                continue;
            }
            tick(session, now, nowSeconds, viewDistance, hide, totalRate);
        }
    }

    private static void tick(ShareSession session, long now, int nowSeconds, int viewDistance, boolean hide, long totalRate) {
        session.ticked++;
        // The worker's plans (the newest one wins)
        ShareSession.Plan plan;
        while ((plan = session.plans.poll()) != null) {
            if (plan.epoch() == session.epoch) {
                session.toOffer.clear();
                session.toOfferStamps.clear();
                for (int i = 0; i < plan.chunks().length; i++) {
                    session.toOffer.enqueue(plan.chunks()[i]);
                    session.toOfferStamps.enqueue(plan.stamps()[i]);
                }
                session.planning = false;
                session.lastPlan = plan.chunks().length;
            }
        }
        if (!session.planning && Math.max(Math.abs(session.chunkX() - session.planCenterX), Math.abs(session.chunkZ() - session.planCenterZ)) > ShareSession.REPLAN_CHUNKS) {
            plan(session);
        }
        if (now - session.acceptedAt < ShareSession.START_DELAY) {
            return;
        }
        // The next offer once the last is answered (or given up) and the wanted backlog is small
        if (session.offerOut() && now - session.offerSentAt > ShareSession.OFFER_TIMEOUT) {
            session.offerChunks = null;
            session.offerStamps = null;
        }
        if (!session.offerOut() && session.wanted.size() < ShareSession.MAX_WANTED - FarNet.MAX_OFFER) {
            FarNet.Offer offer = session.nextOffer(viewDistance, nowSeconds);
            if (offer != null) {
                session.offerSentAt = now;
                FarNet.send(session.player, offer);
            }
        }
        // Reads: a few a player, a few in all, while little waits to be sent
        while (!session.wanted.isEmpty() && session.reads.get() < ShareSession.MAX_READS && worker.reads.get() < ShareWorker.MAX_READS
                && session.ready.size() < ShareSession.MAX_READY) {
            worker.pack(session, session.epoch, session.saved, session.wanted.dequeueLong(), session.wantedStamps.dequeueInt(), hide);
        }
        // Sends: within the window and both rate limits (a column may overdraw them once)
        session.tokens = Math.min(session.bytesPerSecond, session.tokens + session.bytesPerSecond * ((now - session.lastRefill) / 1.0e9));
        session.lastRefill = now;
        ShareSession.Ready ready;
        while ((ready = session.ready.peek()) != null) {
            if (ready.epoch() != session.epoch) {
                session.ready.poll();
                continue;
            }
            int size = ready.data().length + 16;
            if (session.inFlight > 0 && session.inFlight + size > session.window || session.tokens <= 0 || totalRate > 0 && totalTokens <= 0) {
                break;
            }
            session.ready.poll();
            FarNet.send(session.player, new FarNet.Column(session.epoch, net.minecraft.world.level.ChunkPos.getX(ready.pos()), net.minecraft.world.level.ChunkPos.getZ(ready.pos()),
                    ready.stamp(), ready.data()));
            session.tokens -= size;
            if (totalRate > 0) {
                totalTokens -= size;
            }
            session.inFlight += size;
            session.bytesSent += size;
            if (ready.data().length == 0) {
                session.notComplete++;
            } else {
                session.columnsSent++;
            }
        }
    }

    // The admin command

    private static void onCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("farshare").requires(source -> source.hasPermission(2))
                .executes(context -> {
                    for (String line : status()) {
                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return 1;
                }));
    }

    /** The {@code /farshare} lines: the settings, the worker and each player's session. */
    static String[] status() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (server == null || worker == null) {
            return new String[]{"Far terrain sharing: no server running"};
        }
        lines.add(String.format("Far terrain sharing: %s; radius %d chunks, %d KB/s a player, %s in all; updates %s; enclosed blocks %s",
                sharing() ? "on" : "off" + (server.isDedicatedServer() ? "" : " (a singleplayer world shares only while open to LAN)"), ShareConfig.RADIUS.get(),
                ShareConfig.KBPS_PER_PLAYER.get(), ShareConfig.KBPS_TOTAL.get() == 0 ? "no limit" : ShareConfig.KBPS_TOTAL.get() + " KB/s",
                ShareConfig.UPDATES.get() ? "on" : "off", ShareConfig.HIDE_ENCLOSED.get() ? "hidden" : "sent"));
        lines.add(String.format("Worker: %d columns packed (%.2f ms each), %d from its cache (%d MB), %d tasks waiting, %d reads in flight, %d failures",
                worker.packed, worker.packed == 0 ? 0.0 : worker.packNanos / 1.0e6 / worker.packed, worker.cacheHits, worker.cacheBytes() >> 20, worker.queued(),
                worker.reads.get(), worker.failures));
        for (ShareSession s : sessions.values()) {
            lines.add(String.format("%s in %s: %s; %d columns sent (%.1f MB), %d not complete; last plan %d columns around %d, %d%s; %d offered (%d new or changed; "
                            + "%d left out as the game's own chunks, %d as offered before), %d wanted, %d to offer, %d waiting to be read; epoch %d, %d plans queued (epochs %s), removed %s, ticked %d",
                    s.player.getGameProfile().getName(), s.dimension.location(), !s.shares ? "not shared here" : s.accepted ? "taking it" : "not taking it",
                    s.columnsSent, s.bytesSent / 1048576.0, s.notComplete, s.lastPlan, s.planCenterX, s.planCenterZ, s.planning ? " (planning)" : "", s.offeredTotal,
                    s.pushesOffered, s.skippedNotFar, s.skippedOffered, s.wantedTotal, s.toOffer.size() + s.pushes.size(), s.wanted.size(), s.epoch, s.plans.size(),
                    s.plans.stream().map(pl -> Integer.toString(pl.epoch())).toList(), s.player.isRemoved(), s.ticked));
        }
        return lines.toArray(new String[0]);
    }
}
