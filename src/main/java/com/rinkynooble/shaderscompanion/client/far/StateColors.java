package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.client.model.data.ModelData;

/**
 * The far terrain's colour table (research/lod/M2-PLAN.md, "Colours"), worked out on the render thread (model and
 * atlas access) for the far store's state ids, lazily within {@link #BUDGET_NANOS} a frame, and again after resource
 * reloads ({@link #modelsChanged}) and for a new store (another world).
 * <p>
 * Per state id and face (Direction ordinal), in {@link #colours()} at {@code id * 6 + face}: the average colour of the
 * state's baked model quads on that face (its culled quads for that side plus its unculled quads facing that way; each
 * quad's sprite pixels in its UV rectangle, from the sprite's original image, weighted by alpha and by the quad's area,
 * averaged in linear light and encoded back as the textures are) as RGBA8 with red in the low byte and alpha the share of the face covered (leaves about 0.8, a fence post less). A
 * face with no quads takes the average of the whole model; a block with none (drawn by a block entity renderer, or a
 * fluid) its particle sprite, which for water and lava is the still texture. A face is tinted only when all its quads
 * are (a grass block's top); a face with tinted and untinted quads (its side, a base and a grey overlay) takes the
 * untinted ones, the base, alone.
 * <p>
 * Per state id in {@link #info()}: bits 0 to 11 the tint kind of each face (2 bits a face: {@link #TINT_NONE},
 * {@link #TINT_GRASS}, {@link #TINT_FOLIAGE}, {@link #TINT_WATER}; a tint that doesn't depend on the biome, such as
 * spruce leaves or a lily pad, is already multiplied into the colour), bits 12 to 15 the state's own light emission (0
 * to 15; M3, for the far data's alpha), and in bits 16 to 31 the shader pack's {@code block.properties} id plus one (0:
 * none; Oculus's {@code WorldRenderingSettings.getBlockStateIds()}, read again when the pack reloads). The tint kind
 * comes from asking the state's tint provider with a stand-in world that answers each biome colour resolver with a
 * marker. Until M3 bits 12 and 13 held a resolved flag and an "other tint" flag that nothing read.
 * <p>
 * Per biome id in {@link #biomeColours()} at {@code id * 4}: the grass colour ({@code Biome.getGrassColor} at 0, 0),
 * the foliage colour and the water colour, RGBA8, and 1 at {@code id * 4 + 3} once worked out.
 * <p>
 * The arrays are replaced when they grow and written in place otherwise; any thread may read them, an entry being
 * final once {@link #resolvedStates()} (or the biome count) has passed it. {@link #version()} grows with every change,
 * for M2b's uploads.
 */
public final class StateColors {
    /** Most render-thread time a frame spent working out colours, while there are new ids. */
    static final long BUDGET_NANOS = 300_000L;
    public static final int TINT_NONE = 0;
    public static final int TINT_GRASS = 1;
    public static final int TINT_FOLIAGE = 2;
    public static final int TINT_WATER = 3;
    /** The light emission's place in {@link #info()}: bits 12 to 15. */
    public static final int EMISSION_SHIFT = 12;
    private static final int GRASS_MARK = 0x01FE01;
    private static final int FOLIAGE_MARK = 0x02FE02;
    private static final int WATER_MARK = 0x03FE03;
    private static final int OTHER_RESOLVER = 0xFFFFFF;
    private static final Direction[] DIRECTIONS = Direction.values();

    private static volatile int[] colours = new int[0];
    private static volatile int[] info = new int[0];
    private static volatile int[] biomeColours = new int[0];
    private static volatile int resolvedStates;
    private static volatile int resolvedBiomes;
    private static volatile long version;
    private static volatile boolean modelsChanged;

