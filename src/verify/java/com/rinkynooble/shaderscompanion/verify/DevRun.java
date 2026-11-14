package com.rinkynooble.shaderscompanion.verify;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.Config;
import com.rinkynooble.shaderscompanion.client.CubeDump;
import com.rinkynooble.shaderscompanion.client.CubeView;
import com.rinkynooble.shaderscompanion.client.DebugOverlay;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/**
 * Dev-run check, never shipped (gradlew runClient -PdevDump=N, see README). The client makes a fresh world and then:
 * <ul>
 * <li>default: lifts the player (-PdevLift, 60 blocks), shows the debug overlay, waits until the cube has been drawn N
 * more times, saves the cube and a screenshot, quits. From above, the up face must be empty sky, the down face a map
 * of the ground and the side faces horizons.</li>
 * <li>-PdevScene=pool: builds a water pool 30 blocks up with a red, glowstone-lit ceiling above the camera's view,
 * looks down at the water, and saves a screenshot with the mod drawing ("-on") and one with it turned off ("-off").
 * The ceiling should show in the water's reflection only in "-on". The dump's "-mirror.png" is the mirror view of the
 * water: the ceiling, with the player's head at the bottom.</li>
 * <li>-PdevScene=mobs: on a flat platform 20 blocks up, summons still mobs around the camera (a husk in front, a
 * villager behind, a pig and an iron golem to the sides, a spider behind for its glowing eyes) and places a chest and
 * a sign, then saves the cube. The mobs and the chest must show in the side faces, with the entity id bit in the data
 * texture.</li>
 * <li>-PdevScene=mirror: a wall of black stained glass 3 blocks in front of the camera and still mobs behind it,
 * screenshotted with the mod drawing ("-on") and turned off ("-off"). The mobs, out of the screen, and the player
 * should show in the glass only in "-on"; "-mirror.png" is the mirror view of the glass.</li>
 * <li>-PdevScene=corner: a block of black stained glass with one vertical edge straight ahead, so two of its faces
 * cover about the same area, while the view turns slowly across the edge and back. Logs the mirror's plane twice a
 * second; the plane should be the face under the crosshair and move to the other face once each way (2 switches).</li>
 * <li>-PdevScene=grass: the black glass wall in front of a lawn of grass (plants that sway with the shader pack), with
 * two "-on" screenshots a few frames apart and an "-off" one. The grass in the glass should look the same in both
 * "-on" shots, with no patches.</li>
 * <li>-PdevScene=match: a black glass wall seen at an angle, with bricks, logs, wool, leaves and a roof's shadow beside
 * it in the morning sun, so the same surfaces show on screen and in the glass (shots "-on" and "-off"). For checking
 * that reflections light surfaces as the screen does.</li>
 * <li>-PdevScene=ocean: the nearest deep ocean, the camera about 2.7 blocks above the water (standing on a barrier) looking out to the horizon
 * (-PdevLift=6 stands that many blocks higher; -PdevPitch, 5 degrees down by default here; -PdevYaw, by default the way the ocean runs farthest), screenshotted with the mod drawing ("-on") and turned off ("-off").
 * With Distant Horizons (-PdevDh -PdevWait=1400) the far water is LOD water: distant water should look the same in
 * both shots, with no stripes.</li>
 * <li>-PdevToggle=WATER_WAVE_FILTER (any scene but city and shadows): after the "-on" shot, six alternating timing rounds
 * with that shader pack option off and on, each with a screenshot ("-WATER_WAVE_FILTER-off0" and so on), instead of the
 * "-off" shot: costs and looks compared within one run (two runs differ by more than most changes, mostly through
 * Distant Horizons' loading). -PdevToggle=config.distantHorizons toggles one of the mod's own on/off settings instead.
 * To compare code, patch a copy of the pack so an option that does nothing in the scene switches between old and new.</li>
 * </ul>
 * <ul>
 * <li>-PdevScene=shadows: the black glass wall in front, and behind the camera pillars, a roof and two mobs in the low
 * morning sun, so their long shadows show only in the glass. Three still poses (straight at the glass, turned 30
 * degrees right, then left; shots "-pose0" to "-pose2") and a fourth a step to the side ("-on"): every pillar must
 * keep its shadow in the glass in all of them. Then four timing rounds with the mirror view's shadows off and on.</li>
 * </ul>
 * -PdevScene=move: the black glass wall seen at an angle across a lawn with plants, a fence post and pillars in the
 * low morning sun, on foot: a still shot, two while strafing, one while walking forward and one settled after
 * ("shaderscompanion-move-&lt;-PdevTag&gt;-still.png" and so on). The glass must look the same moving as standing still.
 * <p>
 * -PdevScene=boat: stands beside a vehicle on a pool (-PdevBoat, Superb Warfare's speed boat by default, which needs
 * -PdevSbw; -PdevBoat=superbwarfare:tiny_speedboat for the small one), rides it for three seconds, leaves it and
 * returns: shots "shaderscompanion-boat-&lt;-PdevTag&gt;-before", "-riding", "-after-early", "-after", then
 * "-after-noentities" (the mod's entities off), "-after-off" (the mod off) and "-after-on". With -PdevRain the scene
 * moves to the nearest deep ocean, where it rains. Every shot must look the same with the mod on and off apart from
 * reflections.
 * <p>
 * -PdevRain: rain from 30 ticks into any scene (it doesn't rain where the world starts; the ocean scene's biome has
 * rain).
 * <p>
 * -PdevScene=bright: a platform of white concrete, grass and stone bricks with a white wall, a quartz pillar and a
 * black glass wall, screenshotted at midnight and at noon, each with the mod drawing and turned off
 * ("shaderscompanion-bright-&lt;-PdevTag&gt;-night-on.png" and so on): for comparing the brightness of shader pack versions.
 * <p>
 * A run in which the mod never draws (the pack reads none of its views) logs why after 15 seconds in the world and
 * quits.
 * <p>
 * -PdevQuit (without -PdevDump) logs the status at the title screen and quits: a quick check of the bundled shader
 * pack's install (the status line's last part, and Oculus's "Using shaderpack" line in the log).
 * <p>
 * In the default scene the log also describes three texels just below the horizon of the +X face: with Distant
 * Horizons (-PdevDh) they are LODs (id 49152 and up) lit by daylight (sky light 255, block light 0).
 * Logs the status every 10 seconds, so a run that never draws shows why, and the frame time and the GPU's time per
 * frame (medians of the last 120 frames) at each screenshot.
 */
