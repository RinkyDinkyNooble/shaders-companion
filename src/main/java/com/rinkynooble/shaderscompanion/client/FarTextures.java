package com.rinkynooble.shaderscompanion.client;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.nio.FloatBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/**
 * The far terrain's textures for shader packs (API.md, "Far terrain"; far protocol 1): the two layers the far
 * terrain is drawn into ({@code client/far/FarRenderer}), the opaque surfaces and the nearest translucent surface in
 * front of them, and the info texture. A pack binds {@code far_info} (the layers jittered for its TAA) or
 * {@code far_info_still} (not jittered), and that binding is the demand: the mod draws far terrain for the pack only
 * while one of the two names was bound within the last 30 frames. The layers are 1 x 1 until first drawn and again
 * once far terrain goes off, so a pack never gets Minecraft's missing texture while the mod is installed.
 */
public final class FarTextures {
    /** Far protocol version, in the info texture's first texel. */
    public static final int PROTOCOL = 1;
    public static final int INFO_WIDTH = 4;
    /** What the pack asked for this frame ({@link #demand}). */
    public static final int NOT_BOUND = 0;
    public static final int BOUND_JITTERED = 1;
    public static final int BOUND_STILL = 2;

    public static final ResourceLocation ALBEDO_ID = id("far_albedo");
    public static final ResourceLocation DATA_ID = id("far_data");
    public static final ResourceLocation DEPTH_ID = id("far_depth");
    public static final ResourceLocation TRANSLUCENT_ALBEDO_ID = id("far_translucent_albedo");
    public static final ResourceLocation TRANSLUCENT_DATA_ID = id("far_translucent_data");
    public static final ResourceLocation INFO_ID = id("far_info");
    public static final ResourceLocation INFO_STILL_ID = id("far_info_still");

    static final CompanionTexture ALBEDO = layer(GL11C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE);
    static final CompanionTexture DATA = layer(GL11C.GL_RGBA16, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_SHORT);
    /** r: the opaque surface's depth, g: the translucent one's (1 where there is none), in the protocol's convention. */
    static final CompanionTexture DEPTH = layer(GL30C.GL_RG32F, GL30C.GL_RG, GL11C.GL_FLOAT);
    static final CompanionTexture TRANSLUCENT_ALBEDO = layer(GL11C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE);
    static final CompanionTexture TRANSLUCENT_DATA = layer(GL11C.GL_RGBA16, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_SHORT);
    static final CompanionTexture INFO = new CompanionTexture(CompanionTexture.FAR_INFO, GL30C.GL_RGBA32F, GL11C.GL_RGBA, GL11C.GL_FLOAT, GL11C.GL_NEAREST);
    private static final CompanionTexture[] LAYERS = {ALBEDO, DATA, DEPTH, TRANSLUCENT_ALBEDO, TRANSLUCENT_DATA};

    /** Whether the pack binds the info texture under either name; nothing counts as bound before the pack asks again. */
    private static final TextureDemand jittered = new TextureDemand(CompanionTexture.FAR_INFO, true);
    private static final TextureDemand still = new TextureDemand(CompanionTexture.FAR_INFO_STILL, true);
    private static int demand;
    private static FloatBuffer infoBuffer;
    /** What the info texture says now: -1 never written, 0 not drawn, 1 drawn. */
    private static int infoState = -1;
    private static int layerWidth;
    private static int layerHeight;

    private FarTextures() {
    }

    private static CompanionTexture layer(int internalFormat, int format, int type) {
        return new CompanionTexture(CompanionTexture.FAR, internalFormat, format, type, GL11C.GL_NEAREST);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(ShadersCompanion.MOD_ID, path);
    }

    /** Registers the textures (from {@link CubeTextures#register}, render thread). */
    static void register() {
        var manager = Minecraft.getInstance().getTextureManager();
        manager.register(ALBEDO_ID, ALBEDO);
        manager.register(DATA_ID, DATA);
        manager.register(DEPTH_ID, DEPTH);
        manager.register(TRANSLUCENT_ALBEDO_ID, TRANSLUCENT_ALBEDO);
        manager.register(TRANSLUCENT_DATA_ID, TRANSLUCENT_DATA);
        manager.register(INFO_ID, INFO);
        // far_info_still: the same texture; a pack that binds it asks for layers without the TAA jitter
        manager.register(INFO_STILL_ID, new CompanionTexture.Alias(INFO, CompanionTexture.FAR_INFO_STILL));
        INFO.allocate(INFO_WIDTH, 1);
        for (CompanionTexture texture : LAYERS) {
            texture.allocate(1, 1);
        }
    }

