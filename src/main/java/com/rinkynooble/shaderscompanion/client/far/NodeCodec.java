package com.rinkynooble.shaderscompanion.client.far;

import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A node's blob on disk (M1-PLAN "Disk"), then passed through {@link Deflater} (zlib, so a damaged blob fails its
 * checksum instead of decoding to garbage):
 * <ul>
 * <li>a version byte;</li>
 * <li>the states: a local palette of state ids (varints, in order of first use), then each voxel's palette index
 * bit-packed, low bits first, x fastest, then z, then y; no indices when the palette has one state;</li>
 * <li>the biomes the same way, per 4 x 4 x 4 voxels;</li>
 * <li>the light: one byte when every voxel has the same; else the sky light as 16 KiB of nibble pairs and the block
 * light either as one byte (all the same, the usual case outside caves and builds) or as 16 KiB more.</li>
 * </ul>
 * A node of one state, one light and one biome has no blob: {@link #encode} returns null and the region file keeps the
 * three values in its slot. Index bits are rounded up to 1, 2, 4, 8 or 16 by default, so an index never straddles a
 * byte below 8 bits and repeated runs stay byte-aligned for the deflater (smaller than the exact bit count; measured in
 * research/lod/M1.md). One codec per thread: it keeps its scratch arrays.
 */
final class NodeCodec {
    static final int VERSION = 1;
    /** Deflater level of the store (measured against 1 and 6 in research/lod/M1.md). */
    static final int DEFAULT_LEVEL = 4;
    private static final int LIGHT_UNIFORM = 0;
    private static final int LIGHT_SKY = 1;
    private static final int LIGHT_BOTH = 2;

    private final Deflater deflater;
    private final Inflater inflater = new Inflater();
    /** Index bits rounded up to a power of two (the default), or exactly ceil(log2(palette size)). */
    boolean pow2Bits = true;

    private byte[] raw = new byte[1 << 17];
    private int rawLength;
    private int readAt;
    private byte[] out = new byte[1 << 15];
    /** Per id: its index in the palette being built, plus one (0: not in it); cleared again after each use. */
    private int[] paletteIndex = new int[1024];
    private final int[] palette = new int[Node.VOXELS];
    private final int[] indices = new int[Node.VOXELS];

    /** The values of the last node {@link #encode} found uniform (it returned null). */
    int uniformState;
    byte uniformLight;
    short uniformBiome;

    NodeCodec(int level) {
        deflater = new Deflater(level);
    }

    void setLevel(int level) {
        deflater.setLevel(level);
    }

    /** The bytes the last blob {@link #encode} made held before the deflater. */
    int lastRawLength() {
        return rawLength;
    }

    /** The node's deflated blob, or null when it is one state, one light and one biome (then see {@link #uniformState}). */
    byte[] encode(Node node) {
        rawLength = 0;
        put(VERSION);
        // States
        int size;
        if (node.state == null) {
            palette[0] = node.uniformState;
            size = 1;
        } else {
            size = buildPalette(node.state, Node.VOXELS);
        }
        // Light: uniform, or the sky light varies and the block light is one value, or both vary
        int lightMode;
        int firstLight = node.light(0);
        if (node.light == null) {
            lightMode = LIGHT_UNIFORM;
        } else {
            byte[] l = node.light;
            boolean sameSky = true;
            boolean sameBlock = true;
            for (int i = 1; i < Node.VOXELS && (sameSky || sameBlock); i++) {
                int v = l[i] ^ firstLight;
                sameSky &= (v & 0xF0) == 0;
                sameBlock &= (v & 0x0F) == 0;
            }
            lightMode = sameSky && sameBlock ? LIGHT_UNIFORM : sameBlock ? LIGHT_SKY : LIGHT_BOTH;
        }
        // Biomes (their palette after the states', which are packed first)
        if (size == 1 && lightMode == LIGHT_UNIFORM && uniformBiomes(node)) {
            uniformState = palette[0];
            uniformLight = (byte) firstLight;
            uniformBiome = (short) node.biome(0);
            return null;
        }
        putPalette(size);
        if (size > 1) {
            int bits = bits(size);
            put(bits);
            pack(indices, Node.VOXELS, bits);
        }
        if (node.biome == null) {
            palette[0] = node.uniformBiome;
            putPalette(1);
        } else {
            int[] cells = new int[Node.BIOME_CELLS];
            for (int i = 0; i < Node.BIOME_CELLS; i++) {
                cells[i] = node.biome[i];
            }
            int biomes = buildPalette(cells, Node.BIOME_CELLS);
            putPalette(biomes);
            if (biomes > 1) {
                int bits = bits(biomes);
                put(bits);
                pack(indices, Node.BIOME_CELLS, bits);
            }
        }
        put(lightMode);
        if (lightMode == LIGHT_UNIFORM) {
            put(firstLight);
        } else {
            byte[] l = node.light;
            ensure(Node.VOXELS);
            for (int i = 0; i < Node.VOXELS; i += 2) {
                raw[rawLength++] = (byte) ((l[i] >> 4 & 15) | (l[i + 1] & 0xF0));
            }
            if (lightMode == LIGHT_SKY) {
                put(firstLight & 15);
            } else {
                for (int i = 0; i < Node.VOXELS; i += 2) {
                    raw[rawLength++] = (byte) ((l[i] & 15) | (l[i + 1] & 15) << 4);
                }
            }
        }
        return deflate();
    }

    private static boolean uniformBiomes(Node node) {
        if (node.biome == null) {
            return true;
        }
        short first = node.biome[0];
        for (int i = 1; i < Node.BIOME_CELLS; i++) {
            if (node.biome[i] != first) {
                return false;
            }
        }
        return true;
    }

    /** Fills {@link #palette} (first use first) and {@link #indices}; returns the palette's size. */
    private int buildPalette(int[] values, int count) {
        int size = 0;
        int last = Integer.MIN_VALUE;
        int lastIndex = 0;
        for (int i = 0; i < count; i++) {
            int id = values[i];
            if (id != last) {
                if (id >= paletteIndex.length) {
                    paletteIndex = Arrays.copyOf(paletteIndex, Math.max(id + 1, paletteIndex.length * 2));
                }
                int at = paletteIndex[id];
                if (at == 0) {
                    palette[size] = id;
                    at = ++size;
                    paletteIndex[id] = at;
                }
                last = id;
                lastIndex = at - 1;
            }
            indices[i] = lastIndex;
        }
        for (int i = 0; i < size; i++) {
            paletteIndex[palette[i]] = 0;
        }
        return size;
    }

    private int bits(int size) {
        int exact = 32 - Integer.numberOfLeadingZeros(size - 1);
        if (!pow2Bits) {
            return exact;
        }
        return exact <= 1 ? 1 : exact <= 2 ? 2 : exact <= 4 ? 4 : exact <= 8 ? 8 : 16;
    }

    private void putPalette(int size) {
        putVarint(size);
        for (int i = 0; i < size; i++) {
            putVarint(palette[i]);
        }
    }

    private void pack(int[] values, int count, int bits) {
        ensure((count * bits + 7) >> 3);
        long acc = 0;
        int accBits = 0;
        for (int i = 0; i < count; i++) {
            acc |= (long) values[i] << accBits;
            accBits += bits;
            while (accBits >= 8) {
                raw[rawLength++] = (byte) acc;
                acc >>>= 8;
                accBits -= 8;
            }
        }
        if (accBits > 0) {
            raw[rawLength++] = (byte) acc;
        }
    }

    private byte[] deflate() {
        deflater.reset();
        deflater.setInput(raw, 0, rawLength);
        deflater.finish();
        int n = 0;
        while (!deflater.finished()) {
            if (n == out.length) {
                out = Arrays.copyOf(out, out.length * 2);
            }
            n += deflater.deflate(out, n, out.length - n);
        }
        return Arrays.copyOf(out, n);
    }

    /** Decodes a blob into a node made for it (its arrays, uniform values and known count). */
    void decode(byte[] blob, Node node) throws DataFormatException {
        intoState = null;
        intoLight = null;
        intoBiome = null;
        decodeBlob(blob, node);
    }

    /**
     * {@link #decode} into the given arrays instead of new ones (the far mesher's scratch, so a decode makes no
     * garbage): the node's arrays are those arrays afterwards, or null when it is uniform.
     */
    void decodeInto(byte[] blob, Node node, int[] state, byte[] light, short[] biome) throws DataFormatException {
        intoState = state;
        intoLight = light;
        intoBiome = biome;
        try {
            decodeBlob(blob, node);
        } finally {
            intoState = null;
            intoLight = null;
            intoBiome = null;
        }
    }

    private int[] intoState;
    private byte[] intoLight;
    private short[] intoBiome;

    private int[] newStates() {
        return intoState != null ? intoState : new int[Node.VOXELS];
    }

    private byte[] newLight() {
        return intoLight != null ? intoLight : new byte[Node.VOXELS];
    }

    private void decodeBlob(byte[] blob, Node node) throws DataFormatException {
        inflater.reset();
        inflater.setInput(blob, 0, blob.length);
        int n = 0;
        while (!inflater.finished()) {
            if (n == raw.length) {
                raw = Arrays.copyOf(raw, raw.length * 2);
            }
            int got = inflater.inflate(raw, n, raw.length - n);
            if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                throw new DataFormatException("blob ends early");
            }
            n += got;
        }
        rawLength = n;
        readAt = 0;
        try {
            int version = get();
            if (version != VERSION) {
                throw new DataFormatException("blob version " + version);
            }
            int size = getPalette();
            int uniform = palette[0];
            int[] state = null;
            if (size > 1) {
                state = newStates();
                unpack(Node.VOXELS, get(), size, state);
            }
            int biomes = getPalette();
            short[] biome = null;
            if (biomes > 1) {
                unpack(Node.BIOME_CELLS, get(), biomes, indices);
                biome = intoBiome != null ? intoBiome : new short[Node.BIOME_CELLS];
                for (int i = 0; i < Node.BIOME_CELLS; i++) {
                    biome[i] = (short) indices[i];
                }
            } else {
                node.uniformBiome = (short) palette[0];
            }
            node.biome = biome;
            int lightMode = get();
            byte[] light = null;
            if (lightMode == LIGHT_UNIFORM) {
                byte value = (byte) get();
                if (state != null) {
                    light = newLight();
                    Arrays.fill(light, value);
                } else {
                    node.uniformLight = value;
                }
            } else {
                light = newLight();
                check(Node.VOXELS / 2);
                for (int i = 0; i < Node.VOXELS; i += 2) {
                    int b = raw[readAt++];
                    light[i] = (byte) ((b & 15) << 4);
                    light[i + 1] = (byte) (b & 0xF0);
                }
                if (lightMode == LIGHT_SKY) {
                    int block = get() & 15;
                    for (int i = 0; i < Node.VOXELS; i++) {
                        light[i] |= (byte) block;
                    }
                } else {
                    check(Node.VOXELS / 2);
                    for (int i = 0; i < Node.VOXELS; i += 2) {
                        int b = raw[readAt++];
                        light[i] |= (byte) (b & 15);
                        light[i + 1] |= (byte) (b >> 4 & 15);
                    }
                }
                if (state == null) {
                    state = newStates();
                    Arrays.fill(state, uniform);
                }
            }
            if (state == null) {
                node.state = null;
                node.light = null;
                node.uniformState = uniform;
                node.known = uniform != StateIds.UNKNOWN ? Node.VOXELS : 0;
            } else {
                node.state = state;
                node.light = light;
                int known = 0;
                for (int i = 0; i < Node.VOXELS; i++) {
                    if (state[i] != StateIds.UNKNOWN) {
                        known++;
                    }
                }
                node.known = known;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new DataFormatException("blob damaged: " + e.getMessage());
        }
    }

    /** Reads a palette into {@link #palette}; returns its size. */
    private int getPalette() throws DataFormatException {
        int size = getVarint();
        if (size < 1 || size > Node.VOXELS) {
            throw new DataFormatException("palette of " + size);
        }
        for (int i = 0; i < size; i++) {
            palette[i] = getVarint();
        }
        return size;
    }

    /** Unpacks indices and maps them through the palette. */
    private void unpack(int count, int bits, int size, int[] into) throws DataFormatException {
        if (bits < 1 || bits > 16) {
            throw new DataFormatException(bits + " bits an index");
        }
        check((count * bits + 7) >> 3);
        long acc = 0;
        int accBits = 0;
        int mask = (1 << bits) - 1;
        for (int i = 0; i < count; i++) {
            while (accBits < bits) {
                acc |= (long) (raw[readAt++] & 0xFF) << accBits;
                accBits += 8;
            }
            int v = (int) acc & mask;
            acc >>>= bits;
            accBits -= bits;
            if (v >= size) {
                throw new DataFormatException("index " + v + " past a palette of " + size);
            }
            into[i] = palette[v];
        }
    }

    // Raw bytes

    private void ensure(int more) {
        if (rawLength + more > raw.length) {
            raw = Arrays.copyOf(raw, Math.max(rawLength + more, raw.length * 2));
        }
    }

    private void put(int b) {
        ensure(1);
        raw[rawLength++] = (byte) b;
    }

    private void putVarint(int v) {
        ensure(5);
        while ((v & ~0x7F) != 0) {
            raw[rawLength++] = (byte) (v & 0x7F | 0x80);
            v >>>= 7;
        }
        raw[rawLength++] = (byte) v;
    }

    private void check(int bytes) throws DataFormatException {
        if (readAt + bytes > rawLength) {
            throw new DataFormatException("blob ends early");
        }
    }

    private int get() throws DataFormatException {
        check(1);
        return raw[readAt++] & 0xFF;
    }

    private int getVarint() throws DataFormatException {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = get();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
        }
        throw new DataFormatException("varint too long");
    }
}
