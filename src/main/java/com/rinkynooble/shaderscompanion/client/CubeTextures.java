package com.rinkynooble.shaderscompanion.client;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * The textures shader packs read, by resource location (see API.md): the cube around the camera and the mirror view. A pack binds them with
 * {@code customTexture.<sampler> = shaderscompanion:<name>} in shaders.properties.
 */
public final class CubeTextures {
    /** Protocol version written to the info texture; packs check it before reading anything else. */
    public static final int PROTOCOL = 2;
    /** Marker in the info texture's first texel, so a pack can tell this mod's texture from the missing texture. */
    public static final float MAGIC = 4242.0F;
    /** Info texture width: one header texel, six face texels, one projection texel. */
    public static final int INFO_WIDTH = 8;
    /** Protocol version of the mirror view's textures, in its own info texture. */
    public static final int MIRROR_PROTOCOL = 2;
    /** Mirror info texture width: header, plane, view origin, four matrix columns, ranges. */
    public static final int MIRROR_INFO_WIDTH = 8;

    public static final ResourceLocation ALBEDO_ID = id("cube_albedo");
    public static final ResourceLocation DATA_ID = id("cube_data");
    public static final ResourceLocation DISTANCE_ID = id("cube_distance");
    public static final ResourceLocation PREVIEW_ID = id("cube_preview");
    public static final ResourceLocation INFO_ID = id("cube_info");
    public static final ResourceLocation MIRROR_ALBEDO_ID = id("mirror_albedo");
    public static final ResourceLocation MIRROR_DATA_ID = id("mirror_data");
    public static final ResourceLocation MIRROR_DISTANCE_ID = id("mirror_distance");
    public static final ResourceLocation MIRROR_INFO_ID = id("mirror_info");
    public static final ResourceLocation MIRROR_BEHIND_ALBEDO_ID = id("mirror_behind_albedo");
    public static final ResourceLocation MIRROR_BEHIND_DATA_ID = id("mirror_behind_data");
    public static final ResourceLocation MIRROR_BEHIND_DISTANCE_ID = id("mirror_behind_distance");

    static final CompanionTexture ALBEDO = new CompanionTexture(CompanionTexture.CUBE, GL11C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, GL11C.GL_LINEAR);
    static final CompanionTexture DATA = new CompanionTexture(CompanionTexture.CUBE, GL11C.GL_RGBA16, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_SHORT, GL11C.GL_NEAREST);
    static final CompanionTexture DISTANCE = new CompanionTexture(CompanionTexture.CUBE, GL30C.GL_R32F, GL11C.GL_RED, GL11C.GL_FLOAT, GL11C.GL_NEAREST);
    static final CompanionTexture PREVIEW = new CompanionTexture(CompanionTexture.UNTRACKED, GL11C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, GL11C.GL_LINEAR);
    static final CompanionTexture INFO = new CompanionTexture(CompanionTexture.CUBE, GL30C.GL_RGBA32F, GL11C.GL_RGBA, GL11C.GL_FLOAT, GL11C.GL_NEAREST);
    static final CompanionTexture MIRROR_ALBEDO = new CompanionTexture(CompanionTexture.MIRROR, GL11C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, GL11C.GL_LINEAR);
    static final CompanionTexture MIRROR_DATA = new CompanionTexture(CompanionTexture.MIRROR, GL11C.GL_RGBA16, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_SHORT, GL11C.GL_NEAREST);
    static final CompanionTexture MIRROR_DISTANCE = new CompanionTexture(CompanionTexture.MIRROR, GL30C.GL_R32F, GL11C.GL_RED, GL11C.GL_FLOAT, GL11C.GL_NEAREST);
    static final CompanionTexture MIRROR_INFO = new CompanionTexture(CompanionTexture.MIRROR, GL30C.GL_RGBA32F, GL11C.GL_RGBA, GL11C.GL_FLOAT, GL11C.GL_NEAREST);
    /** The mirror view's opaque surfaces as they were before water and glass were drawn over them: what lies behind those. */
    static final CompanionTexture MIRROR_BEHIND_ALBEDO = new CompanionTexture(CompanionTexture.MIRROR_BEHIND, GL11C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, GL11C.GL_LINEAR);
    static final CompanionTexture MIRROR_BEHIND_DATA = new CompanionTexture(CompanionTexture.MIRROR_BEHIND, GL11C.GL_RGBA16, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_SHORT, GL11C.GL_NEAREST);
    static final CompanionTexture MIRROR_BEHIND_DISTANCE = new CompanionTexture(CompanionTexture.MIRROR_BEHIND, GL30C.GL_R32F, GL11C.GL_RED, GL11C.GL_FLOAT, GL11C.GL_NEAREST);

    private CubeTextures() {
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(ShadersCompanion.MOD_ID, path);
    }

    static void register() {
        var manager = Minecraft.getInstance().getTextureManager();
        manager.register(ALBEDO_ID, ALBEDO);
        manager.register(DATA_ID, DATA);
        manager.register(DISTANCE_ID, DISTANCE);
        manager.register(PREVIEW_ID, PREVIEW);
        manager.register(INFO_ID, INFO);
        manager.register(MIRROR_ALBEDO_ID, MIRROR_ALBEDO);
        manager.register(MIRROR_DATA_ID, MIRROR_DATA);
        manager.register(MIRROR_DISTANCE_ID, MIRROR_DISTANCE);
        manager.register(MIRROR_INFO_ID, MIRROR_INFO);
        manager.register(MIRROR_BEHIND_ALBEDO_ID, MIRROR_BEHIND_ALBEDO);
        manager.register(MIRROR_BEHIND_DATA_ID, MIRROR_BEHIND_DATA);
        manager.register(MIRROR_BEHIND_DISTANCE_ID, MIRROR_BEHIND_DISTANCE);
        INFO.allocate(INFO_WIDTH, 1);
        MIRROR_INFO.allocate(MIRROR_INFO_WIDTH, 1);
        // Until the mirror view is first drawn, its textures are 1 x 1, so a pack binding them never gets Minecraft's missing texture
        MIRROR_ALBEDO.allocate(1, 1);
        MIRROR_DATA.allocate(1, 1);
        MIRROR_DISTANCE.allocate(1, 1);
        MIRROR_BEHIND_ALBEDO.allocate(1, 1);
        MIRROR_BEHIND_DATA.allocate(1, 1);
        MIRROR_BEHIND_DISTANCE.allocate(1, 1);
        ShadersCompanion.LOGGER.info("Shaders Companion: cube textures registered (protocol {}), mirror view textures (protocol {})", PROTOCOL, MIRROR_PROTOCOL);
    }
}
