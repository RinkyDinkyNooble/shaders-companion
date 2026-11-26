package com.rinkynooble.shaderscompanion.verify;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.DebugOverlay;
import com.rinkynooble.shaderscompanion.client.far.FarChecks;
import com.rinkynooble.shaderscompanion.client.far.FarStore;
import com.rinkynooble.shaderscompanion.far.CapturedSection;
import com.rinkynooble.shaderscompanion.far.ColumnWire;
import com.rinkynooble.shaderscompanion.far.SavedChunks;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Dev checks of the far terrain's multiplayer side (research/lod/MULTIPLAYER.md), apart from DevRun's scenes:
 * <ul>
 * <li>-PdevServer=localhost:25565: at the title screen, the far store on for the run and the client joins that server
 * (offline mode: the dev client's player is "Dev"); in the world it logs the status lines every 5 seconds, and after
 * -PdevServerWait seconds (120) the store's level-0 and level-3 maps around the player
 * (screenshots/shaderscompanion-lod-L0.png, -L3.png) and the far store's stats, then quits.</li>
 * <li>-PdevWireCheck: opens the world -PdevWireWorld (shaderscompanion-far) and checks the column wire format on its
 * -PdevWireChunks (200) saved chunks nearest the player: packed and read back, every voxel's state, block and sky light
 * and biome must equal the import's ({@link SavedChunks#decode}); packed with the enclosed blocks hidden, every voxel
 * that changed must be an opaque full block with six opaque neighbours (across the column's sides: opaque), now dark;
 * then damaged columns (flipped bytes, cut short, a deflate bomb, a lying palette) must be turned down, never throw.
 * Logs one summary line ("wire check: ...") and quits.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ShadersCompanion.MOD_ID, value = Dist.CLIENT)
public final class MultiplayerRun {
    private static final String SERVER = System.getProperty("shaderscompanion.devServer", "");
    private static final int SERVER_WAIT = Integer.getInteger("shaderscompanion.devServerWait", 120);
    private static final boolean WIRE = Boolean.getBoolean("shaderscompanion.devWireCheck");
    private static final String WIRE_WORLD = System.getProperty("shaderscompanion.devWireWorld", "shaderscompanion-far");
    private static final int WIRE_CHUNKS = Integer.getInteger("shaderscompanion.devWireChunks", 200);
    private static boolean started;
    private static int ticks;
    private static boolean wireStarted;

    private MultiplayerRun() {
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (started || !(event.getNewScreen() instanceof TitleScreen) || SERVER.isEmpty() && !WIRE) {
            return;
        }
        started = true;
        Minecraft mc = Minecraft.getInstance();
        if (!SERVER.isEmpty()) {
            FarChecks.force(true, 0);
            mc.tell(() -> {
                ShadersCompanion.LOGGER.info("Shaders Companion multiplayer run: joining {}", SERVER);
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(SERVER), new ServerData("dev server", SERVER, false), false);
            });
        } else {
            mc.tell(() -> {
                ShadersCompanion.LOGGER.info("Shaders Companion wire check: opening {}", WIRE_WORLD);
                mc.createWorldOpenFlows().loadLevel(new TitleScreen(), WIRE_WORLD);
            });
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!started || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        ticks++;
        if (WIRE) {
            if (ticks == 100 && !wireStarted) {
                wireStarted = true;
                ServerLevel level = mc.getSingleplayerServer().getLevel(mc.level.dimension());
                ColumnWire.Expect expect = new ColumnWire.Expect(mc.level.getMinSection(), mc.level.getSectionsCount(),
                        mc.level.registryAccess().registryOrThrow(Registries.BIOME).asHolderIdMap());
                ChunkPos at = mc.player.chunkPosition();
                Thread thread = new Thread(() -> {
                    String line;
                    try {
                        line = wireCheck(new SavedChunks(level), expect, at.x, at.z);
                    } catch (Throwable t) {
                        line = "failed: " + t;
                        ShadersCompanion.LOGGER.error("Shaders Companion wire check failed", t);
                    }
                    ShadersCompanion.LOGGER.info("Shaders Companion wire check: {}", line);
                    mc.tell(mc::stop);
                }, "Shaders Companion wire check");
                thread.setDaemon(true);
                thread.start();
            }
            return;
        }
        if (ticks % 100 == 0) {
            ShadersCompanion.LOGGER.info("Shaders Companion multiplayer run at {} s: Far store: {} | Server far terrain: {}", ticks / 20, FarStore.status(),
                    com.rinkynooble.shaderscompanion.client.far.FarLink.status());
        }
        if (ticks == SERVER_WAIT * 20) {
            ShadersCompanion.LOGGER.info("Shaders Companion multiplayer run: {}", String.join(" | ", DebugOverlay.statusLines()));
            FarStore.mapAroundCamera(0, 6).thenAccept(line -> ShadersCompanion.LOGGER.info("Shaders Companion multiplayer run: {}", line));
            FarStore.mapAroundCamera(3, 4).thenAccept(line -> ShadersCompanion.LOGGER.info("Shaders Companion multiplayer run: {}", line));
            FarStore.stats().thenAccept(lines -> {
                for (String l : lines) {
                    ShadersCompanion.LOGGER.info("Shaders Companion multiplayer run: {}", l);
                }
            });
        }
        if (ticks == SERVER_WAIT * 20 + 200) {
            ShadersCompanion.LOGGER.info("Shaders Companion multiplayer run: done");
            mc.stop();
        }
    }

    // The wire check

    private static BlockState stateAt(CapturedSection s, int i) {
        return s == null || !s.hasBlocks() ? Blocks.AIR.defaultBlockState() : s.state(i);
    }

    private static int blockAt(CapturedSection s, int i) {
        return s == null || s.blockLight == null ? 0 : s.blockLight.get(i & 15, i >> 8, i >> 4 & 15);
    }

    private static int skyAt(CapturedSection s, int i) {
        return s == null ? -1 : s.sky(i & 15, i >> 8, i >> 4 & 15);
    }

    private static CapturedSection[] byIndex(List<CapturedSection> sections, int minSection, int count) {
        CapturedSection[] out = new CapturedSection[count];
        for (CapturedSection s : sections) {
            out[s.y - minSection] = s;
        }
        return out;
    }

    private static boolean opaque(BlockState state) {
        try {
            return state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String wireCheck(SavedChunks saved, ColumnWire.Expect expect, int centerX, int centerZ) throws Exception {
        long[] chunks = new long[4096];
        int[] n = {0};
        saved.stamps(centerX, centerZ, 24, (pos, written) -> {
            if (n[0] < chunks.length) {
                chunks[n[0]++] = pos;
            }
        });
        long[] order = SavedChunks.nearestFirst(chunks, n[0], centerX, centerZ, 24);
        int columns = 0;
        long voxels = 0;
        long stateDiffs = 0;
        long lightDiffs = 0;
        long biomeDiffs = 0;
        long sectionDiffs = 0;
        long hiddenReplaced = 0;
        long hiddenWrong = 0;
        long hiddenLit = 0;
        long hiddenOpenDiffs = 0;
        long oresBefore = 0;
        long oresAfter = 0;
        long bytesPlain = 0;
        long bytesHidden = 0;
        long encodeNanos = 0;
        long decodeNanos = 0;
        byte[] sample = null;
        int count = expect.sectionCount();
        int min = expect.minSection();
        for (int c = 0; c < order.length && columns < WIRE_CHUNKS; c++) {
            long pos = order[c];
            CompoundTag tag = saved.read(pos).join().orElse(null);
            if (tag == null) {
                continue;
            }
            tag = saved.upgraded(tag);
            if (SavedChunks.check(tag) != SavedChunks.Status.COMPLETE) {
                continue;
            }
            int cx = ChunkPos.getX(pos);
            int cz = ChunkPos.getZ(pos);
            List<CapturedSection> a = saved.decode(tag, cx, cz, 0, new int[1]);
            long t0 = System.nanoTime();
            byte[] plain = ColumnWire.encode(a, min, count, saved.biomeIds, false);
            long t1 = System.nanoTime();
            List<CapturedSection> b = ColumnWire.decode(plain, cx, cz, 0, expect);
            long t2 = System.nanoTime();
            byte[] hidden = ColumnWire.encode(a, min, count, saved.biomeIds, true);
            List<CapturedSection> h = ColumnWire.decode(hidden, cx, cz, 0, expect);
            encodeNanos += t1 - t0;
            decodeNanos += t2 - t1;
            bytesPlain += plain.length;
            bytesHidden += hidden.length;
            if (sample == null) {
                sample = plain;
            }
            CapturedSection[] sa = byIndex(a, min, count);
            CapturedSection[] sb = byIndex(b, min, count);
            CapturedSection[] sh = byIndex(h, min, count);
            for (int s = 0; s < count; s++) {
                if ((sa[s] == null) != (sb[s] == null) || (sa[s] == null) != (sh[s] == null)) {
                    sectionDiffs++;
                    continue;
                }
                if (sa[s] == null) {
                    continue;
                }
                for (int i = 0; i < 4096; i++) {
                    voxels++;
                    BlockState sA = stateAt(sa[s], i);
                    if (sA != stateAt(sb[s], i)) {
                        stateDiffs++;
                    }
                    if (blockAt(sa[s], i) != blockAt(sb[s], i) || skyAt(sa[s], i) != skyAt(sb[s], i)) {
                        lightDiffs++;
                    }
                    BlockState sH = stateAt(sh[s], i);
                    if (sA.getBlock().builtInRegistryHolder().key().location().getPath().endsWith("_ore")) {
                        oresBefore++;
                    }
                    if (sH.getBlock().builtInRegistryHolder().key().location().getPath().endsWith("_ore")) {
                        oresAfter++;
                    }
                    // Enclosed: opaque, with six opaque neighbours (across the column's sides: opaque)
                    int x = i & 15;
                    int z = i >> 4 & 15;
                    int y = i >> 8;
                    boolean enclosed = opaque(sA);
                    if (enclosed && x > 0) {
                        enclosed = opaque(stateAt(sa[s], i - 1));
                    }
                    if (enclosed && x < 15) {
                        enclosed = opaque(stateAt(sa[s], i + 1));
                    }
                    if (enclosed && z > 0) {
                        enclosed = opaque(stateAt(sa[s], i - 16));
                    }
                    if (enclosed && z < 15) {
                        enclosed = opaque(stateAt(sa[s], i + 16));
                    }
                    if (enclosed) {
                        enclosed = y > 0 ? opaque(stateAt(sa[s], i - 256)) : s == 0 || opaque(stateAt(sa[s - 1], i + 3840));
                    }
                    if (enclosed) {
                        enclosed = y < 15 ? opaque(stateAt(sa[s], i + 256)) : s + 1 < count && opaque(stateAt(sa[s + 1], i - 3840));
                    }
                    if (sH != sA) {
                        hiddenReplaced++;
                        if (!enclosed || !opaque(sH)) {
                            hiddenWrong++;
                        }
                    }
                    if (enclosed) {
                        if (blockAt(sh[s], i) != 0 || skyAt(sh[s], i) != 0) {
                            hiddenLit++;
                        }
                    } else if (sH != sA || blockAt(sa[s], i) != blockAt(sh[s], i) || skyAt(sa[s], i) != skyAt(sh[s], i)) {
                        hiddenOpenDiffs++;
                    }
                }
                for (int i = 0; i < 64; i++) {
                    if (sa[s].biomes[i] != sb[s].biomes[i] || sa[s].biomes[i] != sh[s].biomes[i]) {
                        biomeDiffs++;
                    }
                }
            }
            columns++;
        }
        String fuzz = sample == null ? "no sample" : fuzz(sample, expect);
        return String.format("%d columns, %d voxels: %d state, %d light, %d biome and %d section differences (expect 0 each); hidden: %d voxels replaced, "
                        + "%d not enclosed (expect 0), %d enclosed still lit (expect 0), %d open voxels changed (expect 0), ores %d -> %d; %.1f KB a column packed (%.1f KB hidden), encode %.2f ms, decode %.2f ms a column; "
                        + "damaged columns: %s",
                columns, voxels, stateDiffs, lightDiffs, biomeDiffs, sectionDiffs, hiddenReplaced, hiddenWrong, hiddenLit, hiddenOpenDiffs, oresBefore, oresAfter,
                columns == 0 ? 0.0 : bytesPlain / 1024.0 / columns, columns == 0 ? 0.0 : bytesHidden / 1024.0 / columns,
                columns == 0 ? 0.0 : encodeNanos / 1.0e6 / columns, columns == 0 ? 0.0 : decodeNanos / 1.0e6 / columns, fuzz);
    }

    /** Damaged columns: each must be turned down (ColumnWire.Rejected) or read as valid sections; nothing else may come out. */
    private static String fuzz(byte[] sample, ColumnWire.Expect expect) {
        Random random = new Random(20261126L);
        int rejected = 0;
        int read = 0;
        int other = 0;
        String firstOther = null;
        for (int k = 0; k < 3000; k++) {
            byte[] data;
            switch (k % 3) {
                case 0 -> {
                    data = sample.clone();
                    for (int f = 0; f < 1 + random.nextInt(4); f++) {
                        data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                    }
                }
                case 1 -> data = java.util.Arrays.copyOf(sample, random.nextInt(sample.length));
                default -> {
                    // The packed bytes damaged before deflating: a stream that inflates but lies
                    byte[] raw = inflate(sample);
                    for (int f = 0; f < 1 + random.nextInt(6); f++) {
                        raw[random.nextInt(raw.length)] = (byte) random.nextInt(256);
                    }
                    data = deflate(raw);
                }
            }
            try {
                ColumnWire.decode(data, 0, 0, 0, expect);
                read++;
            } catch (ColumnWire.Rejected e) {
                rejected++;
            } catch (Throwable t) {
                other++;
                if (firstOther == null) {
                    firstOther = t.toString();
                }
            }
        }
        // A deflate bomb: 256 MB of zeros
        String bomb;
        long start = System.nanoTime();
        try {
            byte[] zeros = new byte[1 << 20];
            java.util.zip.Deflater deflater = new java.util.zip.Deflater(9);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int i = 0; i < 256; i++) {
                deflater.setInput(zeros);
                while (!deflater.needsInput()) {
                    out.write(buf, 0, deflater.deflate(buf));
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                out.write(buf, 0, deflater.deflate(buf));
            }
            deflater.end();
            ColumnWire.decode(out.toByteArray(), 0, 0, 0, expect);
            bomb = "read (wrong!)";
        } catch (ColumnWire.Rejected e) {
            bomb = String.format("turned down in %.0f ms (%s)", (System.nanoTime() - start) / 1.0e6, e.getMessage());
        } catch (Throwable t) {
            bomb = "threw " + t;
        }
        // A palette that says 4096 ids with only a few bytes behind it
        String lying;
        try {
            io.netty.buffer.ByteBuf b = io.netty.buffer.Unpooled.buffer();
            net.minecraft.network.FriendlyByteBuf f = new net.minecraft.network.FriendlyByteBuf(b);
            f.writeByte(1);
            f.writeVarInt(expect.minSection());
            f.writeVarInt(expect.sectionCount());
            f.writeVarInt(1);
            f.writeVarInt(0);
            f.writeByte(1 | 1 << 3);
            f.writeVarInt(4096);
            f.writeVarInt(1);
            byte[] raw = new byte[b.readableBytes()];
            b.getBytes(0, raw);
            ColumnWire.decode(deflate(raw), 0, 0, 0, expect);
            lying = "read (wrong!)";
        } catch (ColumnWire.Rejected e) {
            lying = "turned down (" + e.getMessage() + ")";
        } catch (Throwable t) {
            lying = "threw " + t;
        }
        return String.format("%d turned down, %d read as valid, %d threw something else (expect 0)%s; deflate bomb %s; lying palette %s", rejected, read, other,
                firstOther == null ? "" : " first: " + firstOther, bomb, lying);
    }

    private static byte[] inflate(byte[] data) {
        try {
            java.util.zip.Inflater inflater = new java.util.zip.Inflater();
            inflater.setInput(data);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            while (!inflater.finished()) {
                int got = inflater.inflate(buf);
                if (got == 0 && inflater.needsInput()) {
                    break;
                }
                out.write(buf, 0, got);
            }
            inflater.end();
            return out.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] deflate(byte[] raw) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(4);
        deflater.setInput(raw);
        deflater.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        return out.toByteArray();
    }
}
