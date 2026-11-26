package com.rinkynooble.shaderscompanion.far;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * A chunk column of far terrain on the network: what {@link SavedChunks#decode} reads from a saved chunk, packed by the
 * server and read back into {@link CapturedSection}s by the client, which stores them exactly as it stores the chunks it
 * imports. Block states and biomes are sent by the ids the game already shares for its chunk packets (the block state
 * registry, synced by Forge, and the level's biome registry, synced at login), each section with its own palette.
 * <p>
 * Layout (deflated as a whole): a version byte, the column's lowest section and section count (the level's: the client
 * rejects a column whose layout isn't its level's), the number of sections that follow, then per section its index in
 * the column, a flags byte (states present, block light mode, sky light mode), the states (palette size, palette ids,
 * then the 4096 indices bit-packed as vanilla packs them, values never spanning two longs), the biomes the same way (64
 * cells), and the light: block light none, one value or 2048 bytes; sky light one value, the 256 values from above
 * ({@link CapturedSection#skyColumn}) or 2048 bytes.
 * <p>
 * Reading checks everything before it allocates or stores: the inflated size (at most {@link #maxInflated}), every count
 * and index, every id against the client's own registries (an unknown id turns the column down), and that the data ends
 * where it should. A column that fails any check is turned down whole; nothing about it is stored. Common code.
 */
public final class ColumnWire {
    private static final int VERSION = 1;
    private static final int STATES = 1;
    private static final int BLOCK_LIGHT_SHIFT = 1;
    private static final int SKY_LIGHT_SHIFT = 3;
    private static final int LIGHT_NONE = 0;
    private static final int LIGHT_UNIFORM = 1;
    private static final int LIGHT_COLUMN = 2;
    private static final int LIGHT_FULL = 3;
    /** A level never has more sections than this (4,064 blocks of height). */
    public static final int MAX_SECTIONS = 254;

    private ColumnWire() {
    }

    /** Why a column was turned down. */
    public static final class Rejected extends Exception {
        public Rejected(String message) {
            super(message, null, false, false);
        }
    }

    // Encoding (the server)

    /**
     * Packs a column's sections (from {@link SavedChunks#decode}) and deflates them. With {@code hide}, the enclosed
     * blocks are replaced first ({@link EnclosedBlocks}), so the column never carries the ores and caves' variety deep
     * inside the rock that no one can see.
     */
    public static byte[] encode(List<CapturedSection> sections, int minSection, int sectionCount, IdMap<Holder<Biome>> biomes, boolean hide) {
        int[][] ids = new int[sectionCount][];
        CapturedSection[] byIndex = new CapturedSection[sectionCount];
        for (CapturedSection section : sections) {
            int index = section.y - minSection;
            if (index < 0 || index >= sectionCount) {
                continue;
            }
            byIndex[index] = section;
            if (section.hasBlocks()) {
                int[] states = new int[4096];
                for (int i = 0; i < 4096; i++) {
                    states[i] = Block.BLOCK_STATE_REGISTRY.getId(section.state(i));
                }
                ids[index] = states;
            }
        }
        boolean[][] darkened = hide ? EnclosedBlocks.hide(ids) : null;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(64 * 1024));
        try {
            buf.writeByte(VERSION);
            buf.writeVarInt(minSection);
            buf.writeVarInt(sectionCount);
            int present = 0;
            for (CapturedSection s : byIndex) {
                if (s != null) {
                    present++;
                }
            }
            buf.writeVarInt(present);
            for (int index = 0; index < sectionCount; index++) {
                CapturedSection section = byIndex[index];
                if (section == null) {
                    continue;
                }
                buf.writeVarInt(index);
                int[] states = ids[index];
                int blockMode = lightMode(section.blockLight, false);
                int skyMode = section.skyLight != null ? lightMode(section.skyLight, true) : section.skyColumn != null ? LIGHT_COLUMN : LIGHT_UNIFORM;
                boolean[] dark = darkened == null ? null : darkened[index];
                if (dark != null) {
                    // Replaced blocks are dark (as the far store keeps hidden voxels): light written in full
                    blockMode = section.blockLight == null ? LIGHT_NONE : LIGHT_FULL;
                    skyMode = LIGHT_FULL;
                }
                buf.writeByte((states != null ? STATES : 0) | blockMode << BLOCK_LIGHT_SHIFT | skyMode << SKY_LIGHT_SHIFT);
                if (states != null) {
                    writePalette(buf, states, 4096, 12);
                }
                int[] biomeIds = new int[64];
                for (int i = 0; i < 64; i++) {
                    @SuppressWarnings("unchecked")
                    Holder<Biome> biome = (Holder<Biome>) section.biomes[i];
                    biomeIds[i] = Math.max(0, biomes.getId(biome));
                }
                writePalette(buf, biomeIds, 64, 6);
                writeBlockLight(buf, section, blockMode, dark);
                writeSkyLight(buf, section, skyMode, dark);
            }
            byte[] raw = new byte[buf.readableBytes()];
            buf.getBytes(0, raw);
            return deflate(raw);
        } finally {
            buf.release();
        }
    }

    private static int lightMode(DataLayer layer, boolean sky) {
        if (layer == null) {
            return sky ? LIGHT_UNIFORM : LIGHT_NONE;
        }
        return layer.isDefinitelyHomogenous() ? LIGHT_UNIFORM : LIGHT_FULL;
    }

    private static void writeBlockLight(FriendlyByteBuf buf, CapturedSection section, int mode, boolean[] dark) {
        if (mode == LIGHT_UNIFORM) {
            buf.writeByte(section.blockLight.get(0, 0, 0));
        } else if (mode == LIGHT_FULL) {
            byte[] data = new byte[2048];
            for (int i = 0; i < 4096; i++) {
                int v = dark != null && dark[i] ? 0 : section.blockLight.get(i & 15, i >> 8, i >> 4 & 15);
                data[i >> 1] |= (byte) (v << ((i & 1) << 2));
            }
            buf.writeBytes(data);
        }
    }

    private static void writeSkyLight(FriendlyByteBuf buf, CapturedSection section, int mode, boolean[] dark) {
        if (mode == LIGHT_UNIFORM) {
            buf.writeByte(section.skyLight != null ? section.skyLight.get(0, 0, 0) : section.skyUniform);
        } else if (mode == LIGHT_COLUMN) {
            buf.writeBytes(section.skyColumn);
        } else {
            byte[] data = new byte[2048];
            for (int i = 0; i < 4096; i++) {
                int v = dark != null && dark[i] ? 0 : section.sky(i & 15, i >> 8, i >> 4 & 15);
                data[i >> 1] |= (byte) (v << ((i & 1) << 2));
            }
            buf.writeBytes(data);
        }
    }

    /** A palette (size, then the ids) and the values' indices into it, bit-packed (none for a palette of one). */
    private static void writePalette(FriendlyByteBuf buf, int[] values, int count, int maxBits) {
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap index = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        index.defaultReturnValue(-1);
        int[] palette = new int[Math.min(count, 1 << maxBits)];
        int size = 0;
        int[] at = new int[count];
        for (int i = 0; i < count; i++) {
            int p = index.get(values[i]);
            if (p < 0) {
                p = size;
                palette[size++] = values[i];
                index.put(values[i], p);
            }
            at[i] = p;
        }
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            buf.writeVarInt(palette[i]);
        }
        if (size > 1) {
            int bits = 32 - Integer.numberOfLeadingZeros(size - 1);
            int perLong = 64 / bits;
            long[] packed = new long[(count + perLong - 1) / perLong];
            for (int i = 0; i < count; i++) {
                packed[i / perLong] |= (long) at[i] << (i % perLong * bits);
            }
            for (long l : packed) {
                buf.writeLong(l);
            }
        }
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(4);
        try {
            deflater.setInput(raw);
            deflater.finish();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length / 4 + 64);
            byte[] chunk = new byte[16384];
            while (!deflater.finished()) {
                int n = deflater.deflate(chunk);
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    // Decoding (the client)

    /** The most bytes a column of this many sections can inflate to (a fixed bound, set before inflating). */
    public static int maxInflated(int sectionCount) {
        // Per section: index, flags, 4096 palette ids of 5 bytes and 820 longs at most (12 bits, 5 a long), the biomes
        // (64 ids, 7 longs), the light
        return 16 + sectionCount * (8 + 4096 * 5 + 820 * 8 + 8 + 64 * 5 + 7 * 8 + 2049 + 2049);
    }

    /** What the client checks a column against: its level's layout and registries. */
    public record Expect(int minSection, int sectionCount, IdMap<Holder<Biome>> biomes) {
    }

    /** Reads a column for the client's far store (worker thread): its sections, or {@link Rejected} with the reason. */
    public static List<CapturedSection> decode(byte[] data, int chunkX, int chunkZ, int generation, Expect expect) throws Rejected {
        if (expect.sectionCount() < 1 || expect.sectionCount() > MAX_SECTIONS) {
            throw new Rejected("the level's height is outside what far terrain supports");
        }
        byte[] raw = inflate(data, maxInflated(expect.sectionCount()));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        try {
            if (buf.readUnsignedByte() != VERSION) {
                throw new Rejected("unknown column version");
            }
            int minSection = buf.readVarInt();
            int sectionCount = buf.readVarInt();
            if (minSection != expect.minSection() || sectionCount != expect.sectionCount()) {
                throw new Rejected("the column's height (" + minSection + ", " + sectionCount + " sections) isn't the level's");
            }
            int present = buf.readVarInt();
            if (present < 0 || present > sectionCount) {
                throw new Rejected("bad section count " + present);
            }
            List<CapturedSection> out = new ArrayList<>(present);
            int last = -1;
            for (int n = 0; n < present; n++) {
                int index = buf.readVarInt();
                if (index <= last || index >= sectionCount) {
                    throw new Rejected("bad section index " + index);
                }
                last = index;
                int flags = buf.readUnsignedByte();
                int blockMode = flags >> BLOCK_LIGHT_SHIFT & 3;
                int skyMode = flags >> SKY_LIGHT_SHIFT & 3;
                if ((flags & ~0x1F) != 0 || blockMode == LIGHT_COLUMN || skyMode == LIGHT_NONE) {
                    throw new Rejected("bad section flags " + flags);
                }
                BlockState[] palette = null;
                short[] indices = null;
                if ((flags & STATES) != 0) {
                    int[] ids = readPaletteIds(buf, 4096);
                    palette = new BlockState[ids.length];
                    boolean anyBlock = false;
                    for (int i = 0; i < ids.length; i++) {
                        BlockState state = Block.BLOCK_STATE_REGISTRY.byId(ids[i]);
                        if (state == null) {
                            throw new Rejected("unknown block state id " + ids[i]);
                        }
                        palette[i] = state;
                        anyBlock |= !state.isAir();
                    }
                    indices = readIndices(buf, 4096, palette.length);
                    if (!anyBlock) {
                        // Air only: stored as an all-air section, as the import keeps one
                        palette = null;
                        indices = null;
                    }
                }
                int[] biomeIds = readPaletteIds(buf, 64);
                Object[] biomePalette = new Object[biomeIds.length];
                for (int i = 0; i < biomeIds.length; i++) {
                    Holder<Biome> biome = expect.biomes().byId(biomeIds[i]);
                    if (biome == null) {
                        throw new Rejected("unknown biome id " + biomeIds[i]);
                    }
                    biomePalette[i] = biome;
                }
                short[] biomeIndices = readIndices(buf, 64, biomePalette.length);
                Object[] biomes = new Object[64];
                for (int i = 0; i < 64; i++) {
                    biomes[i] = biomePalette[biomeIndices[i]];
                }
                DataLayer block = switch (blockMode) {
                    case LIGHT_UNIFORM -> new DataLayer(nibble(buf));
                    case LIGHT_FULL -> new DataLayer(bytes(buf, 2048));
                    default -> null;
                };
                DataLayer sky = null;
                byte[] skyColumn = null;
                int skyUniform = 0;
                switch (skyMode) {
                    case LIGHT_UNIFORM -> skyUniform = nibble(buf);
                    case LIGHT_COLUMN -> {
                        skyColumn = bytes(buf, 256);
                        for (byte b : skyColumn) {
                            if ((b & 0xF0) != 0) {
                                throw new Rejected("bad sky light value");
                            }
                        }
                    }
                    default -> sky = new DataLayer(bytes(buf, 2048));
                }
                out.add(new CapturedSection(generation, chunkX, minSection + index, chunkZ, null, palette, indices, biomes, block, sky, skyColumn, skyUniform, false));
            }
            if (buf.isReadable()) {
                throw new Rejected(buf.readableBytes() + " bytes left over");
            }
            return out;
        } catch (RuntimeException e) {
            // Ran out of data, or a var int too long: the column is damaged
            throw new Rejected("damaged column: " + e);
        } finally {
            buf.release();
        }
    }

    private static int nibble(FriendlyByteBuf buf) throws Rejected {
        int v = buf.readUnsignedByte();
        if (v > 15) {
            throw new Rejected("bad light value " + v);
        }
        return v;
    }

    private static byte[] bytes(FriendlyByteBuf buf, int n) throws Rejected {
        if (buf.readableBytes() < n) {
            throw new Rejected("column ends early");
        }
        byte[] out = new byte[n];
        buf.readBytes(out);
        return out;
    }

    private static int[] readPaletteIds(FriendlyByteBuf buf, int count) throws Rejected {
        int size = buf.readVarInt();
        if (size < 1 || size > count) {
            throw new Rejected("bad palette size " + size);
        }
        // At least a byte an id must still be there before the array is made
        if (buf.readableBytes() < size) {
            throw new Rejected("column ends early");
        }
        int[] ids = new int[size];
        for (int i = 0; i < size; i++) {
            ids[i] = buf.readVarInt();
        }
        return ids;
    }

    private static short[] readIndices(FriendlyByteBuf buf, int count, int paletteSize) throws Rejected {
        short[] out = new short[count];
        if (paletteSize == 1) {
            return out;
        }
        int bits = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        int perLong = 64 / bits;
        int longs = (count + perLong - 1) / perLong;
        if (buf.readableBytes() < longs * 8L) {
            throw new Rejected("column ends early");
        }
        long mask = (1L << bits) - 1;
        int i = 0;
        for (int l = 0; l < longs; l++) {
            long word = buf.readLong();
            for (int k = 0; k < perLong && i < count; k++, i++) {
                int v = (int) (word >>> (k * bits) & mask);
                if (v >= paletteSize) {
                    throw new Rejected("palette index " + v + " past its " + paletteSize + " entries");
                }
                out[i] = (short) v;
            }
        }
        return out;
    }

    /** Inflates at most {@code max} bytes; more, or a stream that doesn't end, turns the column down. */
    private static byte[] inflate(byte[] data, int max) throws Rejected {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            byte[] out = new byte[Math.min(max, Math.max(4096, data.length * 8))];
            int n = 0;
            while (!inflater.finished()) {
                if (n == out.length) {
                    if (out.length >= max) {
                        throw new Rejected("column inflates past " + max + " bytes");
                    }
                    out = java.util.Arrays.copyOf(out, (int) Math.min(max, out.length * 2L));
                }
                int got = inflater.inflate(out, n, out.length - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new Rejected("column data ends early");
                }
                n += got;
            }
            if (inflater.getRemaining() > 0) {
                throw new Rejected("bytes after the column's data");
            }
            return java.util.Arrays.copyOf(out, n);
        } catch (DataFormatException e) {
            throw new Rejected("column data damaged: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }
}