@Mod.EventBusSubscriber(modid = ShadersCompanion.MOD_ID, value = Dist.CLIENT)
public final class DevRun {
    private static final int DUMP_FRAMES = Integer.getInteger("shaderscompanion.devDump", 0);
    private static final int LIFT_BLOCKS = Integer.getInteger("shaderscompanion.devLift", 60);
    private static final String SCENE = System.getProperty("shaderscompanion.devScene", "");
    private static final int PITCH = Integer.getInteger("shaderscompanion.devPitch", 50);
    private static final int WAIT_TICKS = Integer.getInteger("shaderscompanion.devWait", 160);
    /** A shader pack on/off option, or config.<name> for the mod's own, to time in alternating rounds (-PdevToggle). */
    private static final String TOGGLE = System.getProperty("shaderscompanion.devToggle", "");
    private static final int TOGGLE_ROUNDS = 6;
    /** Ticks a round waits after the pack reloads before its timed ticks. */
    private static final int TOGGLE_SETTLE_TICKS = 100;
    private static final int START_FRAME = 60;
    /** Ticks per timing round: long enough for the 120 timed frames to be all from that round. */
    private static final int TIMING_TICKS = 120;
    private static final String WORLD = "shaderscompanion-dev";
    private static boolean worldRequested;
    private static int phase;
    private static int phaseTick;
    private static int ticks;
    private static int timingRound;
    private static int worldTicks;
    /** -PdevNoShaders: the scene may start (100 ticks in the world). */
    private static boolean noShadersReady;
    /** Ticks in the world without the mod drawing before a run gives up and logs why. */
    private static final int IDLE_TICKS = 300;
    private static String prefix = "devrun";
    /** The boat scene's vehicle (-PdevBoat; Superb Warfare's speed boat needs -PdevSbw). */
    private static final String BOAT = System.getProperty("shaderscompanion.devBoat", "superbwarfare:speedboat");
    private static net.minecraft.world.phys.Vec3 boatStand;
    private static float boatYaw;
    private static float boatPitch;
    private static volatile String boatSummon;
    // Frame timing: GPU time between timestamps at the start and end of each frame (read 4 frames later), and the
    // time between frame starts
    private static final int TIMED_FRAMES = 120;
    private static final int[] frameQueries = new int[8];
    private static final double[] gpuMillis = new double[TIMED_FRAMES];
    private static final double[] frameMillis = new double[TIMED_FRAMES];
    private static final double[] modGpuMillis = new double[TIMED_FRAMES];
    private static final double[] entityMillis = new double[TIMED_FRAMES];
    private static int framesTimed;
    private static int frameSlot;
    private static long lastFrameStart;