    // Render thread
    /** The id of still water (the far mesher's state for every water voxel), -1 until its colour is worked out. */
    private static int waterState = -1;
    private static StateIds ids;
    private static BiomeIds biomes;
    private static ClientLevel level;
    private static Object2IntMap<BlockState> propertyIds;
    private static boolean propertiesRead;
    /** Per sprite rectangle: premultiplied red, green, blue and the mean alpha (0 to 1, red etc. 0 to 255). */
    private static final Map<Region, float[]> regions = new HashMap<>();
    private static final RandomSource random = RandomSource.create();
    private static boolean loggedFailure;

    // Statistics (render thread)
    private static long stateNanos;
    private static int statesTimed;
    private static long stateMaxNanos;
    private static BlockState slowestState;
    private static long frameNanos;
    private static int framesBusy;
    private static long frameMaxNanos;
    private static long fillStart;
    private static long lastFillNanos = -1;

    private record Region(TextureAtlasSprite sprite, int x0, int y0, int x1, int y1) {
    }

    private StateColors() {
    }

    public static int[] colours() {
        return colours;
    }

    public static int[] info() {
        return info;
    }

    public static int[] biomeColours() {
        return biomeColours;
    }

    /** The id of still water, the far mesher's state for every water voxel; -1 while unknown (render thread). */
    static int waterState() {
        return waterState;
    }

    /** State ids below this have their colours (any thread). */
    public static int resolvedStates() {
        return resolvedStates;
    }

    public static long version() {
        return version;
    }

    /** Models were baked again (a resource reload): every colour is worked out again. */
    public static void modelsChanged() {
        modelsChanged = true;
    }

    /** Works out new ids within the frame's budget (render thread; from the frame hook while the far terrain meshes). */
    static void frame(StateIds states, BiomeIds biomeIds, ClientLevel clientLevel) {
        if (states != ids || biomeIds != biomes || clientLevel != level || modelsChanged) {
            modelsChanged = false;
            ids = states;
            biomes = biomeIds;
            level = clientLevel;
            regions.clear();
            resolvedStates = 0;
            resolvedBiomes = 0;
            fillStart = System.nanoTime();
            waterState = -1;
            version++;
        }
        Object2IntMap<BlockState> properties = WorldRenderingSettings.INSTANCE.getBlockStateIds();
        if (properties != propertyIds || !propertiesRead) {
            propertyIds = properties;
            propertiesRead = true;
            int[] inf = info;
            for (int id = 0; id < resolvedStates; id++) {
                inf[id] = inf[id] & 0xFFFF | propertyId(ids.state(id)) << 16;
            }
            version++;
        }
        int size = states.size();
        String[] names = biomeIds.published();
        if (resolvedStates >= size && resolvedBiomes >= names.length) {
            return;
        }
        long start = System.nanoTime();
        if (colours.length < size * 6) {
            int capacity = Math.max(size, info.length * 2);
            colours = Arrays.copyOf(colours, capacity * 6);
            info = Arrays.copyOf(info, capacity);
        }
        if (biomeColours.length < names.length * 4) {
            biomeColours = Arrays.copyOf(biomeColours, Math.max(names.length, biomeColours.length / 2) * 4);
        }
        Minecraft mc = Minecraft.getInstance();
        int done = resolvedStates;
        do {
            if (done >= size) {
                break;
            }
            long t0 = System.nanoTime();
            try {
                resolve(mc, done);
            } catch (RuntimeException e) {
                // A model or tint provider that needs more than is at hand: grey, untinted
                if (!loggedFailure) {
                    loggedFailure = true;
                    ShadersCompanion.LOGGER.warn("Shaders Companion far terrain: no colour for {} ({}); grey instead (logged once)", ids.state(done), e.toString());
                }
                Arrays.fill(colours, done * 6, done * 6 + 6, 0xFF808080);
                info[done] = emission(ids.state(done)) << EMISSION_SHIFT | propertyId(ids.state(done)) << 16;
            }
            if (ids.state(done) == Blocks.WATER.defaultBlockState()) {
                waterState = done;
            }
            long nanos = System.nanoTime() - t0;
            stateNanos += nanos;
            statesTimed++;
            if (nanos > stateMaxNanos) {
                stateMaxNanos = nanos;
                slowestState = ids.state(done);
            }
            done++;
            resolvedStates = done;
        } while (System.nanoTime() - start < BUDGET_NANOS);
        int biomesDone = resolvedBiomes;
        while (biomesDone < names.length && System.nanoTime() - start < BUDGET_NANOS) {
            resolveBiome(biomesDone, names[biomesDone]);
            biomesDone++;
            resolvedBiomes = biomesDone;
        }
        version++;
        long nanos = System.nanoTime() - start;
        frameNanos += nanos;
        framesBusy++;
        frameMaxNanos = Math.max(frameMaxNanos, nanos);
        if (resolvedStates >= size && resolvedBiomes >= names.length && fillStart != 0) {
            lastFillNanos = System.nanoTime() - fillStart;
            fillStart = 0;
            ShadersCompanion.LOGGER.info("Shaders Companion far terrain: colours of {} block states and {} biomes in {} ms over {} frames ({} us a state, at most {} us for {}; "
                            + "{} us a frame, at most {} us)", size, names.length, lastFillNanos / 1_000_000, framesBusy, String.format("%.1f", stateNanos / 1.0e3 / Math.max(1, statesTimed)),
                    stateMaxNanos / 1000, slowestState, String.format("%.0f", frameNanos / 1.0e3 / Math.max(1, framesBusy)), frameMaxNanos / 1000);
        }
    }

