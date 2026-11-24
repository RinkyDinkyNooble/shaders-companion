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
    public static final ForgeConfigSpec.BooleanValue ENTITY_LOD;
    public static final ForgeConfigSpec.BooleanValue BLOCK_ENTITIES;
    public static final ForgeConfigSpec.IntValue BLOCK_ENTITY_DISTANCE;
    public static final ForgeConfigSpec.IntValue MAX_BLOCK_ENTITIES;
    public static final ForgeConfigSpec.BooleanValue DISTANT_HORIZONS;
    public static final ForgeConfigSpec.IntValue LOD_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue MIRROR;
    public static final ForgeConfigSpec.IntValue MIRROR_SCALE;
    public static final ForgeConfigSpec.BooleanValue MIRROR_LOD;
    public static final ForgeConfigSpec.BooleanValue MIRROR_SHADOWS;
    public static final ForgeConfigSpec.BooleanValue INSTALL_BUNDLED_PACK;
    public static final ForgeConfigSpec.BooleanValue LATTICE;
    public static final ForgeConfigSpec.ConfigValue<Integer> LATTICE_SIZE;
    public static final ForgeConfigSpec.IntValue LATTICE_BUDGET;
    public static final ForgeConfigSpec.BooleanValue LIGHT_LEAK_FIX;
    public static final ForgeConfigSpec.BooleanValue MATERIALS;
    public static final ForgeConfigSpec.BooleanValue FAR_STORE;
    public static final ForgeConfigSpec.IntValue FAR_STORE_BUDGET;
    public static final ForgeConfigSpec.IntValue FAR_STORE_CACHE_MB;
    public static final ForgeConfigSpec.IntValue FAR_STORE_DISK_MB;
    public static final ForgeConfigSpec.IntValue FAR_RANGE;
    public static final ForgeConfigSpec.IntValue FAR_PIXELS_PER_VOXEL;
    public static final ForgeConfigSpec.BooleanValue FAR_TERRAIN_DEBUG;
    public static final ForgeConfigSpec.IntValue FAR_GPU_MB;

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
        ENTITY_LOD = builder
                .comment("Redraw far mobs and block entities into the views less often (every 2nd frame from 16 blocks away, every 3rd from 32, every 4th from 48), moving them with their last pose in between. Saves processor time in crowded places; mobs and block entities near you are redrawn every frame.")
                .define("entityLod", true);
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
                .comment("Render the mirror view: the world mirrored in the largest water surfaces and glass walls on screen (as many as the shader pack asks for), for sharp reflections that show your own player.")
                .define("mirror", true);
        MIRROR_SCALE = builder
                .comment("Resolution of the mirror view, in percent of the screen's. Lower is faster and blurrier.")
                .defineInRange("mirrorScale", 100, 25, 100);
        MIRROR_LOD = builder
                .comment("Draw a mirror's view at a lower resolution while the mirror covers little of the screen (three quarters below about a third of the screen's centre, half below about a sixth), like Distant Horizons' detail levels.")
                .define("mirrorLod", true);
        MIRROR_SHADOWS = builder
                .comment("Keep the shadows of what the mirror view shows. Oculus draws only the shadows that can fall into your own view, so without this, things behind you lose their shadows in reflections, and the shadows come and go as you move. Off draws fewer shadows while a mirror is on screen.")
                .define("mirrorShadows", true);
        INSTALL_BUNDLED_PACK = builder
                .comment("Install the shader pack this mod comes with (ABZA Shader) as shaderpacks/ABZA Shader.zip at startup, and update it with the mod. A file you put there yourself is never replaced.")
                .define("installBundledPack", true);
        LATTICE = builder
                .comment("Keep the lattice while a shader pack reads it: a copy of the blocks around you (their shapes in quarter blocks, their light and what kind of block they are) that the pack uses for lighting that doesn't depend on what is on screen. Built from the world and updated only where blocks or light change.")
                .define("lattice", true);
        LATTICE_SIZE = builder
                .comment("Width of the lattice around you, in blocks (it is 128 blocks tall). 128 uses 24 MB of video memory, 256 uses 96 MB.")
                .defineInList("latticeSize", 128, java.util.List.of(64, 128, 256));
        LATTICE_BUDGET = builder
                .comment("Most processor time spent on the lattice per frame, in microseconds. It is used up only while blocks wait to be read: on joining a world, after a teleport or while flying fast.")
                .defineInRange("latticeBudget", 1000, 100, 20000);
        LIGHT_LEAK_FIX = builder
                .comment("While a shader pack is on: smooth lighting takes no light from stairs, slabs and other partial blocks through their closed sides, so a wall of stairs with its flat back to a dark room doesn't light the floor, ceiling and walls along it with the daylight outside. Off is Minecraft's own smooth lighting. The world's terrain is rebuilt when this changes.")
                .define("lightLeakFix", true);
        MATERIALS = builder
                .comment("Give blocks without their own PBR maps material values (how smooth, how reflective, whether metal) for a shader pack that reads them: vanilla blocks and some tech mods' machines from the mod's own tables, other modded blocks from their sound, tags and name, and any block from config/shaderscompanion-materials.json. A resource pack's own PBR maps win. Takes effect when the shader pack is next loaded.")
                .define("materials", true);
        FAR_STORE = builder
                .comment("Keep the mod's own copy of the world for far terrain (in development; drawn only in a debug view, farTerrainDebug).")
                .define("farStore", false);
        FAR_STORE_BUDGET = builder
                .comment("Most processor time per frame spent copying the world for far terrain, in microseconds. It is used up only while sections wait to be copied: on joining a world, after a teleport or while flying fast.")
                .defineInRange("farStoreBudget", 150, 20, 2000);
        FAR_STORE_CACHE_MB = builder
                .comment("Memory for the far terrain kept ready near you, in MB.")
                .defineInRange("farStoreCacheMb", 64, 16, 8192);
        FAR_STORE_DISK_MB = builder
                .comment("Most disk space for far terrain, in MB, per world.")
                .defineInRange("farStoreDiskMb", 4096, 256, 1048576);
        FAR_RANGE = builder
                .comment("How far the far terrain reaches, in blocks (in development).")
                .defineInRange("farRange", 1024, 64, 32768);
        FAR_PIXELS_PER_VOXEL = builder
                .comment("Far terrain detail: the most pixels one of its voxels may cover before a finer level is used. Lower is sharper and costs more.")
                .defineInRange("farPixelsPerVoxel", 2, 1, 8);
        FAR_TERRAIN_DEBUG = builder
                .comment("Draw the far terrain in a debug view where the screen shows sky (in development; needs farStore). /shaderscompanion far show, far show alone and far hide change this in game.")
                .define("farTerrainDebug", false);
        FAR_GPU_MB = builder
                .comment("Most video memory for the far terrain's geometry, in MB. The buffer grows to this as terrain is meshed.")
                .defineInRange("farGpuMb", 256, 32, 4096);
        SPEC = builder.build();
    }

    private Config() {
    }
}
