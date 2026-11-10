package com.rinkynooble.shaderscompanion.verify;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.Config;
import com.rinkynooble.shaderscompanion.client.CubeDump;
import com.rinkynooble.shaderscompanion.client.CubeView;
import com.rinkynooble.shaderscompanion.client.DebugOverlay;
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
 * (-PdevPitch, 5 degrees down by default here; -PdevYaw, by default the way the ocean runs farthest), screenshotted with the mod drawing ("-on") and turned off ("-off").
 * With Distant Horizons (-PdevDh -PdevWait=1400) the far water is LOD water: distant water should look the same in
 * both shots, with no stripes.</li>
 * </ul>
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
    private static final int START_FRAME = 60;
    private static final String WORLD = "shaderscompanion-dev";
    private static boolean worldRequested;
    private static int phase;
    private static int phaseTick;
    private static int ticks;
    private static String prefix = "devrun";
    // Frame timing: GPU time between timestamps at the start and end of each frame (read 4 frames later), and the
    // time between frame starts
    private static final int TIMED_FRAMES = 120;
    private static final int[] frameQueries = new int[8];
    private static final double[] gpuMillis = new double[TIMED_FRAMES];
    private static final double[] frameMillis = new double[TIMED_FRAMES];
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
        return String.format("frame %.2f ms, GPU %.2f ms (medians of the last %d frames)", median(frameMillis), median(gpuMillis), Math.min(framesTimed, TIMED_FRAMES));
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
        boolean glass = mirror || corner || grass || SCENE.equals("match");
        boolean ocean = SCENE.equals("ocean");
        boolean match = SCENE.equals("match");
        if (phase == 0 && mc.player != null && CubeView.framesDrawn() >= START_FRAME) {
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
                    server.overworld().setBlock(new BlockPos(at.getX(), 63, at.getZ()), Blocks.BARRIER.defaultBlockState(), 3);
                    mc.tell(() -> mc.player.connection.sendCommand("tp @s " + (at.getX() + 0.5) + " 64 " + (at.getZ() + 0.5) + " " + finalYaw + " " + pitch));
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
        } else if (phase == 1 && grass && ticks - phaseTick == WAIT_TICKS - 6) {
            Screenshot.grab(mc.gameDirectory, "shaderscompanion-grass-" + System.currentTimeMillis() + "-on-early.png", mc.getMainRenderTarget(), message -> { });
        } else if (phase == 1 && (pool || mobs || corner || grass || ocean || match ? ticks - phaseTick >= WAIT_TICKS : CubeView.framesDrawn() >= START_FRAME + DUMP_FRAMES)) {
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
                Screenshot.grab(mc.gameDirectory, prefix + (pool || glass || ocean ? "-on.png" : "-main.png"), mc.getMainRenderTarget(), message -> { });
            } catch (Exception e) {
                ShadersCompanion.LOGGER.error("Shaders Companion dev run: saving failed", e);
            }
            ShadersCompanion.LOGGER.info("Shaders Companion dev run at {}: {}", mc.player == null ? "?" : mc.player.position(),
                    String.join(" | ", DebugOverlay.statusLines()));
            ShadersCompanion.LOGGER.info("Shaders Companion dev run timing with the mod on: {}", frameTiming());
            if (pool || glass || ocean) {
                Config.ENABLED.set(false);
                phase = 2;
                phaseTick = ticks;
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

    private static void finish(Minecraft mc) {
        if (CubeView.lodRangeForChecks() > 0 || System.getProperty("shaderscompanion.devDh") != null) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: Distant Horizons range {}", CubeView.lodRangeForChecks());
        }
        phase = -1;
        ShadersCompanion.LOGGER.info("Shaders Companion dev run done");
        mc.stop();
    }
}