    /** The status line's part. */
    static String status() {
        StateIds s = ids;
        return String.format("colours of %d of %d block states, %.0f us a state (at most %.0f), %.0f us a frame while working (at most %.0f)", resolvedStates,
                s == null ? 0 : s.size(), statesTimed == 0 ? 0.0 : stateNanos / 1.0e3 / statesTimed, stateMaxNanos / 1.0e3,
                framesBusy == 0 ? 0.0 : frameNanos / 1.0e3 / framesBusy, frameMaxNanos / 1.0e3);
    }

    /** Dev checks: states timed, their nanos, the most for one, frames that worked, their nanos, the most for one, the last fill's nanos. */
    static long[] numbers() {
        return new long[]{statesTimed, stateNanos, stateMaxNanos, framesBusy, frameNanos, frameMaxNanos, lastFillNanos};
    }

    /**
     * A state's up face colour with its biome's tint, 0xRRGGBB, or -1 when it isn't worked out yet (any thread; the
     * mesh map).
     */
    static int upColour(int state, int biome) {
        if (state >= resolvedStates) {
            return -1;
        }
        int[] c = colours;
        int[] inf = info;
        int[] b = biomeColours;
        int rgba = c[state * 6 + 1];
        int kind = inf[state] >> 2 & 3;
        int r = rgba & 0xFF;
        int g = rgba >> 8 & 0xFF;
        int bl = rgba >> 16 & 0xFF;
        if (kind != TINT_NONE) {
            if (biome * 4 + 3 >= b.length || b[biome * 4 + 3] == 0) {
                return -1;
            }
            int tint = b[biome * 4 + kind - 1];
            r = r * (tint & 0xFF) / 255;
            g = g * (tint >> 8 & 0xFF) / 255;
            bl = bl * (tint >> 16 & 0xFF) / 255;
        }
        return r << 16 | g << 8 | bl;
    }

    private static int propertyId(BlockState state) {
        Object2IntMap<BlockState> map = propertyIds;
        if (state == null || map == null) {
            return 0;
        }
        int id = map.getInt(state);
        return id < 0 ? 0 : (id + 1) & 0xFFFF;
    }