    private DevRun() {
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (Boolean.getBoolean("shaderscompanion.devQuit") && event.getNewScreen() instanceof TitleScreen) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run at the title screen: {}", String.join(" | ", DebugOverlay.statusLines()));
            Minecraft.getInstance().tell(() -> Minecraft.getInstance().stop());
            return;
        }
        if (DUMP_FRAMES <= 0 || worldRequested || !(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }
        worldRequested = true;
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: creating world {}", WORLD);
            LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261108L, false, false), WorldPresets::createNormalWorldDimensions);
        });
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (DUMP_FRAMES <= 0 || phase < 0) {
            return;
        }
        if (frameQueries[0] == 0) {
            GL15C.glGenQueries(frameQueries);
        }
        int slot = frameSlot % 4;
        if (event.phase == TickEvent.Phase.START) {
            long now = System.nanoTime();
            if (frameSlot >= 4) {
                long start = GL33C.glGetQueryObjecti64(frameQueries[slot * 2], GL15C.GL_QUERY_RESULT);
                long end = GL33C.glGetQueryObjecti64(frameQueries[slot * 2 + 1], GL15C.GL_QUERY_RESULT);
                gpuMillis[framesTimed % TIMED_FRAMES] = (end - start) / 1.0E6;
                frameMillis[framesTimed % TIMED_FRAMES] = (now - lastFrameStart) / 1.0E6;
                modGpuMillis[framesTimed % TIMED_FRAMES] = Math.max(0L, CubeView.gpuNanosForChecks()) / 1.0E6;
                entityMillis[framesTimed % TIMED_FRAMES] = CubeView.entityNanosForChecks() / 1.0E6;
                framesTimed++;
            }
            lastFrameStart = now;
            GL33C.glQueryCounter(frameQueries[slot * 2], GL33C.GL_TIMESTAMP);
        } else {
            GL33C.glQueryCounter(frameQueries[slot * 2 + 1], GL33C.GL_TIMESTAMP);
            frameSlot++;
        }
    }

    private static double median(double[] values) {
        int n = Math.min(framesTimed, values.length);
        if (n == 0) {
            return 0.0;
        }
        double[] sorted = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(sorted);
        return sorted[n / 2];
    }

    private static String frameTiming() {
        return String.format("frame %.2f ms, GPU %.2f ms, the mod's GPU %.2f ms, entities' CPU %.3f ms (medians of the last %d frames)", median(frameMillis),
                median(gpuMillis), median(modGpuMillis), median(entityMillis), Math.min(framesTimed, TIMED_FRAMES));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (DUMP_FRAMES <= 0 || phase < 0 || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (++ticks % 200 == 0) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: {}", String.join(" | ", DebugOverlay.statusLines()));
        }
        boolean pool = SCENE.equals("pool");
        boolean mirror = SCENE.equals("mirror");
        boolean mobs = SCENE.equals("mobs") || mirror;
        boolean corner = SCENE.equals("corner");
        boolean grass = SCENE.equals("grass");
        boolean shadows = SCENE.equals("shadows");
        boolean bright = SCENE.equals("bright");
        boolean move = SCENE.equals("move");
        boolean boat = SCENE.equals("boat");
        boolean aquarium = SCENE.equals("aquarium");
        boolean overlay = SCENE.equals("overlay");
        boolean turn = SCENE.equals("turn");
        boolean city = SCENE.equals("city");
        boolean glass = mirror || corner || grass || shadows || aquarium || overlay || city || SCENE.equals("match");
        boolean ocean = SCENE.equals("ocean");
        boolean match = SCENE.equals("match");
        if (phase == 1 && ticks - phaseTick == 30 && Boolean.getBoolean("shaderscompanion.devRain") && mc.player != null) {
            // -PdevRain: rain for the rest of the run (needs a biome where it rains: the ocean scene's)
            mc.player.connection.sendCommand("weather rain");
        }
        // -PdevNoShaders: the scene starts after 100 ticks in the world, since the mod draws nothing without a shader pack
        boolean noShaders = Boolean.getBoolean("shaderscompanion.devNoShaders");
        if (phase == 0 && mc.player != null && noShaders && ++worldTicks >= 100) {
            noShadersReady = true;
        }
        boolean ready = CubeView.framesDrawn() >= START_FRAME || noShadersReady;
        if (phase == 0 && mc.player != null && !noShaders && CubeView.framesDrawn() < START_FRAME && ++worldTicks >= IDLE_TICKS) {
            // The mod never drew (a shader pack setting that reads none of its views, say): log why and quit
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, nothing drawn after {} ticks in the world: {}", IDLE_TICKS, String.join(" | ", DebugOverlay.statusLines()));
            ShadersCompanion.LOGGER.info("Shaders Companion dev run timing without drawing: {}", frameTiming());
            finish(mc);
        } else if (phase == 0 && mc.player != null && ready) {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
            if (pool) {
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set noon", "weather clear", "gamerule doDaylightCycle false",
                        "tp @s ~ ~30 ~ 0 " + PITCH,
                        "fill ~-14 ~-5 ~-14 ~14 ~-4 ~14 minecraft:stone",
                        "fill ~-13 ~-4 ~-13 ~13 ~-4 ~13 minecraft:water",
                        "fill ~-10 ~6 ~-4 ~10 ~6 ~16 minecraft:red_wool",
                        "fill ~-8 ~6 ~-2 ~8 ~6 ~14 minecraft:glowstone replace minecraft:red_wool"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the pool scene");
            } else if (ocean) {
                mc.options.hideGui = true;
                for (String command : new String[]{"time set noon", "weather clear", "gamerule doDaylightCycle false"}) {
                    mc.player.connection.sendCommand(command);
                }
                MinecraftServer server = mc.getSingleplayerServer();
                BlockPos from = mc.player.blockPosition();
                int pitch = Integer.getInteger("shaderscompanion.devPitch", 5);
                int fixedYaw = Integer.getInteger("shaderscompanion.devYaw", -1);
                int lift = Integer.getInteger("shaderscompanion.devLift", 0);
                server.execute(() -> {
                    var found = server.overworld().findClosestBiome3d(biome -> biome.is(BiomeTags.IS_DEEP_OCEAN), from, 6400, 32, 64);
                    BlockPos at = found == null ? from : found.getFirst();
                    // Without -PdevYaw, look the way the ocean runs farthest (by biome, without generating chunks)
                    int yaw = fixedYaw;
                    if (yaw < 0) {
                        int best = -1;
                        for (int candidate = 0; candidate < 360; candidate += 45) {
                            double dx = -Math.sin(Math.toRadians(candidate));
                            double dz = Math.cos(Math.toRadians(candidate));
                            int run = 0;
                            while (run < 48 && server.overworld().getBiome(at.offset((int) (dx * 16 * (run + 1)), 0, (int) (dz * 16 * (run + 1)))).is(BiomeTags.IS_OCEAN)) {
                                run++;
                            }
                            if (run > best) {
                                best = run;
                                yaw = candidate;
                            }
                        }
                    }
                    int finalYaw = yaw;
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run: deep ocean at {}, looking at yaw {}", at, yaw);
                    // Placed by the server, which loads the chunk (a setblock command fails while it isn't loaded)
                    server.overworld().setBlock(new BlockPos(at.getX(), 63 + lift, at.getZ()), Blocks.BARRIER.defaultBlockState(), 3);
                    if (Boolean.getBoolean("shaderscompanion.devGlass")) {
                        // -PdevGlass: a ring of light grey stained glass around the camera, so all the water is seen through glass
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dz = -2; dz <= 2; dz++) {
                                if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
                                    for (int dy = 64; dy <= 67; dy++) {
                                        server.overworld().setBlock(new BlockPos(at.getX() + dx, dy + lift, at.getZ() + dz), Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState(), 3);
                                    }
                                }
                            }
                        }
                    }
                    mc.tell(() -> mc.player.connection.sendCommand("tp @s " + (at.getX() + 0.5) + " " + (64 + lift) + " " + (at.getZ() + 0.5) + " " + finalYaw + " " + pitch));
                });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the ocean scene");
            } else if (mobs) {
                for (String command : new String[]{
                        "time set noon", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "difficulty easy", "tp @s ~ ~20 ~ 0 10",
                        "fill ~-10 ~-1 ~-10 ~10 ~-1 ~10 minecraft:smooth_stone", "fill ~-10 ~ ~-10 ~10 ~6 ~10 minecraft:air",
                        "summon minecraft:husk ~ ~ ~4 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f]}",
                        "summon minecraft:villager ~ ~ ~-4 {NoAI:1b,PersistenceRequired:1b}",
                        "summon minecraft:pig ~4 ~ ~ {NoAI:1b,PersistenceRequired:1b}",
                        "summon minecraft:iron_golem ~-5 ~ ~ {NoAI:1b,PersistenceRequired:1b}",
                        "summon minecraft:spider ~-2 ~ ~-7 {NoAI:1b,PersistenceRequired:1b}",
                        "setblock ~4 ~ ~2 minecraft:chest[facing=west]", "setblock ~-3 ~ ~-3 minecraft:oak_sign"}) {
                    mc.player.connection.sendCommand(command);
                }
                if (mirror) {
                    mc.options.hideGui = true;
                    mc.player.connection.sendCommand("fill ~-7 ~ ~3 ~7 ~4 ~3 minecraft:black_stained_glass");
                    if (Boolean.getBoolean("shaderscompanion.devTacz")) {
                        // A TaCZ gun in hand and armour on, seen in the glass
                        for (String command : new String[]{
                                "item replace entity @s weapon.mainhand with tacz:modern_kinetic_gun{GunId:\"tacz:ak47\"}",
                                "item replace entity @s armor.chest with minecraft:diamond_chestplate",
                                "item replace entity @s armor.head with minecraft:iron_helmet",
                                "item replace entity @s armor.legs with minecraft:golden_leggings"}) {
                            mc.player.connection.sendCommand(command);
                        }
                    }
                } else {
                    DebugOverlay.setVisible(true);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: summoned the mobs");
            } else if (match) {
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set 2000", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ -35 12",
                        "fill ~-10 ~-1 ~-10 ~12 ~-1 ~10 minecraft:smooth_stone", "fill ~-10 ~ ~-10 ~12 ~8 ~10 minecraft:air",
                        "fill ~-6 ~ ~6 ~3 ~4 ~6 minecraft:black_stained_glass",
                        "fill ~6 ~ ~0 ~6 ~3 ~4 minecraft:bricks", "fill ~5 ~ ~-1 ~5 ~2 ~-1 minecraft:oak_log",
                        "setblock ~4 ~ ~3 minecraft:white_wool", "setblock ~4 ~ ~1 minecraft:red_wool", "setblock ~4 ~1 ~1 minecraft:oak_leaves[persistent=true]",
                        "fill ~3 ~3 ~0 ~6 ~3 ~4 minecraft:oak_planks", "fill ~2 ~ ~-2 ~2 ~1 ~-2 minecraft:cobblestone"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the match scene");
            } else if (city) {
                // A dense block of towers at y 150 around a street: stone brick walls with light grey stained glass
                // windows, chests, furnaces and signs inside, villagers on the street, and a black glass wall on the
                // street's east side, for the mod's costs where a lot is drawn
                mc.options.hideGui = true;
                for (String command : new String[]{"time set 2000", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false"}) {
                    mc.player.connection.sendCommand(command);
                }
                MinecraftServer server = mc.getSingleplayerServer();
                BlockPos at = mc.player.blockPosition();
                BlockPos base = new BlockPos(at.getX(), 150, at.getZ());
                server.execute(() -> {
                    var level = server.overworld();
                    var wall = Blocks.STONE_BRICKS.defaultBlockState();
                    var window = Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState();
                    for (int x = -48; x <= 48; x++) {
                        for (int z = -48; z <= 64; z++) {
                            level.setBlock(base.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                        }
                    }
                    int towers = 0;
                    for (int bx = -45; bx <= 45; bx += 9) {
                        if (Math.abs(bx) < 9) {
                            continue;
                        }
                        for (int bz = -45; bz <= 60; bz += 9) {
                            int height = 8 + Math.floorMod(bx * 7 + bz * 3, 5) * 4;
                            for (int y = 0; y < height; y++) {
                                for (int x = 0; x < 6; x++) {
                                    for (int z = 0; z < 6; z++) {
                                        boolean edge = x == 0 || x == 5 || z == 0 || z == 5;
                                        boolean floor = y % 4 == 3;
                                        if (!edge && !floor) {
                                            continue;
                                        }
                                        boolean pillar = (x == 0 || x == 5) && (z == 0 || z == 5);
                                        boolean glassAt = edge && !pillar && !floor && y % 4 != 0;
                                        level.setBlock(base.offset(bx + x, y, bz + z), glassAt ? window : wall, 2);
                                    }
                                }
                            }
                            level.setBlock(base.offset(bx + 1, 0, bz + 1), Blocks.CHEST.defaultBlockState(), 2);
                            level.setBlock(base.offset(bx + 2, 0, bz + 1), Blocks.CHEST.defaultBlockState(), 2);
                            level.setBlock(base.offset(bx + 3, 0, bz + 1), Blocks.FURNACE.defaultBlockState(), 2);
                            level.setBlock(base.offset(bx + 4, 0, bz + 1), Blocks.OAK_SIGN.defaultBlockState(), 2);
                            level.setBlock(base.offset(bx + 1, 4, bz + 1), Blocks.CHEST.defaultBlockState(), 2);
                            level.setBlock(base.offset(bx + 2, 4, bz + 1), Blocks.BARREL.defaultBlockState(), 2);
                            towers++;
                        }
                    }
                    for (int y = 0; y <= 6; y++) {
                        for (int z = -4; z <= 16; z++) {
                            level.setBlock(base.offset(5, y, z), Blocks.BLACK_STAINED_GLASS.defaultBlockState(), 2);
                        }
                    }
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run: built {} towers", towers);
                    mc.tell(() -> {
                        mc.player.connection.sendCommand("tp @s " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5) + " 20 5");
                        for (int i = 0; i < 60; i++) {
                            int vx = (i % 4) * 3 - 5;
                            int vz = (i / 4) * 6 - 40;
                            mc.player.connection.sendCommand("summon minecraft:villager " + (base.getX() + vx + 0.5) + " " + base.getY() + " " + (base.getZ() + vz + 0.5) + " {NoAI:1b,PersistenceRequired:1b}");
                        }
                    });
                });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the city scene");
            } else if (turn) {
                // A water pool below and a black glass wall 3 blocks to the west (yaw 90); the view starts level, looking south (yaw 0)
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set noon", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ -90 0",
                        "fill ~-12 ~-2 ~-12 ~12 ~-2 ~12 minecraft:stone", "fill ~-11 ~-1 ~-11 ~11 ~-1 ~11 minecraft:water",
                        "fill ~-12 ~ ~-12 ~12 ~10 ~12 minecraft:air",
                        "fill ~-3 ~ ~-10 ~-3 ~9 ~10 minecraft:black_stained_glass"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the turn scene");
            } else if (overlay) {
                // Superb Warfare's vehicle container (needs -PdevSbw) 4 blocks ahead with a crowbar in hand, which draws its
                // green placement box, beside a white wall and a grass lawn
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set noon", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ 0 " + Integer.getInteger("shaderscompanion.devPitch", 12),
                        "fill ~-14 ~-1 ~-6 ~14 ~-1 ~20 minecraft:grass_block", "fill ~-14 ~ ~-6 ~14 ~10 ~20 minecraft:air",
                        "fill ~-14 ~ ~16 ~14 ~6 ~16 minecraft:white_concrete",
                        "setblock ~ ~ ~5 superbwarfare:container{EntityType:\"superbwarfare:speedboat\"}",
                        "item replace entity @s weapon.mainhand with superbwarfare:crowbar"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the overlay scene");
            } else if (aquarium) {
                // Water 7 deep over coloured wool, half of it seen through a light grey stained glass wall
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set noon", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~30 ~ 0 " + Integer.getInteger("shaderscompanion.devPitch", 35),
                        "fill ~-12 ~-10 ~-2 ~12 ~-2 ~20 minecraft:stone", "fill ~-11 ~-9 ~-1 ~11 ~-9 ~19 minecraft:orange_wool",
                        "fill ~-11 ~-9 ~5 ~11 ~-9 ~7 minecraft:magenta_wool", "fill ~-11 ~-9 ~11 ~11 ~-9 ~13 minecraft:lime_wool",
                        "fill ~-11 ~-8 ~-1 ~11 ~-2 ~19 minecraft:water", "fill ~-12 ~-1 ~-2 ~12 ~6 ~20 minecraft:air",
                        "fill ~0 ~-1 ~2 ~9 ~5 ~2 minecraft:light_gray_stained_glass"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the aquarium scene");
            } else if (boat && Boolean.getBoolean("shaderscompanion.devRain")) {
                // In the rain: on the nearest deep ocean (it doesn't rain where the world starts), the boat summoned later
                for (String command : new String[]{"time set 9000", "gamerule doDaylightCycle false", "gamerule doMobSpawning false"}) {
                    mc.player.connection.sendCommand(command);
                }
                MinecraftServer server = mc.getSingleplayerServer();
                BlockPos from = mc.player.blockPosition();
                server.execute(() -> {
                    var found = server.overworld().findClosestBiome3d(biome -> biome.is(BiomeTags.IS_DEEP_OCEAN), from, 6400, 32, 64);
                    BlockPos at = found == null ? from : found.getFirst();
                    server.overworld().setBlock(new BlockPos(at.getX(), 64, at.getZ()), Blocks.BARRIER.defaultBlockState(), 3);
                    boatSummon = "summon " + BOAT + " " + (at.getX() + 3.5) + " 63 " + (at.getZ() + 7.5) + " {Energy:100000}";
                    mc.tell(() -> mc.player.connection.sendCommand("tp @s " + (at.getX() + 0.5) + " 65 " + (at.getZ() + 0.5) + " 0 "
                            + Integer.getInteger("shaderscompanion.devPitch", 25)));
                });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the boat scene on the ocean");
            } else if (boat) {
                for (String command : new String[]{
                        "time set 9000", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~30 ~ 0 " + Integer.getInteger("shaderscompanion.devPitch", 25),
                        "fill ~-14 ~-5 ~-6 ~14 ~-4 ~24 minecraft:stone", "fill ~-13 ~-3 ~-5 ~13 ~-3 ~23 minecraft:water",
                        "fill ~-14 ~-3 ~-6 ~14 ~-3 ~-6 minecraft:stone", "fill ~-14 ~-3 ~24 ~14 ~-3 ~24 minecraft:stone",
                        "fill ~-14 ~-3 ~-6 ~-14 ~-3 ~24 minecraft:stone", "fill ~14 ~-3 ~-6 ~14 ~-3 ~24 minecraft:stone",
                        "summon " + BOAT + " ~3 ~-2 ~7"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the boat scene");
            } else if (move) {
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set 2000", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ -35 8",
                        "fill ~-14 ~-1 ~-16 ~16 ~-1 ~10 minecraft:smooth_stone", "fill ~-14 ~ ~-16 ~16 ~8 ~10 minecraft:air",
                        "fill ~-10 ~ ~6 ~6 ~4 ~6 minecraft:black_stained_glass",
                        "fill ~-10 ~-1 ~2 ~12 ~-1 ~4 minecraft:grass_block",
                        "setblock ~1 ~ ~3 minecraft:grass", "setblock ~3 ~ ~4 minecraft:fern", "setblock ~-2 ~ ~3 minecraft:grass",
                        "fill ~5 ~ ~2 ~5 ~1 ~2 minecraft:oak_fence", "setblock ~7 ~ ~3 minecraft:grass",
                        "fill ~9 ~ ~-2 ~9 ~4 ~-2 minecraft:bricks", "fill ~-4 ~ ~-6 ~-4 ~3 ~-6 minecraft:oak_log",
                        "fill ~4 ~ ~-9 ~4 ~4 ~-9 minecraft:red_wool",
                        "summon minecraft:husk ~2 ~ ~-4 {NoAI:1b,PersistenceRequired:1b}"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the move scene");
            } else if (bright) {
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set 18000", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ 0 25",
                        "fill ~-20 ~-1 ~-20 ~20 ~-1 ~20 minecraft:white_concrete", "fill ~-20 ~ ~-20 ~20 ~9 ~20 minecraft:air",
                        "fill ~-20 ~-1 ~6 ~-1 ~-1 ~20 minecraft:grass_block", "fill ~1 ~-1 ~6 ~20 ~-1 ~20 minecraft:stone_bricks",
                        "fill ~-8 ~ ~12 ~8 ~5 ~12 minecraft:white_concrete", "fill ~-12 ~ ~4 ~-12 ~4 ~11 minecraft:black_stained_glass",
                        "fill ~10 ~ ~6 ~10 ~3 ~9 minecraft:quartz_block"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the bright scene");
            } else if (shadows) {
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set 2000", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ 0 8",
                        "fill ~-20 ~-1 ~-44 ~20 ~-1 ~10 minecraft:smooth_stone", "fill ~-20 ~ ~-44 ~20 ~9 ~10 minecraft:air",
                        "fill ~-7 ~ ~4 ~7 ~4 ~4 minecraft:black_stained_glass",
                        "fill ~4 ~ ~-4 ~4 ~3 ~-4 minecraft:bricks", "fill ~6 ~ ~-9 ~6 ~4 ~-9 minecraft:oak_log",
                        "fill ~3 ~ ~-14 ~3 ~3 ~-14 minecraft:red_wool", "fill ~7 ~ ~-19 ~7 ~5 ~-19 minecraft:bricks",
                        "fill ~2 ~ ~-24 ~2 ~4 ~-24 minecraft:oak_log", "fill ~5 ~ ~-30 ~5 ~5 ~-30 minecraft:red_wool",
                        "fill ~8 ~ ~-36 ~8 ~6 ~-36 minecraft:bricks", "fill ~-6 ~ ~-12 ~-6 ~4 ~-12 minecraft:bricks",
                        "fill ~-9 ~4 ~-22 ~-3 ~4 ~-18 minecraft:oak_planks", "fill ~-9 ~ ~-22 ~-9 ~3 ~-22 minecraft:oak_log",
                        "summon minecraft:husk ~1 ~ ~-6 {NoAI:1b,PersistenceRequired:1b}",
                        "summon minecraft:iron_golem ~-3 ~ ~-9 {NoAI:1b,PersistenceRequired:1b}"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the shadows scene");
            } else if (corner || grass) {
                mc.options.hideGui = true;
                String[] commands = corner ? new String[]{
                        "time set noon", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ -60 0",
                        "fill ~-10 ~-1 ~-10 ~10 ~-1 ~10 minecraft:smooth_stone", "fill ~-10 ~ ~-10 ~10 ~6 ~10 minecraft:air",
                        "fill ~3 ~ ~3 ~6 ~5 ~6 minecraft:black_stained_glass"}
                        : new String[]{
                        "time set noon", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ 0 10",
                        "fill ~-10 ~-1 ~-10 ~10 ~-1 ~10 minecraft:grass_block", "fill ~-10 ~ ~-10 ~10 ~6 ~10 minecraft:air",
                        "fill ~-7 ~ ~-7 ~7 ~ ~2 minecraft:grass",
                        "fill ~-7 ~ ~3 ~7 ~4 ~3 minecraft:black_stained_glass"};
                for (String command : commands) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the {} scene", SCENE);
            } else {
                mc.player.connection.sendCommand("tp @s ~ ~" + LIFT_BLOCKS + " ~ 0 0");
                DebugOverlay.setVisible(true);
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: lifted the player {} blocks", LIFT_BLOCKS);
            }
            phase = 1;
            phaseTick = ticks;
        } else if (phase == 1 && corner && ticks - phaseTick < WAIT_TICKS) {
            // Turn from 60 to 30 degrees right of +Z across the glass block's front edge (at 45 degrees) and back
            float progress = 2.0F * (ticks - phaseTick) / WAIT_TICKS;
            float yaw = -60.0F + 30.0F * (progress <= 1.0F ? progress : 2.0F - progress);
            mc.player.setYRot(yaw);
            mc.player.yRotO = yaw;
            if ((ticks - phaseTick) % 10 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, yaw {}: {}", String.format("%.1f", yaw), DebugOverlay.statusLines()[DebugOverlay.statusLines().length - 1]);
            }
        } else if (phase == 1 && turn && ticks - phaseTick >= WAIT_TICKS) {
            // Three quick turns from facing away from the glass wall (yaw -90, the water is mirrored) to facing it (yaw 90)
            // and back, 180 degrees in 6 ticks; the mirrored plane is logged every tick (x is the glass, y the water)
            int t = ticks - phaseTick - WAIT_TICKS;
            int cycle = t % 60;
            float yaw = cycle < 6 ? -90.0F + 30.0F * (cycle + 1) : cycle < 30 ? 90.0F : cycle < 36 ? 90.0F - 30.0F * (cycle - 29) : -90.0F;
            // yRotO keeps the last tick's yaw, so frames turn smoothly between ticks as with a mouse
            mc.player.setYRot(yaw);
            String[] lines = DebugOverlay.statusLines();
            String mirrorLine = "";
            for (String line : lines) {
                int at = line.indexOf("Mirror view: ");
                if (at >= 0) {
                    mirrorLine = line.substring(at + 13);
                }
            }
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, turn tick {} yaw {}: {}", t, yaw, mirrorLine);
            if (t == 2 || t == 4 || t == 6) {
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-turn-" + System.getProperty("shaderscompanion.devTag", "run") + "-t" + t + ".png", mc.getMainRenderTarget(), message -> { });
            }
            if (t >= 180) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run timing with the mod on: {}", frameTiming());
                finish(mc);
            }
        } else if (phase == 1 && boat) {
            // Stand beside the boat, ride it, leave it, and stand where the run began: shots before, riding and after,
            // then after with the mod's entities off, with the mod off, and with it on again
            int t = ticks - phaseTick;
            int s = t - WAIT_TICKS;
            String tag = System.getProperty("shaderscompanion.devTag", "run");
            if (t == 40 && boatSummon != null) {
                mc.player.connection.sendCommand(boatSummon);
            }
            if (t == 20) {
                boatStand = mc.player.position();
                boatYaw = mc.player.getYRot();
                boatPitch = mc.player.getXRot();
            }
            String back = boatStand == null ? null : "tp @s " + boatStand.x + " " + boatStand.y + " " + boatStand.z + " " + boatYaw + " " + boatPitch;
            // Driven forward for two seconds while riding
            mc.options.keyUp.setDown(s >= 6 && s < 46);
            if (s == 1) {
                mc.player.connection.sendCommand("ride @s mount @e[type=" + BOAT + ",limit=1,sort=nearest]");
            } else if (s == 61) {
                mc.player.connection.sendCommand("ride @s dismount");
            } else if (s == 63 && back != null) {
                mc.player.connection.sendCommand(back);
                mc.player.getAbilities().flying = true;
                mc.player.onUpdateAbilities();
            } else if (s == 121) {
                Config.ENTITIES.set(false);
            } else if (s == 161) {
                Config.ENTITIES.set(true);
                Config.ENABLED.set(false);
            } else if (s == 201) {
                Config.ENABLED.set(true);
            }
            String name = s == 0 ? "before" : s == 60 ? "riding" : s == 72 ? "after-early" : s == 120 ? "after" : s == 160 ? "after-noentities"
                    : s == 200 ? "after-off" : s == 260 ? "after-on" : null;
            if (name != null) {
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-boat-" + tag + "-" + name + ".png", mc.getMainRenderTarget(), message -> { });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, {} {} at {} (vehicle {}): {}", tag, name, mc.player.position(),
                        mc.player.getVehicle(), String.join(" | ", DebugOverlay.statusLines()));
            }
            if (s == 261) {
                finish(mc);
            }
        } else if (phase == 1 && move) {
            // On foot: a still shot, two while strafing left, one while walking forward, and one settled after
            int t = ticks - phaseTick;
            int s = t - WAIT_TICKS;
            String tag = System.getProperty("shaderscompanion.devTag", "run");
            if (t == 20) {
                mc.player.getAbilities().flying = false;
                mc.player.onUpdateAbilities();
            }
            mc.options.keyLeft.setDown(s >= 0 && s < 26);
            mc.options.keyUp.setDown(s >= 40 && s < 60);
            String name = s == 0 ? "still" : s == 14 ? "strafe1" : s == 20 ? "strafe2" : s == 52 ? "forward" : s == 130 ? "settled" : null;
            if (name != null) {
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-move-" + tag + "-" + name + ".png", mc.getMainRenderTarget(), message -> { });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, {} {} at {}: {}", tag, name, mc.player.position(), DebugOverlay.statusLines()[DebugOverlay.statusLines().length - 1]);
            }
            if (s == 131) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run timing with the mod on: {}", frameTiming());
                finish(mc);
            }
        } else if (phase == 1 && bright) {
            // Midnight with the mod drawing and turned off, then noon the same way
            int t = ticks - phaseTick - WAIT_TICKS;
            String tag = System.getProperty("shaderscompanion.devTag", "run");
            if (t == 0 || t == 80 || t == 300 || t == 380) {
                String name = (t < 300 ? "night" : "day") + (t == 0 || t == 300 ? "-on" : "-off");
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-bright-" + tag + "-" + name + ".png", mc.getMainRenderTarget(), message -> { });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, {} {}: {}", tag, name, DebugOverlay.statusLines()[0]);
            } else if (t == 1 || t == 301) {
                Config.ENABLED.set(false);
            } else if (t == 81) {
                Config.ENABLED.set(true);
                mc.player.connection.sendCommand("time set 6000");
            } else if (t == 381) {
                Config.ENABLED.set(true);
                finish(mc);
            }
        } else if (phase == 1 && shadows && ticks - phaseTick < WAIT_TICKS) {
            // Three still poses before the last one (straight at the glass, a step to the side): turned right, turned
            // left, straight. Each is held for 50 ticks and screenshotted at its end.
            int left = WAIT_TICKS - (ticks - phaseTick);
            int pose = left > 150 ? -1 : left > 100 ? 0 : left > 50 ? 1 : 2;
            float[] yaws = {0.0F, 30.0F, -30.0F};
            if (pose >= 0) {
                mc.player.setYRot(yaws[pose]);
                mc.player.yRotO = yaws[pose];
                if (left == 101 || left == 51 || left == 1) {
                    Screenshot.grab(mc.gameDirectory, "shaderscompanion-shadows-" + System.currentTimeMillis() + "-pose" + pose + ".png", mc.getMainRenderTarget(), message -> { });
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, pose {} (yaw {}): {}", pose, yaws[pose], DebugOverlay.statusLines()[DebugOverlay.statusLines().length - 1]);
                }
                if (left == 1) {
                    mc.player.setYRot(0.0F);
                    mc.player.yRotO = 0.0F;
                    mc.player.connection.sendCommand("tp @s ~-5 ~ ~ 0 8");
                }
            }
        } else if (phase == 1 && shadows && ticks - phaseTick < WAIT_TICKS + 50) {
            // The last pose settles before the usual shots
        } else if (phase == 1 && grass && ticks - phaseTick == WAIT_TICKS - 6) {
            Screenshot.grab(mc.gameDirectory, "shaderscompanion-grass-" + System.currentTimeMillis() + "-on-early.png", mc.getMainRenderTarget(), message -> { });
        } else if (phase == 1 && (pool || mobs || corner || grass || ocean || match || shadows || aquarium || overlay || turn || city ? ticks - phaseTick >= WAIT_TICKS : CubeView.framesDrawn() >= START_FRAME + DUMP_FRAMES)) {
            // The usual shots: timed scenes after WAIT_TICKS (turn is listed only to hold them back; its own branch above
            // takes over at WAIT_TICKS), the others once the mod has drawn DUMP_FRAMES frames
            if (noShaders) {
                // Nothing of the mod's to dump: the screenshot only
                prefix = "shaderscompanion-" + System.currentTimeMillis() + "-noshaders";
                if (overlay) {
                    logOverlayContainer(mc);
                }
                Screenshot.grab(mc.gameDirectory, prefix + ".png", mc.getMainRenderTarget(), message -> { });
                finish(mc);
                return;
            }
            try {
                prefix = CubeDump.dump();
                int size = CubeView.faceSizeForChecks();
                // Up face (2: column 2, row 0) centre and a quarter in, and the down face (3: column 0, row 1) centre
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, up: {}", CubeDump.describeTexel(size * 5 / 2, size / 2));
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, up: {}", CubeDump.describeTexel(size * 5 / 2 + size / 4, size / 2 + size / 4));
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, down: {}", CubeDump.describeTexel(size / 2, size * 3 / 2));
                if (mobs) {
                    // The husk 4 blocks along +Z (face 4: column 1, row 1), about half a block below eye level
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, husk (+Z): {}", CubeDump.describeTexel(size * 3 / 2, size + size * 7 / 16));
                    // The villager 4 blocks along -Z (face 5: column 2, row 1)
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, villager (-Z): {}", CubeDump.describeTexel(size * 5 / 2, size + size * 7 / 16));
                    // The pig 4 blocks along +X (face 0: column 0, row 0), about 1.2 blocks below eye level
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, pig (+X): {}", CubeDump.describeTexel(size / 2, size * 7 / 20));
                    // The chest at +X 4, +Z 2 (face 0), its lid about 0.75 blocks below eye level
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, chest (+X): {}", CubeDump.describeTexel(size * 778 / 1000, size * 39 / 100));
                } else if (!pool && !glass && !ocean) {
                    // Just below the horizon of the +X face (face 0: column 0, row 0): far ground, LODs with Distant Horizons
                    for (int percent : new int[]{46, 43, 40}) {
                        ShadersCompanion.LOGGER.info("Shaders Companion dev run, +X horizon {}%: {}", percent, CubeDump.describeTexel(size / 2, size * percent / 100));
                    }
                }
                if (overlay) {
                    logOverlayContainer(mc);
                }
                Screenshot.grab(mc.gameDirectory, prefix + (pool || glass || ocean ? "-on.png" : "-main.png"), mc.getMainRenderTarget(), message -> { });
            } catch (Exception e) {
                ShadersCompanion.LOGGER.error("Shaders Companion dev run: saving failed", e);
            }
            ShadersCompanion.LOGGER.info("Shaders Companion dev run at {}: {}", mc.player == null ? "?" : mc.player.position(),
                    String.join(" | ", DebugOverlay.statusLines()));
            ShadersCompanion.LOGGER.info("Shaders Companion dev run timing with the mod on: {}", frameTiming());
            if (!TOGGLE.isEmpty() && !city && !shadows) {
                // Timing rounds with the option off, on, off, on..., each with a screenshot
                timingRound = 0;
                setPackOption(false);
                phase = 4;
                phaseTick = ticks;
            } else if (city) {
                // Timing rounds with one setting changed at a time (CITY_ROUNDS), then the usual shot with the mod off
                timingRound = 0;
                cityRound(0);
                phase = 5;
                phaseTick = ticks;
            } else if (shadows) {
                // Four timing rounds first: the mirror view's shadows off, on, off, on
                Config.MIRROR_SHADOWS.set(false);
                timingRound = 0;
                phase = 3;
                phaseTick = ticks;
            } else if (pool || glass || ocean) {
                Config.ENABLED.set(false);
                phase = 2;
                phaseTick = ticks;
            } else {
                finish(mc);
            }
        } else if (phase == 3 && ticks - phaseTick >= TIMING_TICKS) {
            boolean on = Config.MIRROR_SHADOWS.get();
            ShadersCompanion.LOGGER.info("Shaders Companion dev run timing with the mirror view's shadows {}: {}", on ? "on" : "off", frameTiming());
            phaseTick = ticks;
            if (++timingRound < 4) {
                Config.MIRROR_SHADOWS.set(!on);
            } else {
                Config.ENABLED.set(false);
                phase = 2;
            }
        } else if (phase == 5 && ticks - phaseTick >= 40 + TIMING_TICKS) {
            String[] lines = DebugOverlay.statusLines();
            ShadersCompanion.LOGGER.info("Shaders Companion dev run timing, city round {} ({}): {} | {}", timingRound, CITY_ROUNDS[timingRound], frameTiming(),
                    lines.length > 3 ? lines[1] + " | " + lines[3] : "");
            phaseTick = ticks;
            if (++timingRound < CITY_ROUNDS.length) {
                cityRound(timingRound);
            } else {
                cityRound(-1);
                Config.ENABLED.set(false);
                phase = 2;
            }
        } else if (phase == 4 && ticks - phaseTick >= TOGGLE_SETTLE_TICKS + TIMING_TICKS) {
            boolean on = timingRound % 2 == 1;
            Screenshot.grab(mc.gameDirectory, prefix + "-" + TOGGLE + "-" + (on ? "on" : "off") + timingRound + ".png", mc.getMainRenderTarget(), message -> { });
            String[] lines = DebugOverlay.statusLines();
            ShadersCompanion.LOGGER.info("Shaders Companion dev run timing with {} {}: {} | {}", TOGGLE, on ? "on" : "off", frameTiming(),
                    lines.length > 3 ? lines[1] + " | " + lines[3] : "");
            phaseTick = ticks;
            if (++timingRound < TOGGLE_ROUNDS) {
                setPackOption(!on);
            } else {
                finish(mc);
            }
        } else if (phase == 2 && ticks - phaseTick >= 40) {
            Screenshot.grab(mc.gameDirectory, prefix + "-off.png", mc.getMainRenderTarget(), message -> { });
            ShadersCompanion.LOGGER.info("Shaders Companion dev run timing with the mod off: {}", frameTiming());
            Config.ENABLED.set(true);
            finish(mc);
        }
    }

    /** Logs what the overlay scene's container shows: the held item, the block and its block entity. */
    private static void logOverlayContainer(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        BlockPos container = mc.player.blockPosition().offset(0, 0, 5);
        var blockEntity = mc.level.getBlockEntity(container);
        ShadersCompanion.LOGGER.info("Shaders Companion dev run, overlay: holding {}, at {} {} {}", mc.player.getMainHandItem(), container,
                mc.level.getBlockState(container), blockEntity == null ? "no block entity" : blockEntity.saveWithoutMetadata());
    }

    /** The city scene's timing rounds: what each changes from the defaults. */
    private static final String[] CITY_ROUNDS = {"defaults", "mirror off", "mirror at 50%", "entities off", "entity LOD off", "defaults again", "entity LOD off",
            "defaults again"};

    /** Applies a city timing round's settings (-1: back to the defaults). */
    private static void cityRound(int round) {
        Config.MIRROR.set(round != 1);
        Config.MIRROR_SCALE.set(round == 2 ? 50 : 100);
        Config.ENTITIES.set(round != 3);
        Config.BLOCK_ENTITIES.set(round != 3);
        Config.ENTITY_LOD.set(round < 0 || !CITY_ROUNDS[round].equals("entity LOD off"));
    }

    /**
     * Sets the shader pack's on/off option named by -PdevToggle and reloads the pack; "config.<name>" sets the mod's own
     * boolean setting of that name instead.
     */
    private static void setPackOption(boolean on) {
        if (TOGGLE.startsWith("config.")) {
            String name = TOGGLE.substring("config.".length());
            for (java.lang.reflect.Field field : Config.class.getFields()) {
                try {
                    if (field.get(null) instanceof net.minecraftforge.common.ForgeConfigSpec.BooleanValue value && value.getPath().get(value.getPath().size() - 1).equals(name)) {
                        value.set(on);
                        return;
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: no setting {}", name);
            return;
        }
        try {
            Iris.getShaderPackOptionQueue().put(TOGGLE, Boolean.toString(on));
            Iris.reload();
        } catch (Exception e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: setting {} failed", TOGGLE, e);
        }
    }

    private static void finish(Minecraft mc) {
        if (CubeView.lodRangeForChecks() > 0 || System.getProperty("shaderscompanion.devDh") != null) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: Distant Horizons range {}", CubeView.lodRangeForChecks());
        }
        phase = -1;
        ShadersCompanion.LOGGER.info("Shaders Companion dev run done");
        mc.stop();
    }
}
