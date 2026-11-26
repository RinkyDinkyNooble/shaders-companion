package com.rinkynooble.shaderscompanion.far.net;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * The far terrain channel ({@code shaderscompanion:far}) between a server with the mod and its players' clients
 * (research/lod/MULTIPLAYER.md). The server says hello whenever a player enters a level ({@link Hello}: its protocol,
 * its world's id, whether and how far it shares); a client that takes the far terrain replies ({@link Reply}: how far and
 * how fast it wants it), then the server offers the saved chunk columns around the player, nearest first, with the time
 * each was last saved ({@link Offer}), the client says which it wants ({@link Want}: only ones it doesn't hold as saved
 * then), and the server sends those ({@link Column}) within the client's window of unacknowledged bytes ({@link Ack})
 * and both sides' rate limits; {@link Stop} ends it for the level. New and changed chunks are offered as the server saves
 * them. Nothing is ever sent from one player to another, and the server never generates a chunk for this.
 * <p>
 * The channel accepts any version on either side, so a game with or without the mod (or with another version of it) can
 * always join; versions are agreed in the hello instead ({@link #PROTOCOL}), and a side that doesn't speak the other's
 * stays silent. Every count and length is checked before anything is allocated; a message that breaks a limit throws
 * while decoding, and the handlers never see it. Each message carries the hello's epoch: anything from an earlier level
 * is ignored. Common code: the client's handlers sit behind {@link ClientSide}, set by the client.
 */
public final class FarNet {
    /** The far terrain protocol this build speaks. */
    public static final int PROTOCOL = 1;
    /** Most columns in one offer. */
    public static final int MAX_OFFER = 256;
    /** Most bytes of one column's data (compressed; Minecraft's limit for a custom payload is 1 MiB). */
    public static final int MAX_COLUMN_BYTES = 1_000_000;
    /** Most characters of a world id. */
    public static final int MAX_WORLD_ID = 64;
    /** The widest radius anyone may ask for or offer, in chunks (8,192 blocks). */
    public static final int MAX_RADIUS = 512;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(ShadersCompanion.MOD_ID, "far"), () -> Integer.toString(PROTOCOL),
            version -> true, version -> true);

    /** The client's handlers (set by the client's init; null on a dedicated server). */
    public interface ClientSide {
        void hello(Hello message);

        void offer(Offer message);

        /** Called on the network thread: the column goes to the far store's worker directly. */
        void column(Column message);
    }

    /** The server's handlers (set by the server side's init). */
    public interface ServerSide {
        void reply(ServerPlayer player, Reply message);

        void want(ServerPlayer player, Want message);

        void ack(ServerPlayer player, Ack message);

        void stop(ServerPlayer player, Stop message);
    }

    public static volatile ClientSide client;
    public static volatile ServerSide server;

    private FarNet() {
    }

    /** Registers the messages (once, at mod construction, on both sides). */
    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(Hello.class, id++, NetworkDirection.PLAY_TO_CLIENT).encoder(Hello::write).decoder(Hello::read)
                .consumerMainThread((m, c) -> toClient(c, () -> client.hello(m))).add();
        CHANNEL.messageBuilder(Reply.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(Reply::write).decoder(Reply::read)
                .consumerMainThread((m, c) -> toServer(c, p -> server.reply(p, m))).add();
        CHANNEL.messageBuilder(Offer.class, id++, NetworkDirection.PLAY_TO_CLIENT).encoder(Offer::write).decoder(Offer::read)
                .consumerMainThread((m, c) -> toClient(c, () -> client.offer(m))).add();
        CHANNEL.messageBuilder(Want.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(Want::write).decoder(Want::read)
                .consumerMainThread((m, c) -> toServer(c, p -> server.want(p, m))).add();
        CHANNEL.messageBuilder(Column.class, id++, NetworkDirection.PLAY_TO_CLIENT).encoder(Column::write).decoder(Column::read)
                .consumerNetworkThread((java.util.function.BiConsumer<Column, Supplier<NetworkEvent.Context>>) (m, c) -> toClient(c, () -> client.column(m))).add();
        CHANNEL.messageBuilder(Ack.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(Ack::write).decoder(Ack::read)
                .consumerMainThread((m, c) -> toServer(c, p -> server.ack(p, m))).add();
        CHANNEL.messageBuilder(Stop.class, id, NetworkDirection.PLAY_TO_SERVER).encoder(Stop::write).decoder(Stop::read)
                .consumerMainThread((m, c) -> toServer(c, p -> server.stop(p, m))).add();
    }

    private static void toClient(Supplier<NetworkEvent.Context> context, Runnable handler) {
        context.get().setPacketHandled(true);
        if (client != null && context.get().getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
            run(handler);
        }
    }

    private static void toServer(Supplier<NetworkEvent.Context> context, java.util.function.Consumer<ServerPlayer> handler) {
        context.get().setPacketHandled(true);
        ServerPlayer player = context.get().getSender();
        if (server != null && player != null && context.get().getDirection() == NetworkDirection.PLAY_TO_SERVER) {
            run(() -> handler.accept(player));
        }
    }

    /** A handler's failure is logged, never thrown into the connection (which would drop the player). */
    private static void run(Runnable handler) {
        try {
            handler.run();
        } catch (RuntimeException e) {
            ShadersCompanion.LOGGER.warn("Shaders Companion far terrain: a message failed: {}", e.toString());
        }
    }

    /** Sends a message to a player (any thread). */
    public static void send(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** Whether a player's game has the channel (else nothing is sent to it). */
    public static boolean present(ServerPlayer player) {
        return CHANNEL.isRemotePresent(player.connection.connection);
    }

    // Bounded reads

    static int count(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) {
            throw new IllegalArgumentException("far terrain message count " + n + " outside 0.." + max);
        }
        return n;
    }

    static int range(int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("far terrain message value " + value + " outside " + min + ".." + max);
        }
        return value;
    }

    // The messages

    /**
     * Server to client, whenever the player enters a level (joining, a dimension change, a respawn): the protocol the
     * server speaks, its world's id (never used as a file name as it is), whether it shares far terrain, and the most it
     * gives: radius in chunks, KB a second. {@code epoch} counts the player's hellos: later messages carry it.
     */
    public record Hello(int protocol, int epoch, String worldId, boolean shares, int maxRadius, int maxKbps) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(protocol);
            buf.writeVarInt(epoch);
            buf.writeUtf(worldId, MAX_WORLD_ID);
            buf.writeBoolean(shares);
            buf.writeVarInt(maxRadius);
            buf.writeVarInt(maxKbps);
        }

        static Hello read(FriendlyByteBuf buf) {
            return new Hello(buf.readVarInt(), buf.readVarInt(), buf.readUtf(MAX_WORLD_ID), buf.readBoolean(), range(buf.readVarInt(), 0, MAX_RADIUS),
                    range(buf.readVarInt(), 0, 1 << 20));
        }
    }

    /**
     * Client to server: the answer to a hello. {@code accept} false declines (nothing more is sent for that level);
     * otherwise the radius in chunks, KB a second and the window (KB of columns sent but not yet acknowledged) the client
     * wants; the server clamps each to its own limits.
     */
    public record Reply(int protocol, int epoch, boolean accept, int radius, int kbps, int windowKb) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(protocol);
            buf.writeVarInt(epoch);
            buf.writeBoolean(accept);
            buf.writeVarInt(radius);
            buf.writeVarInt(kbps);
            buf.writeVarInt(windowKb);
        }

        static Reply read(FriendlyByteBuf buf) {
            return new Reply(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), range(buf.readVarInt(), 0, MAX_RADIUS), range(buf.readVarInt(), 0, 1 << 20),
                    range(buf.readVarInt(), 0, 1 << 16));
        }
    }

    /**
     * Server to client: up to {@link #MAX_OFFER} saved chunk columns of the player's level and when each was last saved
     * (seconds since 1970, the server's clock). Positions are deltas from the previous one (zigzag var ints).
     */
    public record Offer(int epoch, int id, long[] chunks, int[] stamps) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(epoch);
            buf.writeVarInt(id);
            buf.writeVarInt(chunks.length);
            int lastX = 0;
            int lastZ = 0;
            for (int i = 0; i < chunks.length; i++) {
                int x = net.minecraft.world.level.ChunkPos.getX(chunks[i]);
                int z = net.minecraft.world.level.ChunkPos.getZ(chunks[i]);
                buf.writeVarInt(zigzag(x - lastX));
                buf.writeVarInt(zigzag(z - lastZ));
                buf.writeInt(stamps[i]);
                lastX = x;
                lastZ = z;
            }
        }

        static Offer read(FriendlyByteBuf buf) {
            int epoch = buf.readVarInt();
            int id = buf.readVarInt();
            int n = count(buf, MAX_OFFER);
            long[] chunks = new long[n];
            int[] stamps = new int[n];
            int x = 0;
            int z = 0;
            for (int i = 0; i < n; i++) {
                x += unzigzag(buf.readVarInt());
                z += unzigzag(buf.readVarInt());
                range(x, -1_900_000, 1_900_000);
                range(z, -1_900_000, 1_900_000);
                chunks[i] = net.minecraft.world.level.ChunkPos.asLong(x, z);
                stamps[i] = buf.readInt();
            }
            return new Offer(epoch, id, chunks, stamps);
        }
    }

    /** Client to server: which of an offer's columns it wants, one bit each in the offer's order. */
    public record Want(int epoch, int id, long[] bits) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(epoch);
            buf.writeVarInt(id);
            buf.writeVarInt(bits.length);
            for (long b : bits) {
                buf.writeLong(b);
            }
        }

        static Want read(FriendlyByteBuf buf) {
            int epoch = buf.readVarInt();
            int id = buf.readVarInt();
            int n = count(buf, (MAX_OFFER + 63) / 64);
            long[] bits = new long[n];
            for (int i = 0; i < n; i++) {
                bits[i] = buf.readLong();
            }
            return new Want(epoch, id, bits);
        }

        public boolean wants(int index) {
            int word = index >> 6;
            return word < bits.length && (bits[word] >>> (index & 63) & 1) != 0;
        }
    }

    /**
     * Server to client: a column the client wanted, as {@code ColumnWire} encodes it, with the save time it was offered
     * with. Empty data: the chunk turned out not complete (nothing to store; the time is still recorded).
     */
    public record Column(int epoch, int x, int z, int stamp, byte[] data) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(epoch);
            buf.writeInt(x);
            buf.writeInt(z);
            buf.writeInt(stamp);
            buf.writeByteArray(data);
        }

        static Column read(FriendlyByteBuf buf) {
            return new Column(buf.readVarInt(), range(buf.readInt(), -1_900_000, 1_900_000), range(buf.readInt(), -1_900_000, 1_900_000), buf.readInt(),
                    buf.readByteArray(MAX_COLUMN_BYTES));
        }
    }

    /** Client to server: this many more bytes of columns are handled (stored or turned down): the window moves on. */
    public record Ack(int epoch, int bytes) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(epoch);
            buf.writeVarInt(bytes);
        }

        static Ack read(FriendlyByteBuf buf) {
            return new Ack(buf.readVarInt(), range(buf.readVarInt(), 0, 1 << 30));
        }
    }

    /** Client to server: send nothing more for this level (the player turned it off, or the client's disk cap is reached). */
    public record Stop(int epoch, String reason) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(epoch);
            buf.writeUtf(reason, 64);
        }

        static Stop read(FriendlyByteBuf buf) {
            return new Stop(buf.readVarInt(), buf.readUtf(64));
        }
    }

    static int zigzag(int v) {
        return v << 1 ^ v >> 31;
    }

    static int unzigzag(int v) {
        return v >>> 1 ^ -(v & 1);
    }
}