    private static void resolve(Minecraft mc, int id) {
        BlockState state = ids.state(id);
        int base = id * 6;
        if (state == null || state.isAir()) {
            Arrays.fill(colours, base, base + 6, 0);
            info[id] = 0;
            return;
        }
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        @SuppressWarnings("unchecked")
        List<BakedQuad>[] byFace = new List[6];
        List<BakedQuad> all = new ArrayList<>();
        for (Direction d : DIRECTIONS) {
            random.setSeed(42L);
            byFace[d.ordinal()] = new ArrayList<>(model.getQuads(state, d, random, ModelData.EMPTY, null));
            all.addAll(byFace[d.ordinal()]);
        }
        random.setSeed(42L);
        for (BakedQuad quad : model.getQuads(state, null, random, ModelData.EMPTY, null)) {
            byFace[quad.getDirection().ordinal()].add(quad);
            all.add(quad);
        }
        int kinds = 0;
        if (all.isEmpty() || state.getBlock() instanceof LiquidBlock) {
            // Drawn some other way (a fluid, a block entity renderer): the particle sprite, tinted as its provider says
            TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);
            float[] s = region(sprite, 0, 0, sprite.contents().width(), sprite.contents().height());
            int tint = probe(mc, state, 0);
            int kind = kindOf(tint);
            int rgba = rgba(s[0], s[1], s[2], s[3], s[3]);
            if (kind < 0) {
                rgba = multiply(rgba, tint);
                kind = TINT_NONE;
            }
            for (int f = 0; f < 6; f++) {
                colours[base + f] = rgba;
                kinds |= kind << (2 * f);
            }
        } else {
            for (int f = 0; f < 6; f++) {
                List<BakedQuad> quads = byFace[f].isEmpty() ? all : byFace[f];
                // Untinted and tinted: premultiplied colour and coverage, each weighted by area
                double[] plain = new double[5];
                double[] tinted = new double[5];
                int tintIndex = -1;
                for (BakedQuad quad : quads) {
                    double area = area(quad);
                    if (area <= 0) {
                        continue;
                    }
                    float[] s = quadRegion(quad);
                    double[] sum = quad.isTinted() ? tinted : plain;
                    if (quad.isTinted() && tintIndex < 0) {
                        tintIndex = quad.getTintIndex();
                    }
                    sum[0] += s[0] * area;
                    sum[1] += s[1] * area;
                    sum[2] += s[2] * area;
                    sum[3] += s[3] * area;
                    sum[4] += area;
                }
                double[] use = plain[4] > 0 ? plain : tinted;
                int kind = TINT_NONE;
                double coverage = byFace[f].isEmpty() ? use[3] / Math.max(1e-9, use[4]) : use[3];
                int rgba = use[3] <= 0 ? 0 : rgba(use[0] / use[4], use[1] / use[4], use[2] / use[4], use[3] / use[4], coverage);
                if (use == tinted && tinted[4] > 0) {
                    int tint = probe(mc, state, tintIndex);
                    kind = kindOf(tint);
                    if (kind < 0) {
                        rgba = multiply(rgba, tint);
                        kind = TINT_NONE;
                    }
                }
                colours[base + f] = rgba;
                kinds |= kind << (2 * f);
            }
        }
        info[id] = kinds | emission(state) << EMISSION_SHIFT | propertyId(state) << 16;
    }

    /** A state's own light emission, 0 to 15 (Forge's, which modded blocks may override; 0 if it needs more than is at hand). */
    private static int emission(BlockState state) {
        if (state == null) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(15, state.getLightEmission(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * RGBA8 (red in the low byte) from premultiplied linear means (0 to 255, {@link #linear}) and the mean alpha, encoded
     * back as the textures are; alpha byte from the coverage.
     */
    private static int rgba(double r, double g, double b, double alpha, double coverage) {
        if (alpha <= 0) {
            return 0;
        }
        int ri = (int) Math.round(encoded(Math.min(255, r / alpha)));
        int gi = (int) Math.round(encoded(Math.min(255, g / alpha)));
        int bi = (int) Math.round(encoded(Math.min(255, b / alpha)));
        int a = (int) Math.round(Math.max(0, Math.min(1, coverage)) * 255);
        return ri | gi << 8 | bi << 16 | a << 24;
    }

    /**
     * A texture channel (0 to 255) in linear light, as a shader pack decodes it (power 2.2), still 0 to 255: the face's
     * pixels are averaged in linear light, as Minecraft's own mipmaps average them ({@code MipmapGenerator.gammaBlend}),
     * so a flat face is as bright as the chunk terrain's texture looks from afar, where a pixel covers many texels (an
     * average of the encoded values is darker the more a texture varies: 2% on grass, 6 to 12% on dirt, cobblestone,
     * leaves and grass block sides).
     */
    private static double linear(double channel) {
        return encodedAverage ? channel : 255.0 * Math.pow(channel / 255.0, 2.2);
    }

    /** The inverse of {@link #linear}. */
    private static double encoded(double linear) {
        return encodedAverage ? linear : 255.0 * Math.pow(Math.max(0, linear) / 255.0, 1.0 / 2.2);
    }

    private static volatile boolean encodedAverage;

    /** Dev checks: average the encoded values, as before 3.0.0-dev4 (set before any colour is worked out). */
    public static void encodedAverageForChecks(boolean on) {
        encodedAverage = on;
    }

    private static int multiply(int rgba, int rgb) {
        int r = (rgba & 0xFF) * (rgb >> 16 & 0xFF) / 255;
        int g = (rgba >> 8 & 0xFF) * (rgb >> 8 & 0xFF) / 255;
        int b = (rgba >> 16 & 0xFF) * (rgb & 0xFF) / 255;
        return r | g << 8 | b << 16 | rgba & 0xFF000000;
    }

    /** A tint provider's answer as a kind: TINT_NONE (no provider), a biome kind, or -1 (another colour: bake it in). */
    private static int kindOf(int tint) {
        return tint == -1 ? TINT_NONE : tint == GRASS_MARK ? TINT_GRASS : tint == FOLIAGE_MARK ? TINT_FOLIAGE : tint == WATER_MARK ? TINT_WATER : -1;
    }

    private static int probe(Minecraft mc, BlockState state, int tintIndex) {
        try {
            return mc.getBlockColors().getColor(state, PROBE, BlockPos.ZERO, tintIndex);
        } catch (RuntimeException e) {
            try {
                return mc.getBlockColors().getColor(state, null, null, tintIndex);
            } catch (RuntimeException e2) {
                return -1;
            }
        }
    }

    /** A quad's area in blocks squared (its first three corners as a parallelogram). */
    private static double area(BakedQuad quad) {
        int[] v = quad.getVertices();
        int stride = v.length / 4;
        float x0 = Float.intBitsToFloat(v[0]);
        float y0 = Float.intBitsToFloat(v[1]);
        float z0 = Float.intBitsToFloat(v[2]);
        float ax = Float.intBitsToFloat(v[stride]) - x0;
        float ay = Float.intBitsToFloat(v[stride + 1]) - y0;
        float az = Float.intBitsToFloat(v[stride + 2]) - z0;
        float bx = Float.intBitsToFloat(v[3 * stride]) - x0;
        float by = Float.intBitsToFloat(v[3 * stride + 1]) - y0;
        float bz = Float.intBitsToFloat(v[3 * stride + 2]) - z0;
        double cx = ay * bz - az * by;
        double cy = az * bx - ax * bz;
        double cz = ax * by - ay * bx;
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** The sprite pixels a quad's UVs cover. */
    private static float[] quadRegion(BakedQuad quad) {
        TextureAtlasSprite sprite = quad.getSprite();
        int[] v = quad.getVertices();
        int stride = v.length / 4;
        float uMin = Float.MAX_VALUE;
        float uMax = -Float.MAX_VALUE;
        float vMin = Float.MAX_VALUE;
        float vMax = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float u = Float.intBitsToFloat(v[i * stride + 4]);
            float w = Float.intBitsToFloat(v[i * stride + 5]);
            uMin = Math.min(uMin, u);
            uMax = Math.max(uMax, u);
            vMin = Math.min(vMin, w);
            vMax = Math.max(vMax, w);
        }
        int width = sprite.contents().width();
        int height = sprite.contents().height();
        float du = sprite.getU1() - sprite.getU0();
        float dv = sprite.getV1() - sprite.getV0();
        int x0 = 0;
        int x1 = width;
        int y0 = 0;
        int y1 = height;
        if (du != 0 && dv != 0) {
            x0 = clamp((int) Math.floor((uMin - sprite.getU0()) / du * width + 1e-3), 0, width - 1);
            x1 = clamp((int) Math.ceil((uMax - sprite.getU0()) / du * width - 1e-3), x0 + 1, width);
            y0 = clamp((int) Math.floor((vMin - sprite.getV0()) / dv * height + 1e-3), 0, height - 1);
            y1 = clamp((int) Math.ceil((vMax - sprite.getV0()) / dv * height - 1e-3), y0 + 1, height);
        }
        return region(sprite, x0, y0, x1, y1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Premultiplied mean colour in linear light ({@link #linear}) and mean alpha of a rectangle of a sprite's first frame,
     * worked out once per rectangle.
     */
    private static float[] region(TextureAtlasSprite sprite, int x0, int y0, int x1, int y1) {
        Region key = new Region(sprite, x0, y0, x1, y1);
        float[] cached = regions.get(key);
        if (cached != null) {
            return cached;
        }
        double r = 0;
        double g = 0;
        double b = 0;
        double a = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                // ABGR: red in the low byte
                int c = sprite.getPixelRGBA(0, x, y);
                double alpha = (c >>> 24) / 255.0;
                r += linear(c & 0xFF) * alpha;
                g += linear(c >> 8 & 0xFF) * alpha;
                b += linear(c >> 16 & 0xFF) * alpha;
                a += alpha;
                n++;
            }
        }
        float[] out = n == 0 ? new float[4] : new float[]{(float) (r / n), (float) (g / n), (float) (b / n), (float) (a / n)};
        regions.put(key, out);
        return out;
    }

    private static void resolveBiome(int id, String name) {
        int grass = 0x91BD59;
        int foliage = 0x77AB2F;
        int water = 0x3F76E4;
        ClientLevel lvl = level;
        if (!name.isEmpty() && lvl != null) {
            ResourceLocation location = ResourceLocation.tryParse(name);
            Biome biome = location == null ? null : lvl.registryAccess().registryOrThrow(Registries.BIOME).getOptional(location).orElse(null);
            if (biome != null) {
                grass = biome.getGrassColor(0.0, 0.0);
                foliage = biome.getFoliageColor();
                water = biome.getWaterColor();
            }
        }
        int[] b = biomeColours;
        b[id * 4] = rgbaOf(grass);
        b[id * 4 + 1] = rgbaOf(foliage);
        b[id * 4 + 2] = rgbaOf(water);
        b[id * 4 + 3] = 1;
    }

    private static int rgbaOf(int rgb) {
        return (rgb >> 16 & 0xFF) | (rgb & 0xFF00) | (rgb & 0xFF) << 16 | 0xFF000000;
    }

    /** A stand-in world for tint providers: each biome colour resolver answers with its marker. */
    private static final BlockAndTintGetter PROBE = new BlockAndTintGetter() {
        @Override
        public float getShade(Direction direction, boolean shade) {
            return 1.0F;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return resolver == BiomeColors.GRASS_COLOR_RESOLVER ? GRASS_MARK
                    : resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER ? FOLIAGE_MARK
                    : resolver == BiomeColors.WATER_COLOR_RESOLVER ? WATER_MARK : OTHER_RESOLVER;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    };
}
