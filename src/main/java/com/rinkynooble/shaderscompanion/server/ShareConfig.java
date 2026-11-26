package com.rinkynooble.shaderscompanion.server;

import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The server's far terrain sharing settings, saved in config/shaderscompanion-common.toml (a dedicated server's, or the
 * game's own for a singleplayer world opened to LAN). Read live: a change applies from the next player's join.
 */
public final class ShareConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue SHARE;
    public static final ForgeConfigSpec.IntValue RADIUS;
    public static final ForgeConfigSpec.IntValue KBPS_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue KBPS_TOTAL;
    public static final ForgeConfigSpec.BooleanValue UPDATES;
    public static final ForgeConfigSpec.BooleanValue HIDE_ENCLOSED;
    public static final ForgeConfigSpec.BooleanValue LAN;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_DIMENSIONS;
    public static final ForgeConfigSpec.IntValue CACHE_MB;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Far terrain sharing: a server with Shaders Companion sends its players who have the mod (and the far store on) the world's saved",
                "chunks around them for their far terrain, so they see what has been explored without going there. Nothing is sent from player to",
                "player, no chunk is generated for it, and a player can turn it down (/shaderscompanion far server deny).").push("farShare");
        SHARE = builder
                .comment("Share the world's saved chunks with players who have Shaders Companion.")
                .define("enabled", true);
        RADIUS = builder
                .comment("Most chunks around a player that are sent (each player asks for at most their own farRange). 64 chunks = 1,024 blocks.")
                .defineInRange("radius", 64, 8, 512);
        KBPS_PER_PLAYER = builder
                .comment("Most KB a second sent to one player.")
                .defineInRange("kbpsPerPlayer", 512, 16, 1 << 20);
        KBPS_TOTAL = builder
                .comment("Most KB a second sent to all players together (0: no limit beyond each player's).")
                .defineInRange("kbpsTotal", 4096, 0, 1 << 20);
        UPDATES = builder
                .comment("Send new and changed chunks as the server saves them (about 10 seconds after a change) to players for whom they are far terrain.")
                .define("updates", true);
        HIDE_ENCLOSED = builder
                .comment("Before sending, replace every block that can't be seen from open air (enclosed on all six sides by full blocks) with the",
                        "rock around it, so far terrain never carries ores or hidden rooms deep in the ground (anti x-ray). Ores on cave walls still show.")
                .define("hideEnclosed", true);
        LAN = builder
                .comment("Also share when a singleplayer world is opened to LAN (with the players who join it).")
                .define("lan", true);
        EXCLUDED_DIMENSIONS = builder
                .comment("Dimensions never shared, by id (minecraft:the_nether).")
                .defineListAllowEmpty(List.of("excludedDimensions"), List::of, entry -> entry instanceof String);
        CACHE_MB = builder
                .comment("Memory for columns already packed for sending, in MB (players near each other get the same columns).")
                .defineInRange("cacheMb", 32, 0, 1024);
        builder.pop();
        SPEC = builder.build();
    }

    private ShareConfig() {
    }
}
