package com.rinkynooble.shaderscompanion.client;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client settings, saved in config/shaderscompanion-client.toml. */
public final class Config {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue FACE_SIZE;
    public static final ForgeConfigSpec.IntValue FACES_PER_FRAME;
    public static final ForgeConfigSpec.IntValue MAX_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue ENTITIES;
    public static final ForgeConfigSpec.IntValue ENTITY_DISTANCE;
    public static final ForgeConfigSpec.IntValue MAX_ENTITIES;
    public static final ForgeConfigSpec.BooleanValue BLOCK_ENTITIES;
    public static final ForgeConfigSpec.IntValue BLOCK_ENTITY_DISTANCE;
    public static final ForgeConfigSpec.IntValue MAX_BLOCK_ENTITIES;
    public static final ForgeConfigSpec.BooleanValue DISTANT_HORIZONS;
    public static final ForgeConfigSpec.IntValue LOD_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue MIRROR;
    public static final ForgeConfigSpec.IntValue MIRROR_SCALE;
    public static final ForgeConfigSpec.BooleanValue MIRROR_SHADOWS;
    public static final ForgeConfigSpec.BooleanValue INSTALL_BUNDLED_PACK;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ENABLED = builder
                .comment("Draw the extra views (the cube around the camera and the mirror view) while a shader pack reads them. Off stops all of the mod's drawing; /shaderscompanion on and off change this in game.")
                .define("enabled", true);
        FACE_SIZE = builder
                .comment("Pixels per side of each of the six cube faces. Higher is sharper and costs more.")
                .defineInRange("faceSize", 512, 64, 1024);
        FACES_PER_FRAME = builder
                .comment("Cube faces redrawn each frame. 6 refreshes the whole view every frame; 2 every third frame.")
                .defineInRange("facesPerFrame", 2, 1, 6);
        MAX_DISTANCE = builder
                .comment("Furthest terrain drawn, in blocks. 0 uses the render distance.")
                .defineInRange("maxDistance", 0, 0, 4096);
        ENTITIES = builder
                .comment("Draw mobs, players and other entities into the view.")
                .define("entities", true);
        ENTITY_DISTANCE = builder
                .comment("Furthest entity drawn, in blocks.")
                .defineInRange("entityDistance", 48, 4, 256);
        MAX_ENTITIES = builder
                .comment("Most entities drawn per frame, nearest first.")
                .defineInRange("maxEntities", 48, 0, 1024);
        BLOCK_ENTITIES = builder
                .comment("Draw block entities (chests, signs, banners, modded machines) into the view.")
                .define("blockEntities", true);
        BLOCK_ENTITY_DISTANCE = builder
                .comment("Furthest block entity drawn, in blocks.")
                .defineInRange("blockEntityDistance", 24, 4, 128);
        MAX_BLOCK_ENTITIES = builder
                .comment("Most block entities drawn per frame, nearest first.")
                .defineInRange("maxBlockEntities", 32, 0, 1024);
        DISTANT_HORIZONS = builder
                .comment("Draw Distant Horizons' far terrain (LODs) beyond the render distance, when Distant Horizons is installed.")
                .define("distantHorizons", true);
        LOD_DISTANCE = builder
                .comment("Furthest Distant Horizons terrain drawn, in blocks. 0 uses Distant Horizons' own range.")
                .defineInRange("lodDistance", 0, 0, 65536);
        MIRROR = builder
                .comment("Render the mirror view: the world mirrored in the largest water surface or glass wall on screen, for sharp reflections that show your own player.")
                .define("mirror", true);
        MIRROR_SCALE = builder
                .comment("Resolution of the mirror view, in percent of the screen's. Lower is faster and blurrier.")
                .defineInRange("mirrorScale", 100, 25, 100);
        MIRROR_SHADOWS = builder
                .comment("Keep the shadows of what the mirror view shows. Oculus draws only the shadows that can fall into your own view, so without this, things behind you lose their shadows in reflections, and the shadows come and go as you move. Off draws fewer shadows while a mirror is on screen.")
                .define("mirrorShadows", true);
        INSTALL_BUNDLED_PACK = builder
                .comment("Install the shader pack this mod comes with (ABZA Shader) as shaderpacks/ABZA Shader.zip at startup, and update it with the mod. A file you put there yourself is never replaced.")
                .define("installBundledPack", true);
        SPEC = builder.build();
    }

    private Config() {
    }
}