    /**
     * Counts this frame's demand (once a frame, from the far store's frame hook, before anything is drawn; no GL): which
     * name the pack binds the info texture under, {@link #BOUND_JITTERED} winning when it binds both.
     */
    public static void frame() {
        boolean j = jittered.update();
        boolean s = still.update();
        demand = j ? BOUND_JITTERED : s ? BOUND_STILL : NOT_BOUND;
    }

    /** This frame's demand: {@link #NOT_BOUND}, {@link #BOUND_JITTERED} or {@link #BOUND_STILL}. */
    public static int demand() {
        return demand;
    }

    /** Gives the layers this size (render thread; keeps their GL names). */
    public static void ensureSize(int width, int height) {
        if (width == layerWidth && height == layerHeight) {
            return;
        }
        for (CompanionTexture texture : LAYERS) {
            texture.allocate(width, height);
        }
        layerWidth = width;
        layerHeight = height;
    }

    /** The layers back to 1 x 1 (far terrain went off: frees their video memory). */
    public static void release() {
        if (layerWidth == 0 && layerHeight == 0) {
            return;
        }
        for (CompanionTexture texture : LAYERS) {
            texture.allocate(1, 1);
        }
        layerWidth = 0;
        layerHeight = 0;
    }

    public static int albedo() {
        return ALBEDO.glId();
    }

    public static int data() {
        return DATA.glId();
    }

    public static int depth() {
        return DEPTH.glId();
    }

    public static int translucentAlbedo() {
        return TRANSLUCENT_ALBEDO.glId();
    }

    public static int translucentData() {
        return TRANSLUCENT_DATA.glId();
    }

    /**
     * Writes the info texture for a frame with far terrain in the layers (render thread; the texture bound before is put
     * back): texel 0 {@code (4242, protocol, 1, frame counter mod 4096)}, texel 1 {@code (N, F, far range, chunk
     * terrain radius)}, texel 2 {@code (projection [0].x, [1].y, jitter x, jitter y)}, texel 3 the layers' size.
     */
    public static void writeDrawn(int frameCounter, float near, float far, float farRange, float chunkRadius, float projectionX, float projectionY,
                                  float jitterX, float jitterY, int width, int height) {
        write(new float[]{CubeTextures.MAGIC, PROTOCOL, 1.0F, frameCounter % 4096, near, far, farRange, chunkRadius, projectionX, projectionY, jitterX, jitterY,
                width, height, 0.0F, 0.0F});
        infoState = 1;
    }

    /** Whether {@link #writeNotDrawn} has something to write (no GL). */
    public static boolean notDrawnPending() {
        return infoState == 1 || demand != NOT_BOUND && infoState != 0;
    }

    /**
     * Says in the info texture that this frame's layers hold no far terrain, when it says they do, or when the pack
     * binds it and it was never written; so a pack that never binds it costs no GL call, and a pack that binds it again
     * later never reads an old frame as drawn. True when it wrote.
     */
    public static boolean writeNotDrawn() {
        if (!notDrawnPending()) {
            return false;
        }
        write(new float[]{CubeTextures.MAGIC, PROTOCOL, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F});
        infoState = 0;
        return true;
    }

    private static void write(float[] texels) {
        if (infoBuffer == null) {
            infoBuffer = MemoryUtil.memAllocFloat(INFO_WIDTH * 4);
        }
        infoBuffer.clear();
        infoBuffer.put(texels).flip();
        INFO.writeRow(infoBuffer, INFO_WIDTH);
    }

    /** Video memory of the layers, in bytes (32 a pixel). */
    public static long layerBytes() {
        return (long) layerWidth * layerHeight * (4 + 8 + 8 + 4 + 8);
    }

    /** Dev checks: the info texture's 16 floats as GL holds them (a read back: a stall, for a check only). */
    public static float[] infoForChecks() {
        float[] out = new float[INFO_WIDTH * 4];
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        Gl.bindTexture(INFO.glId());
        int[] pixelBuffers = CompanionTexture.preparePixelTransfer();
        GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, out);
        CompanionTexture.restorePixelBuffers(pixelBuffers);
        Gl.bindTextureIfAlive(previous);
        return out;
    }
}
