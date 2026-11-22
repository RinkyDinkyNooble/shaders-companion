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
 * <li>-PdevScene=hand: on foot on a white floor before a white wall 2 blocks ahead (with a quartz block and a slab), a
 * held item (a TaCZ AK-47 with -PdevTacz, else an iron sword); with -PdevShowHand the hand and HUD stay drawn, and
 * -PdevStrafe -PdevBurst=N -PdevToggle=... walks left during each burst so the item sways over the wall and floor (for
 * ambient occlusion around the hand); -PdevOutlineBlock=minecraft:cobweb places that block just ahead, before the wall
 * (for the selection outline; "a|b": a there and b on top of it); -PdevHold=&lt;item&gt; and -PdevHold2=&lt;item&gt; put those
 * in the main and off hand (held items' material classes); -PdevHandRoom closes the scene in at night, lit by lanterns.</li>
 * <li>-PdevScene=bathroom -PdevFurniture: the hand scene's flow in a room open behind the camera, with Modern Furniture
 * Forked's bathtub, sink cabinet and wall-hung sink along the back wall (the fine shade between parts of a block).</li>
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
 * both shots, with no stripes. -PdevImport=&lt;file&gt; places a scan of the user's world there instead ("dx y dz state"
 * per line, heights as in their world, so its water lines up with the ocean's) with the camera's feet at -PdevCamX,
 * -PdevCamY (height) and -PdevCamZ, flying and held at -PdevYaw (0 to 359) and -PdevPitch. -PdevTrunk adds a walkway
 * and a cherry tree just ahead (with -PdevStrafe, the view behind its trunk opens up during each burst).</li>
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
 * returns: shots "shaderscompanion-boat-&lt;-PdevTag&gt;-before", "-riding", "-after-early", "-pan1" to "-pan4" (turning
 * right after leaving), "-after", then
 * "-after-noentities" (the mod's entities off), "-after-off" (the mod off) and "-after-on". With -PdevRain the scene
 * moves to the nearest deep ocean, where it rains. Every shot must look the same with the mod on and off apart from
 * reflections.
 * <p>
 * -PdevScene=raincover -PdevRain: a smooth stone floor on the ocean, half under a glass roof, in the rain (with
 * -PdevToggle=COMPANION_RAIN_COVER for the pack's rain cover off and on): only the open half should be wet and rippled.
 * <p>
 * -PdevRain: rain from 30 ticks into any scene (it doesn't rain where the world starts; the ocean scene's biome has
 * rain).
 * <p>
 * -PdevScene=meadow: a lawn with patches of tall grass and flowers, shots every 60 degrees in the morning, at noon and in
 * the afternoon ("shaderscompanion-meadow-&lt;-PdevTag&gt;-t&lt;time&gt;-y&lt;yaw&gt;.png"); -PdevReloads=N reloads the shader pack N
 * times first. For plants drawn wrongly. With -PdevToggle the compass shots give way to the usual toggle rounds.
 * <p>
 * -PdevScene=street: at y 150, a shop front on the camera's left running ahead (+Z): grey pillars every 5 blocks with
 * light grey stained glass set a block back between them over white sills, a lit room with brick walls behind, and a
 * striped tower with lamps across the street on the right. The camera stands beside the glass looking along it
 * (-PdevYaw, -15 by default; -PdevCamX and -PdevCamZ, 1.0 and 0.5, place it), like a player walking past a shop window
 * (-PdevShallow: pillars a block thinner, so the next bays show past them at a grazing angle);
 * shots "-on" and "-off". -PdevImport=&lt;file&gt; also places the blocks listed in a file ("dx dy dz state" per line, from a
 * scan of the user's world) at -PdevImportAt (from the base, "-8,0,-30" by default); -PdevCamY lifts the camera.
 * <p>
 * -PdevScene=darkspot: the meadow, then the client's sky light set to 0 at a few spots ahead (plants, and air over the
 * lawn), as in worlds whose light data is wrong in spots; shots "shaderscompanion-darkspot-&lt;-PdevTag&gt;-dark" and,
 * after the client relights those spots, "-fixed". With the pack's open-sky light the two should match.
 * <p>
 * -PdevScene=lodmirror: a black glass wall 4 blocks ahead, 30 blocks up (-PdevLift), so the glass shows the land behind
 * the camera out to Distant Horizons' LODs (-PdevDh); -PdevTime, -PdevCycle, -PdevYaw, -PdevPitch.
 * <p>
 * -PdevShadersLate=N: the world loads with Oculus's shaders off and they come on N ticks in (Distant Horizons first,
 * then shaders); the scene follows as usual.
 * <p>
 * -PdevScene=corridor: a closed corridor at night like the user's (2026-11-21, V1 to v5): a light grey tile floor, grey
 * walls with brick partitions on both sides, a dark ceiling with light panels every 5 blocks, looking along it
 * (-PdevPitch, 10 by default; 60 looks down at the floor with the panels off screen). With -PdevBlockz the user's Better
 * Blockz blocks (floor cray_blockz_0, walls mint_blockz_8, ceiling vect_blockz_4, panels cyberlight_blockz_0: all
 * but the panels the mod's metal class); without it vanilla stand-ins (smooth stone, grey concrete, black concrete,
 * sea lanterns). -PdevCorridorFloor and -PdevCorridorLamp override the floor and panel blocks; -PdevCorridorSmall
 * puts single-block lamps every 3 blocks instead of the panels. The corridor is 5 blocks wide and the camera stands 1.5
 * blocks right of its middle (at a block's centre, the same in every run), so -PdevStrafe has room. -PdevCorridorEnd=&lt;block&gt; sets two strips of that block 2
 * wide and 4 high in the far wall (glowstone for the bright end of the user's store aisle, staticish.png).
 * <p>
 * -PdevScene=materials: a row of vanilla blocks the material system gives classes on a smooth stone floor in the
 * morning sun. -PdevRow=a;b;c places single blocks of those states instead (modded machines: six to a row, each row a
 * step higher, on stone, seen from just behind the first); -PdevProbe=mekanism,superbwarfare writes the inference's
 * evidence for every block of those mods to research/materials-probe.tsv (MaterialsProbe).
 * <p>
 * -PdevScene=bright: a platform of white concrete, grass and stone bricks with a white wall, a quartz pillar and a
 * black glass wall, screenshotted at midnight and at noon, each with the mod drawing and turned off
 * ("shaderscompanion-bright-&lt;-PdevTag&gt;-night-on.png" and so on): for comparing the brightness of shader pack versions.
 * <p>
 * -PdevScene=stairwall: a closed sandstone room at noon, lit by a lantern behind the camera, its far wall half oak
 * stairs (flat backs to the room, open steps to the sunlit outside) and half sandstone; with
 * -PdevToggle=config.lightLeakFix the floor, ceiling and side wall along the stairs stay as dark as along the sandstone
 * in the "on" rounds.
 * <p>
 * -PdevScene=doorwall: the stairwall room with closed doors (an oak door with windows and a spruce door) in its far wall
 * and a closed trapdoor in its ceiling, sunlit outside and a lantern beside the doors outside (-PdevTime=18000 for night);
 * with -PdevToggle=config.lightLeakFix the room stays as dark as with a solid wall in the "on" rounds.
 * -PdevDoorwallBlock=minecraft:iron_block for metal walls, floor and ceiling, -PdevDoorwallDark for no lantern inside,
 * -PdevDoorwallSolid for the wall block in place of the doors and the trapdoor (the reference).
 * <p>
 * -PdevScene=exposure: a lit block of town (white buildings with glowstone, a lime lawn with sea lanterns) from above
 * at -PdevTime (18000, midnight), a shot every -PdevEvery ticks (200), -PdevShots of them (13), with the time of day
 * logged; -PdevCycle lets the time run, -PdevStep=500 sets the time that much later before each next shot;
 * -PdevNightVision=true gives the player night vision (any scene). For exposure that drifts over minutes or with the time of day.
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

    static {
        // -PdevLeakLog=N: log the first N light samples the light leak fix leaves out
        // (with -PdevToggle=config.lightLeakFix, those of each "on" round's rebuild instead)
        if (!System.getProperty("shaderscompanion.devToggle", "").startsWith("config.lightLeakFix")) {
            com.rinkynooble.shaderscompanion.client.LightLeaks.logBudget.set(Integer.getInteger("shaderscompanion.devLeakLog", 0));
        }
    }
    private static final int PITCH = Integer.getInteger("shaderscompanion.devPitch", 50);
    private static final int WAIT_TICKS = Integer.getInteger("shaderscompanion.devWait", 160);
    /**
     * A shader pack on/off option, or config.<name> for the mod's own, to time in alternating rounds (-PdevToggle);
     * NAME:a:b switches an option with values between a ("off" rounds) and b ("on" rounds); NAME:a:b:c... takes each
     * value in turn (shots named "-v<value>"), every value twice.
     */
    private static final String TOGGLE_SPEC = System.getProperty("shaderscompanion.devToggle", "");
    private static final String TOGGLE = TOGGLE_SPEC.split(":")[0];
    private static final String[] TOGGLE_VALUES = TOGGLE_SPEC.contains(":") ? TOGGLE_SPEC.substring(TOGGLE.length() + 1).split(":") : null;
    private static final int TOGGLE_ROUNDS = TOGGLE_VALUES != null && TOGGLE_VALUES.length > 2 ? 2 * TOGGLE_VALUES.length : 6;
    /** Shots per toggle round, on consecutive ticks (-PdevBurst). */
    private static final int BURST = Integer.getInteger("shaderscompanion.devBurst", 1);
    /**
     * -PdevPan=D: the camera turns D degrees a tick during each round's burst (from the same yaw every round, smoothly
     * between ticks), with the mirror's state logged every tick: for flicker while the view moves.
     */
    private static final float PAN = Float.parseFloat(System.getProperty("shaderscompanion.devPan", "0"));
    /** -PdevPanTicks=K: the camera turns only during the first K ticks of each burst, then holds still (settling after a turn). */
    private static final int PAN_TICKS = Integer.getInteger("shaderscompanion.devPanTicks", Integer.MAX_VALUE);
    /** -PdevMeadowBlocks: the meadow with blocks, glowstone and a tree instead of plants. */
    private static final boolean MEADOW_BLOCKS = Boolean.getBoolean("shaderscompanion.devMeadowBlocks");
    /** -PdevStrafe: the player walks left during each round's burst, from the same spot every round. */
    private static final boolean STRAFE = Boolean.getBoolean("shaderscompanion.devStrafe");
    /** -PdevShowHand: the hand, held item and HUD stay drawn once the scene is built (scenes hide them by default). */
    private static final boolean SHOW_HAND = Boolean.getBoolean("shaderscompanion.devShowHand");
    private static float panBaseYaw = Float.NaN;
    private static net.minecraft.world.phys.Vec3 panBasePos;
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
    /**
     * -PdevShadersLate=N: the world loads with shaders off (Distant Horizons draws its LODs on its own) and Oculus turns
     * them on N ticks in; the scene follows as usual.
     */
    private static final int SHADERS_LATE = Integer.getInteger("shaderscompanion.devShadersLate", 0);
    private static boolean shadersOn = SHADERS_LATE <= 0;
    private static int lateTicks;
    /** Ticks in the world without the mod drawing before a run gives up and logs why. */
    private static final int IDLE_TICKS = 300;
    private static String prefix = "devrun";
    /** The boat scene's vehicle (-PdevBoat; Superb Warfare's speed boat needs -PdevSbw). */
    private static final String BOAT = System.getProperty("shaderscompanion.devBoat", "superbwarfare:speedboat");
    private static net.minecraft.world.phys.Vec3 boatStand;
    private static float boatYaw;
    private static float boatPitch;
    private static volatile String boatSummon;
    /** The ocean scene's camera command with -PdevImport (null otherwise). */
    private static volatile String importCamera;
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
    private static boolean frameStarted;

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
        if (SHADERS_LATE > 0) {
            setShaders(mc, false);
        }
        mc.tell(() -> {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: creating world {}", WORLD);
            // Spawn radius 0: the player starts on the world's spawn point, not up to 10 blocks off it at random, so
            // every run builds its scene in the same place and shots of two runs can be compared pixel by pixel
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_SPAWN_RADIUS).set(0, null);
            LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
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
            frameStarted = true;
            PassProfiler.beginFrame();
        } else if (frameStarted) {
            // Only a frame whose start was timed (the first event can be a frame's end)
            PassProfiler.endFrame();
            GL33C.glQueryCounter(frameQueries[slot * 2 + 1], GL33C.GL_TIMESTAMP);
            frameStarted = false;
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
        PassProfiler.log(TOGGLE.isEmpty() ? SCENE : TOGGLE + " round " + timingRound);
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
        if (SHOW_HAND && phase >= 1) {
            mc.options.hideGui = false;
        }
        if (Boolean.getBoolean("shaderscompanion.devTaczAim") && phase >= 1) {
            // -PdevTaczAim: TaCZ's aim key held from the scene on (aiming down the sights)
            for (net.minecraft.client.KeyMapping key : mc.options.keyMappings) {
                if (key.getName().equals("key.tacz.aim.desc")) {
                    key.setDown(true);
                }
            }
        }
        if (!shadersOn) {
            // -PdevShadersLate: shaders come on once the world has run that long without them
            if (mc.player != null && ++lateTicks >= SHADERS_LATE) {
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-late-" + System.getProperty("shaderscompanion.devTag", "run") + "-before.png",
                        mc.getMainRenderTarget(), message -> { });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: turning shaders on after {} ticks: {}", lateTicks,
                        String.join(" | ", DebugOverlay.statusLines()));
                setShaders(mc, true);
                shadersOn = true;
                worldTicks = 0;
            }
            return;
        }
        boolean pool = SCENE.equals("pool");
        boolean bathroom = SCENE.equals("bathroom");
        boolean showroom = SCENE.equals("showroom");
        boolean hand = SCENE.equals("hand") || bathroom || showroom;
        boolean mirror = SCENE.equals("mirror");
        boolean mobs = SCENE.equals("mobs") || mirror;
        boolean corner = SCENE.equals("corner");
        boolean grass = SCENE.equals("grass");
        boolean shadows = SCENE.equals("shadows");
        boolean bright = SCENE.equals("bright");
        boolean exposure = SCENE.equals("exposure");
        boolean move = SCENE.equals("move");
        boolean boat = SCENE.equals("boat");
        boolean aquarium = SCENE.equals("aquarium");
        boolean overlay = SCENE.equals("overlay");
        boolean turn = SCENE.equals("turn");
        boolean city = SCENE.equals("city");
        boolean reflectPool = SCENE.equals("reflectpool");
        boolean facade = SCENE.equals("facade") || SCENE.equals("lake");
        boolean lake = SCENE.equals("lake");
        boolean glassBox = SCENE.equals("glassbox");
        boolean skyRoom = SCENE.equals("skyroom");
        boolean enclosure = SCENE.equals("enclosure");
        boolean stairWall = SCENE.equals("stairwall");
        boolean doorWall = SCENE.equals("doorwall");
        boolean store = SCENE.equals("store");
        boolean lampGlass = SCENE.equals("lampglass");
        boolean corridor = SCENE.equals("corridor");
        boolean street = SCENE.equals("street");
        boolean darkSpot = SCENE.equals("darkspot");
        boolean meadow = SCENE.equals("meadow") || darkSpot;
        boolean lodMirror = SCENE.equals("lodmirror");
        boolean glass = mirror || corner || grass || shadows || aquarium || overlay || city || reflectPool || facade || glassBox || skyRoom || enclosure || lodMirror || street || store || lampGlass || corridor || SCENE.equals("match") || SCENE.equals("materials");
        boolean shore = SCENE.equals("shore");
        boolean ocean = SCENE.equals("ocean") || shore;
        boolean match = SCENE.equals("match");
        boolean materials = SCENE.equals("materials");
        boolean rainCover = SCENE.equals("raincover");
        if (phase == 1 && importCamera != null && mc.player != null && (ticks - phaseTick == 300 || ticks - phaseTick == 306 || ticks - phaseTick == 312)) {
            // The ocean scene's -PdevImport camera, 15 seconds in (after the import): in the air after a teleport, flying
            // holds (on the ground the client turns it off at once), and a second teleport puts the camera back
            if (ticks - phaseTick == 306) {
                mc.player.getAbilities().flying = true;
                mc.player.onUpdateAbilities();
            } else {
                mc.player.connection.sendCommand(importCamera);
            }
        }
        if (importCamera != null && mc.player != null && (phase > 1 && PAN == 0.0F && !STRAFE || phase == 1 && ticks - phaseTick > 312)) {
            // Then held still, as the window takes mouse input while it has the focus (-PdevPan and -PdevStrafe turn
            // and move it in the toggle rounds)
            String[] pose = importCamera.split(" ");
            float yaw = Float.parseFloat(pose[5]);
            float pitch = Float.parseFloat(pose[6]);
            mc.player.setYRot(yaw);
            mc.player.yRotO = yaw;
            mc.player.setXRot(pitch);
            mc.player.xRotO = pitch;
        }
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
            } else if (showroom) {
                // Every block state of a mod (-PdevNamespace, mdm by default) on a white floor, 2 blocks apart, the camera
                // -PdevLift (20) blocks above the middle
                mc.options.hideGui = true;
                mc.player.getAbilities().flying = true;
                mc.player.onUpdateAbilities();
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear", "gamerule doDaylightCycle false",
                        "gamerule doMobSpawning false"}) {
                    mc.player.connection.sendCommand(command);
                }
                MinecraftServer server = mc.getSingleplayerServer();
                BlockPos origin = mc.player.blockPosition().above(30);
                String namespace = System.getProperty("shaderscompanion.devNamespace", "mdm");
                int lift = Integer.getInteger("shaderscompanion.devLift", 20);
                server.execute(() -> {
                    java.util.List<net.minecraft.world.level.block.state.BlockState> states = new java.util.ArrayList<>();
                    for (var entry : net.minecraftforge.registries.ForgeRegistries.BLOCKS.getEntries()) {
                        if (entry.getKey().location().getNamespace().equals(namespace)) {
                            states.addAll(entry.getValue().getStateDefinition().getPossibleStates());
                        }
                    }
                    int side = (int) Math.ceil(Math.sqrt(states.size()));
                    var world = server.overworld();
                    for (int x = -side - 2; x <= side + 2; x++) {
                        for (int z = -side - 2; z <= side + 2; z++) {
                            world.setBlock(origin.offset(x, -1, z), Blocks.WHITE_CONCRETE.defaultBlockState(), 2 | 16);
                        }
                    }
                    for (int i = 0; i < states.size(); i++) {
                        world.setBlock(origin.offset((i % side) * 2 - side, 0, (i / side) * 2 - side), states.get(i), 2 | 16);
                    }
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the showroom scene, {} block states of {}", states.size(), namespace);
                    server.getPlayerList().getPlayers().get(0).teleportTo(world, origin.getX() + 0.5, origin.getY() + lift, origin.getZ() + 0.5, 0.0F, PITCH);
                });
            } else if (bathroom) {
                // -PdevFurniture: a room open behind the camera (beige walls, white floor and ceiling), Modern Furniture
                // Forked's white bathtub along the back wall on the left, its sink cabinet and a wall-hung sink on the right.
                // -PdevNamespace=mdm: every block state of that mod 40 blocks below first, so the room's shapes are not
                // the first the lattice's detail sees
                if (System.getProperty("shaderscompanion.devNamespace") != null) {
                    MinecraftServer server = mc.getSingleplayerServer();
                    BlockPos below = mc.player.blockPosition().above(30 - 40);
                    String namespace = System.getProperty("shaderscompanion.devNamespace");
                    server.execute(() -> {
                        java.util.List<net.minecraft.world.level.block.state.BlockState> states = new java.util.ArrayList<>();
                        for (var entry : net.minecraftforge.registries.ForgeRegistries.BLOCKS.getEntries()) {
                            if (entry.getKey().location().getNamespace().equals(namespace)) {
                                states.addAll(entry.getValue().getStateDefinition().getPossibleStates());
                            }
                        }
                        int side = (int) Math.ceil(Math.sqrt(states.size()));
                        for (int i = 0; i < states.size(); i++) {
                            server.overworld().setBlock(below.offset((i % side) - side / 2, 0, (i / side) - side / 2), states.get(i), 2 | 16);
                        }
                    });
                }
                mc.options.hideGui = true;
                mc.player.getAbilities().flying = false;
                mc.player.onUpdateAbilities();
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear", "gamerule doDaylightCycle false",
                        "gamerule doMobSpawning false", "tp @s ~ ~30 ~ 0 " + PITCH,
                        "fill ~-6 ~-1 ~-6 ~6 ~-1 ~6 minecraft:white_concrete", "fill ~-6 ~ ~-6 ~6 ~5 ~6 minecraft:air",
                        "fill ~-5 ~ ~3 ~5 ~4 ~3 minecraft:smooth_sandstone",
                        "fill ~-5 ~ ~-2 ~-5 ~4 ~3 minecraft:smooth_sandstone", "fill ~5 ~ ~-2 ~5 ~4 ~3 minecraft:smooth_sandstone",
                        "fill ~-5 ~5 ~-2 ~5 ~5 ~3 minecraft:white_concrete",
                        "setblock ~-1 ~ ~2 mdm:white_bathtub[facing=east,part=main]", "setblock ~-2 ~ ~2 mdm:white_bathtub[facing=east,part=back]",
                        "setblock ~1 ~ ~2 mdm:bathroom_sink_with_shelf[facing=north]", "setblock ~2 ~ ~2 mdm:bathroom_sink[facing=north]",
                        "item replace entity @s weapon.mainhand with minecraft:air"}) {
                    mc.player.connection.sendCommand(command);
                }
                int up = Integer.getInteger("shaderscompanion.devLift", 0);
                if (up > 0) {
                    // -PdevLift=N: the camera that many blocks higher, flying (to look into the bathtub)
                    mc.player.getAbilities().flying = true;
                    mc.player.onUpdateAbilities();
                    mc.player.connection.sendCommand("tp @s ~ ~" + up + " ~ 0 " + PITCH);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the bathroom scene");
            } else if (hand) {
                // A white floor and a white wall 2 blocks ahead with a quartz block and a slab against it, on foot, a held item
                // (a TaCZ gun with -PdevTacz); with -PdevShowHand -PdevStrafe the item sways over the wall and floor
                mc.options.hideGui = true;
                mc.player.getAbilities().flying = false;
                mc.player.onUpdateAbilities();
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear", "gamerule doDaylightCycle false",
                        "gamerule doMobSpawning false", "tp @s ~ ~30 ~ " + Integer.getInteger("shaderscompanion.devYaw", 0) + " " + PITCH,
                        "fill ~-14 ~-1 ~-6 ~14 ~-1 ~6 minecraft:white_concrete", "fill ~-14 ~ ~-6 ~14 ~5 ~6 minecraft:air",
                        "fill ~-14 ~ ~2 ~14 ~4 ~2 minecraft:white_concrete",
                        "setblock ~1 ~ ~1 minecraft:quartz_block", "setblock ~-1 ~ ~1 minecraft:smooth_quartz_slab",
                        "setblock ~ ~ ~1 " + System.getProperty("shaderscompanion.devOutlineBlock", "minecraft:air").split("[|]")[0],
                        "setblock ~ ~1 ~1 " + (System.getProperty("shaderscompanion.devOutlineBlock", "").contains("|")
                                ? System.getProperty("shaderscompanion.devOutlineBlock").split("[|]")[1] : "minecraft:air"),
                        Boolean.getBoolean("shaderscompanion.devTacz")
                                ? "item replace entity @s weapon.mainhand with tacz:modern_kinetic_gun{GunId:\"" + System.getProperty("shaderscompanion.devTaczGun", "tacz:ak47") + "\""
                                        + (System.getProperty("shaderscompanion.devTaczSight", "").isEmpty() ? ""
                                        : ",AttachmentSCOPE:{id:\"tacz:attachment\",Count:1b,tag:{AttachmentId:\"" + System.getProperty("shaderscompanion.devTaczSight") + "\"}}") + "}"
                                : "item replace entity @s weapon.mainhand with " + System.getProperty("shaderscompanion.devHold", "minecraft:iron_sword"),
                        "item replace entity @s weapon.offhand with " + System.getProperty("shaderscompanion.devHold2", "minecraft:air")}) {
                    mc.player.connection.sendCommand(command);
                }
                if (Boolean.getBoolean("shaderscompanion.devHandRoom")) {
                    // -PdevHandRoom: walls and a ceiling all round, at night, lit by two hanging lanterns and a glowstone
                    // block in the floor beside the wall
                    for (String command : new String[]{
                            "time set 18000", "fill ~-6 ~4 ~-6 ~6 ~4 ~6 minecraft:white_concrete", "fill ~-6 ~ ~-6 ~-6 ~3 ~6 minecraft:white_concrete",
                            "fill ~6 ~ ~-6 ~6 ~3 ~6 minecraft:white_concrete", "fill ~-6 ~ ~-6 ~6 ~3 ~-6 minecraft:white_concrete",
                            "setblock ~-2 ~3 ~ minecraft:lantern[hanging=true]", "setblock ~2 ~3 ~-1 minecraft:lantern[hanging=true]",
                            "setblock ~2 ~-1 ~1 minecraft:glowstone"}) {
                        mc.player.connection.sendCommand(command);
                    }
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the hand scene");
            } else if (ocean) {
                mc.options.hideGui = true;
                for (String command : new String[]{"time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear", "gamerule doDaylightCycle false"}) {
                    mc.player.connection.sendCommand(command);
                }
                MinecraftServer server = mc.getSingleplayerServer();
                BlockPos from = mc.player.blockPosition();
                int pitch = Integer.getInteger("shaderscompanion.devPitch", shore ? 14 : 5);
                int fixedYaw = Integer.getInteger("shaderscompanion.devYaw", shore ? 0 : -1);
                int lift = Integer.getInteger("shaderscompanion.devLift", shore ? 40 : 0);
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
                    if (!System.getProperty("shaderscompanion.devImport", "").isEmpty()) {
                        // -PdevImport: a scan of the user's world with its own heights ("dx y dz state" per line), so its
                        // water lines up with the ocean's; the camera's feet at -PdevCamX, -PdevCamY (height), -PdevCamZ
                        importBlocks(server.overworld(), new BlockPos(at.getX(), 0, at.getZ()));
                        // Sent again in phase 1 around turning flying on, which landing during the import turned off
                        importCamera = "tp @s " + (at.getX() + Double.parseDouble(System.getProperty("shaderscompanion.devCamX", "0.5")))
                                + " " + System.getProperty("shaderscompanion.devCamY", "64") + " " + (at.getZ() + Double.parseDouble(System.getProperty("shaderscompanion.devCamZ", "0.5")))
                                + " " + finalYaw + " " + pitch;
                        mc.tell(() -> mc.player.connection.sendCommand(importCamera));
                        return;
                    }
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
                    if (shore) {
                        buildShore(server.overworld(), at, lift);
                    }
                    if (Boolean.getBoolean("shaderscompanion.devTrunk")) {
                        buildTrunk(server.overworld(), at, lift, finalYaw);
                    }
                    mc.tell(() -> mc.player.connection.sendCommand("tp @s " + (at.getX() + 0.5) + " " + (64 + lift) + " " + (at.getZ() + 0.5) + " " + finalYaw + " " + pitch));
                });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the ocean scene");
            } else if (rainCover) {
                // A smooth stone floor on the nearest deep ocean (it rains there, with -PdevRain), its left half under a
                // glass roof (plain glass near, light grey stained glass far), its right half open
                mc.options.hideGui = true;
                for (String command : new String[]{"time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "gamerule doDaylightCycle false",
                        "gamerule doMobSpawning false"}) {
                    mc.player.connection.sendCommand(command);
                }
                MinecraftServer server = mc.getSingleplayerServer();
                BlockPos from = mc.player.blockPosition();
                server.execute(() -> {
                    var found = server.overworld().findClosestBiome3d(biome -> biome.is(BiomeTags.IS_DEEP_OCEAN), from, 6400, 32, 64);
                    BlockPos at = found == null ? from : found.getFirst();
                    for (int dx = -8; dx <= 8; dx++) {
                        for (int dz = 0; dz <= 16; dz++) {
                            server.overworld().setBlock(new BlockPos(at.getX() + dx, 64, at.getZ() + dz), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                            if (dx >= 1) {
                                server.overworld().setBlock(new BlockPos(at.getX() + dx, 69, at.getZ() + dz),
                                        (dz < 8 ? Blocks.GLASS : Blocks.LIGHT_GRAY_STAINED_GLASS).defaultBlockState(), 3);
                            }
                        }
                    }
                    server.overworld().setBlock(new BlockPos(at.getX(), 66, at.getZ() - 4), Blocks.BARRIER.defaultBlockState(), 3);
                    mc.tell(() -> mc.player.connection.sendCommand("tp @s " + (at.getX() + 0.5) + " 67 " + (at.getZ() - 3.5) + " 0 "
                            + Integer.getInteger("shaderscompanion.devPitch", 20)));
                });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the raincover scene");
            } else if (mobs) {
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear", "gamerule doDaylightCycle false",
                        "gamerule doMobSpawning false", "difficulty easy", "tp @s ~ ~20 ~ 0 10",
                        "fill ~-10 ~-1 ~-10 ~10 ~-1 ~10 minecraft:smooth_stone", "fill ~-10 ~ ~-10 ~10 ~6 ~10 minecraft:air",
                        "summon minecraft:husk ~ ~ ~4 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f]}",
                        "summon minecraft:villager ~ ~ ~-4 {NoAI:1b,PersistenceRequired:1b}",
                        "summon minecraft:pig ~4 ~ ~ {NoAI:1b,PersistenceRequired:1b}",
                        "summon minecraft:iron_golem ~-5 ~ ~ {NoAI:1b,PersistenceRequired:1b}",
                        "summon minecraft:spider ~-2 ~ ~-7 {NoAI:1b,PersistenceRequired:1b}",
                        "setblock ~4 ~ ~2 minecraft:chest[facing=west]", "setblock ~-3 ~ ~-3 minecraft:oak_sign"}) {
                    mc.player.connection.sendCommand(command);
                }
                if (Boolean.getBoolean("shaderscompanion.devEntityMaterials")) {
                    // -PdevEntityMaterials: the husk in iron and gold armour with an iron sword, a minecart, a dropped gold
                    // block and (with -PdevSbw) a Superb Warfare speedboat in front, for the pack's Entity materials
                    mc.options.hideGui = true;
                    for (String command : new String[]{
                            "item replace entity @e[type=minecraft:husk,limit=1,sort=nearest] armor.head with minecraft:iron_helmet",
                            "item replace entity @e[type=minecraft:husk,limit=1,sort=nearest] armor.chest with minecraft:iron_chestplate",
                            "item replace entity @e[type=minecraft:husk,limit=1,sort=nearest] armor.legs with minecraft:golden_leggings",
                            "item replace entity @e[type=minecraft:husk,limit=1,sort=nearest] weapon.mainhand with minecraft:iron_sword",
                            "summon minecraft:minecart ~2 ~ ~4", "summon minecraft:item ~-1.5 ~ ~3 {Item:{id:\"minecraft:gold_block\",Count:1b},PickupDelay:32767,Age:-32768}",
                            net.minecraftforge.fml.ModList.get().isLoaded("superbwarfare") ? "summon superbwarfare:tiny_speedboat ~-3 ~ ~6" : "say no Superb Warfare"}) {
                        mc.player.connection.sendCommand(command);
                    }
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
                } else if (!Boolean.getBoolean("shaderscompanion.devEntityMaterials")) {
                    DebugOverlay.setVisible(true);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: summoned the mobs");
            } else if (match) {
                mc.options.hideGui = true;
                // -PdevMetal: metal, gem and quartz in place of the bricks, logs, wool, leaves and cobblestone, and a chest
                boolean metal = Boolean.getBoolean("shaderscompanion.devMetal");
                for (String command : new String[]{
                        "time set 2000", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ -35 12",
                        "fill ~-10 ~-1 ~-10 ~12 ~-1 ~10 minecraft:smooth_stone", "fill ~-10 ~ ~-10 ~12 ~8 ~10 minecraft:air",
                        "fill ~-6 ~ ~6 ~3 ~4 ~6 minecraft:black_stained_glass",
                        "fill ~6 ~ ~0 ~6 ~3 ~4 minecraft:" + (metal ? "iron_block" : "bricks"), "fill ~5 ~ ~-1 ~5 ~2 ~-1 minecraft:" + (metal ? "gold_block" : "oak_log"),
                        "setblock ~4 ~ ~3 minecraft:" + (metal ? "copper_block" : "white_wool"), "setblock ~4 ~ ~1 minecraft:" + (metal ? "quartz_block" : "red_wool"),
                        "setblock ~4 ~1 ~1 minecraft:" + (metal ? "diamond_block" : "oak_leaves[persistent=true]"),
                        "fill ~3 ~3 ~0 ~6 ~3 ~4 minecraft:oak_planks", "fill ~2 ~ ~-2 ~2 ~1 ~-2 minecraft:" + (metal ? "netherite_block" : "cobblestone"),
                        metal ? "setblock ~1 ~ ~3 minecraft:chest[facing=north]" : "time set 2000"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the match scene");
            } else if (materials) {
                // A row of blocks the material system gives classes (and plain stone between them) on a smooth stone
                // floor in the morning sun, seen at an angle so smooth and metal faces catch the sky and the sun
                mc.options.hideGui = true;
                String[] row = {"minecraft:iron_block", "minecraft:gold_block", "minecraft:copper_block", "minecraft:exposed_copper",
                        "minecraft:netherite_block", "minecraft:diamond_block", "minecraft:glass", "minecraft:ice", "minecraft:slime_block",
                        "minecraft:polished_andesite", "minecraft:quartz_block", "minecraft:white_glazed_terracotta", "minecraft:white_concrete",
                        "minecraft:obsidian", "minecraft:stone"};
                // -PdevRow=a;b;c: single blocks of those states instead (modded machines), six to a row two blocks apart
                // on a stone floor, each row a step higher than the one in front, seen from just behind the first
                String customRow = System.getProperty("shaderscompanion.devRow", "");
                java.util.List<String> commands = new java.util.ArrayList<>(java.util.List.of(
                        "time set 1000", "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        customRow.isEmpty() ? "tp @s ~ ~20 ~ " + Integer.getInteger("shaderscompanion.devYaw", -60) + " " + Integer.getInteger("shaderscompanion.devPitch", 25)
                                : "tp @s ~ ~20 ~ " + Integer.getInteger("shaderscompanion.devYaw", 0) + " " + Integer.getInteger("shaderscompanion.devPitch", 25),
                        "fill ~-4 ~-1 ~-4 ~16 ~-1 ~20 minecraft:" + (customRow.isEmpty() ? "smooth_stone" : "stone"),
                        "fill ~-8 ~ ~-4 ~16 ~6 ~20 minecraft:air"));
                if (!customRow.isEmpty()) {
                    commands.add("fill ~-8 ~-1 ~-4 ~-5 ~-1 ~20 minecraft:stone");
                    String[] blocks = customRow.split(";");
                    for (int i = 0; i < blocks.length; i++) {
                        int line = i / 6;
                        if (line > 0 && i % 6 == 0) {
                            commands.add("fill ~-6 ~ ~" + (5 + line * 3) + " ~6 ~" + (line - 1) + " ~" + (7 + line * 3) + " minecraft:stone");
                        }
                        commands.add("setblock ~" + (-5 + (i % 6) * 2) + " ~" + line + " ~" + (5 + line * 3) + " " + blocks[i]);
                    }
                } else {
                    for (int i = 0; i < row.length; i++) {
                        commands.add("fill ~" + (2 + (i % 5) * 3) + " ~ ~" + (4 + (i / 5) * 4) + " ~" + (3 + (i % 5) * 3) + " ~1 ~" + (5 + (i / 5) * 4) + " " + row[i]);
                    }
                }
                for (String command : commands) {
                    mc.player.connection.sendCommand(command);
                }
                String probe = System.getProperty("shaderscompanion.devProbe", "");
                if (!probe.isEmpty()) {
                    com.rinkynooble.shaderscompanion.client.MaterialsProbe.write(probe, java.nio.file.Path.of("..", "research", "materials-probe.tsv"));
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the materials scene");
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
            } else if (facade) {
                // A plaza at y 150 with a light grey stained glass facade 24 wide and 30 high -PdevFacadeDist blocks ahead
                // (+Z, 50 by default), and white towers with glowstone windows behind the camera for it to reflect
                mc.options.hideGui = true;
                for (String command : new String[]{"time set " + Integer.getInteger("shaderscompanion.devTime", 2000), "weather clear",
                        "gamerule doDaylightCycle false", "gamerule doMobSpawning false"}) {
                    mc.player.connection.sendCommand(command);
                }
                MinecraftServer server = mc.getSingleplayerServer();
                BlockPos at = mc.player.blockPosition();
                BlockPos base = new BlockPos(at.getX(), 150, at.getZ());
                int distance = Integer.getInteger("shaderscompanion.devFacadeDist", 50);
                server.execute(() -> {
                    var level = server.overworld();
                    for (int x = -40; x <= 40; x++) {
                        for (int z = -48; z <= (lake ? distance + 74 : distance + 4); z++) {
                            level.setBlock(base.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                        }
                    }
                    if (lake) {
                        // -PdevScene=lake: a lake from -PdevFacadeDist (50) to 70 blocks farther ahead in place of the facade, seen from 40 blocks up
                        for (int x = -40; x <= 40; x++) {
                            for (int z = distance; z <= distance + 70; z++) {
                                level.setBlock(base.offset(x, -2, z), Blocks.SAND.defaultBlockState(), 2);
                                level.setBlock(base.offset(x, -1, z), Blocks.WATER.defaultBlockState(), 2);
                            }
                        }
                        // Tall towers on the far shore, their tops above the view, so the lake shows them only by the mod
                        for (int tx = -36; tx <= 30; tx += 11) {
                            int height = 44 + Math.floorMod(tx * 5, 4) * 8;
                            for (int y = 0; y < height; y++) {
                                for (int x = 0; x < 6; x++) {
                                    for (int z = 0; z < 6; z++) {
                                        boolean window = y % 3 == 1 && (x == 2 || x == 3) && z == 0;
                                        level.setBlock(base.offset(tx + x, y, distance + 76 + z), window ? Blocks.GLOWSTONE.defaultBlockState()
                                                : (y / 6) % 2 == 0 ? Blocks.WHITE_CONCRETE.defaultBlockState() : Blocks.RED_CONCRETE.defaultBlockState(), 2);
                                    }
                                }
                            }
                        }
                    } else {
                        // -PdevFacadeSize=N: an N x N facade, its left edge at the view's centre (24 x 30, centred, by default)
                        int size = Integer.getInteger("shaderscompanion.devFacadeSize", 0);
                        for (int x = size > 0 ? -size : -12; x <= (size > 0 ? 0 : 12); x++) {
                            for (int y = size > 0 ? 4 : 0; y < (size > 0 ? 4 + size : 30); y++) {
                                level.setBlock(base.offset(x, y, distance), Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState(), 2);
                            }
                        }
                    }
                    for (int tx = -36; tx <= 30; tx += 11) {
                        int height = 10 + Math.floorMod(tx * 7, 4) * 6;
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < 6; x++) {
                                for (int z = 0; z < 6; z++) {
                                    boolean window = y % 3 == 1 && (x == 2 || x == 3) && z == 5;
                                    level.setBlock(base.offset(tx + x, y, -30 + z), window ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
                                }
                            }
                        }
                    }
                    mc.tell(() -> {
                        // Flying again: landing in the ticks before (the build) turned it off
                        mc.player.getAbilities().flying = true;
                        mc.player.onUpdateAbilities();
                        mc.player.connection.sendCommand("tp @s " + (base.getX() + 0.5) + " " + (base.getY() + (lake ? 40 : 0)) + " " + (base.getZ() + 0.5) + " 0 "
                                + Integer.getInteger("shaderscompanion.devPitch", lake ? 25 : -8));
                    });
                });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the {} scene, {} blocks ahead", SCENE, distance);
            } else if (street) {
                mc.options.hideGui = true;
                for (String command : new String[]{"time set " + Integer.getInteger("shaderscompanion.devTime", 4000), "weather clear",
                        "gamerule doDaylightCycle false", "gamerule doMobSpawning false"}) {
                    mc.player.connection.sendCommand(command);
                }
                MinecraftServer server = mc.getSingleplayerServer();
                BlockPos at = mc.player.blockPosition();
                BlockPos base = new BlockPos(at.getX(), 150, at.getZ());
                server.execute(() -> {
                    var level = server.overworld();
                    var air = Blocks.AIR.defaultBlockState();
                    for (int x = -30; x <= 14; x++) {
                        for (int z = -12; z <= 60; z++) {
                            level.setBlock(base.offset(x, -1, z), x >= -8 && x <= 0 ? Blocks.GRAY_CONCRETE.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                            for (int y = 0; y < 34; y++) {
                                level.setBlock(base.offset(x, y, z), air, 2);
                            }
                        }
                    }
                    // The shop front: pillars at x 2, glass at x 3, the room from x 4 to 9, 6 high
                    for (int z = -10; z <= 50; z++) {
                        for (int y = 0; y <= 7; y++) {
                            boolean pillar = Math.floorMod(z, 5) == 0;
                            if (y == 7) {
                                for (int x = 2; x <= 10; x++) {
                                    level.setBlock(base.offset(x, y, z), Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
                                }
                                continue;
                            }
                            if (pillar && !Boolean.getBoolean("shaderscompanion.devShallow")) {
                                level.setBlock(base.offset(2, y, z), Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
                            }
                            level.setBlock(base.offset(3, y, z), pillar ? Blocks.GRAY_CONCRETE.defaultBlockState()
                                    : y == 0 ? Blocks.WHITE_CONCRETE.defaultBlockState() : Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState(), 2);
                            level.setBlock(base.offset(10, y, z), Blocks.BRICKS.defaultBlockState(), 2);
                            if (y == 6) {
                                for (int x = 4; x <= 9; x++) {
                                    level.setBlock(base.offset(x, y, z), Math.floorMod(z, 3) == 0 && x % 2 == 0 ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
                                }
                            }
                        }
                        level.setBlock(base.offset(5, 0, z), Math.floorMod(z, 4) == 0 ? Blocks.POTTED_POPPY.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                        level.setBlock(base.offset(9, 1, z), Math.floorMod(z, 2) == 0 ? Blocks.RED_TERRACOTTA.defaultBlockState() : Blocks.BRICKS.defaultBlockState(), 2);
                    }
                    if (!System.getProperty("shaderscompanion.devImport", "").isEmpty()) {
                        // -PdevImport: blocks from a scan of the user's world ("dx dy dz state" per line), from -PdevImportAt
                        String[] importAt = System.getProperty("shaderscompanion.devImportAt", "-8,0,-30").split(",");
                        importBlocks(level, base.offset(Integer.parseInt(importAt[0].trim()), Integer.parseInt(importAt[1].trim()), Integer.parseInt(importAt[2].trim())));
                    }
                    // A striped tower across the street with lamps on its face and, from 12 blocks up, balconies: a
                    // recess 2 deep and 3 high every 4 blocks with a glass railing and a lamp in its ceiling
                    for (int z = 4; z <= 40; z++) {
                        for (int y = 0; y < 32; y++) {
                            int row = y % 4;
                            boolean balcony = y >= 12 && row != 0;
                            for (int x = -16; x <= -10; x++) {
                                boolean face = x == -12;
                                boolean lamp = face && y % 4 == 2 && Math.floorMod(z, 4) == 1 && !balcony;
                                var state = lamp ? Blocks.SEA_LANTERN.defaultBlockState()
                                        : (y / 2) % 2 == 0 ? Blocks.RED_CONCRETE.defaultBlockState() : Blocks.WHITE_CONCRETE.defaultBlockState();
                                if (x > -12) {
                                    state = y >= 12 && row == 0 ? Blocks.GRAY_CONCRETE.defaultBlockState()
                                            : y >= 12 && row == 1 && x == -10 ? Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState() : air;
                                } else if (balcony && x > -14) {
                                    state = row == 3 && x == -13 && Math.floorMod(z, 3) == 0 ? Blocks.GLOWSTONE.defaultBlockState()
                                            : row == 3 ? Blocks.WHITE_CONCRETE.defaultBlockState() : air;
                                }
                                level.setBlock(base.offset(x, y, z), state, 2);
                            }
                        }
                    }
                    mc.tell(() -> {
                        mc.player.getAbilities().flying = true;
                        mc.player.onUpdateAbilities();
                        if (Boolean.getBoolean("shaderscompanion.devBlocks")) {
                            // -PdevBlocks: the user's blocks, BetterBlockZ's Vect 4 pillars and Lathe's vertical slabs of
                            // BetterBlockZ's light grey tinted glass (-PdevGlassFacing, east: the back half of the bay)
                            String facing = System.getProperty("shaderscompanion.devGlassFacing", "east");
                            int bx = base.getX(), by = base.getY(), bz = base.getZ();
                            for (int z = -10; z <= 50; z++) {
                                if (Math.floorMod(z, 5) == 0) {
                                    mc.player.connection.sendCommand("fill " + (bx + (Boolean.getBoolean("shaderscompanion.devShallow") ? 3 : 2)) + " " + by + " " + (bz + z) + " " + (bx + 3) + " " + (by + 6) + " " + (bz + z) + " betterblockz:vect_blockz_4");
                                }
                            }
                            for (int z = -9; z <= 49; z += 5) {
                                mc.player.connection.sendCommand("fill " + (bx + 3) + " " + (by + 1) + " " + (bz + z) + " " + (bx + 3) + " " + (by + 6) + " " + (bz + z + 3)
                                        + " lathe:betterblockz__tintedglass_blockz_1_vslab[facing=" + facing + "]");
                            }
                        }
                        mc.player.connection.sendCommand("tp @s " + (base.getX() + Double.parseDouble(System.getProperty("shaderscompanion.devCamX", "1.0"))) + " "
                                + (base.getY() + Double.parseDouble(System.getProperty("shaderscompanion.devCamY", "0.0"))) + " " + (base.getZ() + Double.parseDouble(System.getProperty("shaderscompanion.devCamZ", "0.5"))) + " "
                                + Integer.getInteger("shaderscompanion.devYaw", -15) + " " + Integer.getInteger("shaderscompanion.devPitch", 0));
                    });
                });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the street scene");
            } else if (glassBox) {
                // A lit room (white walls, a dark floor, glowstone in the ceiling) with a hollow box of light grey stained
                // glass ahead, a white shelf of quartz with dark openings beside the camera and a red "painting" behind the box
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ 0 " + Integer.getInteger("shaderscompanion.devPitch", 15),
                        "fill ~-8 ~-1 ~-6 ~8 ~6 ~12 minecraft:white_concrete", "fill ~-7 ~ ~-5 ~7 ~5 ~11 minecraft:air",
                        "fill ~-7 ~-1 ~-5 ~7 ~-1 ~11 minecraft:dark_oak_planks",
                        "fill ~-5 ~6 ~-3 ~5 ~6 ~9 minecraft:glowstone", "fill ~-4 ~6 ~-2 ~4 ~6 ~8 minecraft:white_concrete",
                        "fill ~-2 ~ ~3 ~2 ~3 ~6 minecraft:light_gray_stained_glass", "fill ~-1 ~1 ~4 ~1 ~2 ~5 minecraft:air",
                        "fill ~-6 ~ ~-4 ~-1 ~4 ~-4 minecraft:quartz_block", "fill ~-6 ~ ~-5 ~-1 ~4 ~-5 minecraft:black_concrete",
                        "fill ~-5 ~1 ~-4 ~-4 ~1 ~-4 minecraft:air", "fill ~-3 ~3 ~-4 ~-2 ~3 ~-4 minecraft:air", "setblock ~-5 ~3 ~-4 minecraft:air",
                        "fill ~-6 ~ ~-1 ~-5 ~3 ~1 minecraft:quartz_block", "setblock ~-5 ~1 ~0 minecraft:air", "setblock ~-5 ~2 ~0 minecraft:air",
                        "fill ~-2 ~2 ~11 ~1 ~4 ~11 minecraft:red_wool", "fill ~-1 ~3 ~11 ~0 ~3 ~11 minecraft:orange_wool",
                        "fill ~4 ~ ~6 ~6 ~1 ~8 minecraft:oak_planks"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the glassbox scene");
            } else if (enclosure) {
                // Standing in a three-sided enclosure of light grey stained glass (open behind and above) in a street: a
                // red building ahead, white concrete behind, bricks and stone bricks to the sides. The glass reflects the
                // buildings around, not the sky (the cube sees only the enclosure's glass around the camera)
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ " + Integer.getInteger("shaderscompanion.devYaw", 0) + " " + Integer.getInteger("shaderscompanion.devPitch", 5),
                        "fill ~-12 ~-1 ~-12 ~12 ~-1 ~30 minecraft:smooth_stone", "fill ~-12 ~ ~-12 ~12 ~14 ~30 minecraft:air",
                        "fill ~-2 ~ ~-1 ~-2 ~2 ~3 minecraft:light_gray_stained_glass", "fill ~2 ~ ~-1 ~2 ~2 ~3 minecraft:light_gray_stained_glass",
                        "fill ~-2 ~ ~3 ~2 ~2 ~3 minecraft:light_gray_stained_glass",
                        "fill ~-6 ~-1 ~12 ~6 ~10 ~14 minecraft:red_wool", "fill ~-12 ~-1 ~-12 ~12 ~10 ~-10 minecraft:white_concrete",
                        "fill ~-12 ~-1 ~-9 ~-8 ~8 ~30 minecraft:bricks", "fill ~8 ~-1 ~-9 ~12 ~8 ~30 minecraft:stone_bricks"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the enclosure scene");
            } else if (store) {
                // A hall like the user's hardware store (2026-11-21), where metal surfaces share planes with glass: iron
                // walls and ceiling (the iron material class), a quartz floor, light grey stained glass skylights flush
                // in the ceiling, shop windows set in an iron wall on the right, a mezzanine with a glass railing on the
                // left and sea lantern strips in the ceiling. Looking along the hall and up at the ceiling
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ " + Integer.getInteger("shaderscompanion.devYaw", 0) + " " + Integer.getInteger("shaderscompanion.devPitch", -20),
                        "fill ~-14 ~-2 ~-6 ~14 ~10 ~32 minecraft:air",
                        "fill ~-13 ~-1 ~-5 ~13 ~9 ~31 minecraft:iron_block", "fill ~-12 ~ ~-4 ~12 ~8 ~30 minecraft:air",
                        "fill ~-12 ~-1 ~-4 ~12 ~-1 ~30 minecraft:quartz_block",
                        "fill ~-7 ~9 ~6 ~-5 ~9 ~8 minecraft:light_gray_stained_glass", "fill ~3 ~9 ~14 ~5 ~9 ~16 minecraft:light_gray_stained_glass",
                        "fill ~-2 ~9 ~22 ~0 ~9 ~24 minecraft:light_gray_stained_glass",
                        "fill ~-10 ~9 ~0 ~-10 ~9 ~28 minecraft:sea_lantern", "fill ~1 ~9 ~0 ~1 ~9 ~20 minecraft:sea_lantern", "fill ~10 ~9 ~0 ~10 ~9 ~28 minecraft:sea_lantern",
                        "fill ~7 ~ ~-4 ~7 ~8 ~30 minecraft:iron_block",
                        "fill ~7 ~ ~8 ~7 ~3 ~13 minecraft:light_gray_stained_glass", "fill ~7 ~ ~18 ~7 ~3 ~23 minecraft:light_gray_stained_glass",
                        "fill ~10 ~ ~9 ~11 ~2 ~12 minecraft:bookshelf", "fill ~10 ~ ~19 ~11 ~2 ~22 minecraft:red_wool",
                        "fill ~-12 ~3 ~-4 ~-8 ~3 ~30 minecraft:white_concrete", "fill ~-7 ~4 ~-4 ~-7 ~4 ~30 minecraft:light_gray_stained_glass_pane",
                        "fill ~-3 ~ ~6 ~-2 ~2 ~20 minecraft:barrel", "fill ~2 ~ ~6 ~3 ~1 ~20 minecraft:bookshelf"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the store scene");
            } else if (lampGlass) {
                // A closed white wool room at night lit by one lantern on the floor 4 blocks ahead, with light grey
                // stained glass to its right, behind it and above it and wool to its left: faces pointing at the lantern
                // must not glow (the user's lamp beside a glass slab, 2026-11-21)
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 18000), "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ " + Integer.getInteger("shaderscompanion.devYaw", 0) + " " + Integer.getInteger("shaderscompanion.devPitch", 20),
                        "fill ~-7 ~-2 ~-3 ~7 ~7 ~10 minecraft:air",
                        "fill ~-6 ~-1 ~-2 ~6 ~5 ~8 minecraft:white_wool", "fill ~-5 ~ ~-1 ~5 ~4 ~7 minecraft:air",
                        "setblock ~ ~ ~4 minecraft:lantern", "setblock ~1 ~ ~4 minecraft:light_gray_stained_glass",
                        "setblock ~ ~ ~5 minecraft:light_gray_stained_glass", "setblock ~ ~1 ~4 minecraft:light_gray_stained_glass",
                        "setblock ~-1 ~ ~4 minecraft:white_wool"}) {
                    mc.player.connection.sendCommand(command);
                }
                if (Boolean.getBoolean("shaderscompanion.devBlockz")) {
                    // The user's bedroom corner (their world at -1382 91 -1378, 2026-11-21), with -PdevFurniture too: a
                    // desk lamp on a nightstand, Lathe's light grey tinted glass slabs (facing north) just south of it
                    // and above, ironslate beside, the orange curtain slabs on the other side, myst blocks below
                    String slab = "lathe:betterblockz__%s_vslab[facing=north,type=single,waterlogged=false]";
                    for (String command : new String[]{
                            "fill ~-4 ~ ~4 ~-4 ~2 ~5 betterblockz:ironslate_blockz_1", "fill ~-3 ~ ~5 ~-2 ~ ~5 betterblockz:myst_blockz_1",
                            "setblock ~-3 ~ ~4 mdm:bedroom_set_1whitenightstand_1[facing=north]", "setblock ~-3 ~1 ~4 mdm:modern_desk_lamp_1[facing=north]",
                            "fill ~-3 ~1 ~5 ~-3 ~2 ~5 " + String.format(slab, "tintedglass_blockz_4"),
                            "fill ~-2 ~1 ~5 ~-2 ~2 ~5 " + String.format(slab, "zeno_orange_blockz_4")}) {
                        mc.player.connection.sendCommand(command);
                    }
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the lampglass scene");
            } else if (corridor) {
                // The user's corridor (2026-11-21): light panels in a dark ceiling over a light grey tile floor, grey walls
                // and brick partitions, closed in at night; the camera looks along it (+Z)
                mc.options.hideGui = true;
                boolean blockz = Boolean.getBoolean("shaderscompanion.devBlockz");
                String floor = System.getProperty("shaderscompanion.devCorridorFloor", blockz ? "betterblockz:cray_blockz_0" : "minecraft:smooth_stone");
                String wall = blockz ? "betterblockz:mint_blockz_8" : "minecraft:gray_concrete";
                String ceiling = blockz ? "betterblockz:vect_blockz_4" : "minecraft:black_concrete";
                String lamp = System.getProperty("shaderscompanion.devCorridorLamp", blockz ? "betterblockz:cyberlight_blockz_0" : "minecraft:sea_lantern");
                java.util.List<String> commands = new java.util.ArrayList<>(java.util.List.of(
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 18000), "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s " + (Math.floor(mc.player.getX()) + 0.5) + " " + (Math.floor(mc.player.getY()) + 20) + " " + (Math.floor(mc.player.getZ()) + 0.5) + " "
                                + Integer.getInteger("shaderscompanion.devYaw", 0) + " " + Integer.getInteger("shaderscompanion.devPitch", 10),
                        "fill ~-7 ~-2 ~-6 ~7 ~6 ~40 minecraft:air",
                        "fill ~-5 ~-1 ~-4 ~5 ~4 ~38 " + wall, "fill ~-4 ~ ~-3 ~4 ~3 ~37 minecraft:air",
                        "fill ~-4 ~-1 ~-3 ~4 ~-1 ~37 " + floor, "fill ~-4 ~4 ~-3 ~4 ~4 ~37 " + ceiling,
                        "fill ~-4 ~ ~2 ~-3 ~2 ~30 minecraft:bricks", "fill ~3 ~ ~2 ~4 ~2 ~30 minecraft:bricks",
                        "fill ~-4 ~ ~11 ~-3 ~2 ~12 minecraft:air", "fill ~3 ~ ~19 ~4 ~2 ~20 minecraft:air"));
                if (Boolean.getBoolean("shaderscompanion.devCorridorSmall")) {
                    // Small lamps: one block in the middle every 3 blocks, as in a store aisle
                    for (int z = 1; z <= 37; z += 3) {
                        commands.add("setblock ~ ~4 ~" + z + " " + lamp);
                    }
                } else {
                    for (int z = 1; z <= 31; z += 5) {
                        commands.add("fill ~-1 ~4 ~" + z + " ~1 ~4 ~" + (z + 1) + " " + lamp);
                    }
                }
                String end = System.getProperty("shaderscompanion.devCorridorEnd", "");
                if (!end.isEmpty()) {
                    // Two bright strips in the far wall, as at the end of the user's store aisle (staticish.png)
                    commands.add("fill ~-2 ~ ~38 ~-1 ~3 ~38 " + end);
                    commands.add("fill ~1 ~ ~38 ~2 ~3 ~38 " + end);
                }
                commands.add("tp @s ~-1.5 ~ ~");
                for (String command : commands) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the corridor scene");
            } else if (stairWall) {
                // A closed room of smooth sandstone at noon, lit only by a lantern behind the camera. Its far wall (+Z) is
                // oak stairs on the left (+X) half, their flat backs to the room and their open steps to the sunlit
                // outside, and sandstone on the right: smooth lighting must not light the floor, ceiling and side wall
                // along the stairs with the daylight outside (-PdevToggle=config.lightLeakFix)
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~30 ~ " + Integer.getInteger("shaderscompanion.devYaw", 0) + " " + Integer.getInteger("shaderscompanion.devPitch", 15),
                        "fill ~-7 ~-2 ~-6 ~7 ~7 ~10 minecraft:air",
                        "fill ~-5 ~-1 ~-4 ~5 ~5 ~6 minecraft:smooth_sandstone", "fill ~-4 ~ ~-3 ~4 ~4 ~5 minecraft:air",
                        "fill ~ ~ ~6 ~4 ~4 ~6 minecraft:oak_stairs[facing=north,half=bottom]",
                        "setblock ~ ~ ~-3 minecraft:lantern"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the stairwall scene");
            } else if (doorWall) {
                // The stairwall room with closed doors: its far wall (+Z) has a closed oak door (windows) and a closed
                // spruce door on the left (+X) half, the outside behind them lit by the sun and by a lantern beside the
                // doors (-PdevTime=18000 for night), and a closed spruce trapdoor in the ceiling on the right: the room
                // must stay as dark as with a solid wall (-PdevToggle=config.lightLeakFix). -PdevDoorsOpen opens them.
                mc.options.hideGui = true;
                String open = Boolean.getBoolean("shaderscompanion.devDoorsOpen") ? "true" : "false";
                // -PdevDoorwallBlock=minecraft:iron_block: metal walls, floor and ceiling (reflections of the room near the doors)
                String walls = System.getProperty("shaderscompanion.devDoorwallBlock", "minecraft:smooth_sandstone");
                // -PdevDoorwallSolid: the wall block where the doors and the trapdoor would be (what the fix should match)
                boolean solid = Boolean.getBoolean("shaderscompanion.devDoorwallSolid");
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~30 ~ " + Integer.getInteger("shaderscompanion.devYaw", 0) + " " + Integer.getInteger("shaderscompanion.devPitch", 15),
                        "fill ~-7 ~-2 ~-6 ~7 ~7 ~10 minecraft:air",
                        "fill ~-5 ~-1 ~-4 ~5 ~5 ~6 " + walls, "fill ~-4 ~ ~-3 ~4 ~4 ~5 minecraft:air",
                        solid ? "say solid wall" : "setblock ~1 ~1 ~6 minecraft:oak_door[facing=north,half=upper,open=" + open + "]",
                        solid ? "say solid wall" : "setblock ~1 ~ ~6 minecraft:oak_door[facing=north,half=lower,open=" + open + "]",
                        solid ? "say solid wall" : "setblock ~3 ~1 ~6 minecraft:spruce_door[facing=north,half=upper,open=" + open + "]",
                        solid ? "say solid wall" : "setblock ~3 ~ ~6 minecraft:spruce_door[facing=north,half=lower,open=" + open + "]",
                        solid ? "say solid wall" : "setblock ~-2 ~5 ~3 minecraft:spruce_trapdoor[half=top,open=" + open + "]",
                        "setblock ~2 ~ ~8 minecraft:lantern",
                        // -PdevDoorwallBacked: the wall block right behind the doors outside (no light beyond them)
                        Boolean.getBoolean("shaderscompanion.devDoorwallBacked") ? "fill ~ ~ ~7 ~4 ~1 ~7 " + walls : "say open behind",
                        // -PdevDoorwallDark: no lantern inside, so at night whatever lights the room comes through the doors
                        Boolean.getBoolean("shaderscompanion.devDoorwallDark") ? "setblock ~ ~ ~-3 minecraft:air" : "setblock ~ ~ ~-3 minecraft:lantern"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the doorwall scene");
            } else if (skyRoom) {
                // A white room lit by the sky through a hole in its ceiling behind the camera, a low table of light grey
                // stained glass ahead and a wall of it at the far end, a red building across the street outside: the
                // glass reflects the room, never the sky
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s ~ ~20 ~ " + Integer.getInteger("shaderscompanion.devYaw", 20) + " " + Integer.getInteger("shaderscompanion.devPitch", 15),
                        "fill ~-8 ~-1 ~-6 ~8 ~6 ~12 minecraft:white_concrete", "fill ~-7 ~ ~-5 ~7 ~5 ~11 minecraft:air",
                        "fill ~-7 ~-1 ~-5 ~7 ~-1 ~11 minecraft:dark_oak_planks", "fill ~-7 ~6 ~-5 ~-4 ~6 ~-3 minecraft:air",
                        "fill ~-2 ~ ~4 ~2 ~ ~6 minecraft:light_gray_stained_glass",
                        "fill ~-6 ~ ~12 ~6 ~4 ~12 minecraft:light_gray_stained_glass",
                        "fill ~-10 ~-1 ~22 ~10 ~12 ~24 minecraft:red_wool"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the skyroom scene");
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
            } else if (reflectPool) {
                // A black glass wall ahead and, behind the camera, a pool (2 deep over coloured wool, 6 deep in its far
                // half) and a stone pillar standing in it: the pool shows only in the glass, for water in reflections
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 2000), "weather clear", "gamerule doDaylightCycle false",
                        "gamerule doMobSpawning false", "tp @s ~ ~30 ~ 0 " + Integer.getInteger("shaderscompanion.devPitch", 8),
                        "fill ~-12 ~-8 ~-20 ~12 ~-1 ~6 minecraft:stone", "fill ~-12 ~ ~-20 ~12 ~8 ~8 minecraft:air",
                        "fill ~-10 ~-3 ~-18 ~10 ~-3 ~-2 minecraft:orange_wool", "fill ~-10 ~-3 ~-8 ~10 ~-3 ~-6 minecraft:lime_wool",
                        "fill ~-10 ~-7 ~-18 ~10 ~-7 ~-11 minecraft:magenta_wool", "fill ~-10 ~-6 ~-18 ~10 ~-4 ~-11 minecraft:water",
                        "fill ~-10 ~-2 ~-18 ~10 ~-1 ~-2 minecraft:water", "fill ~3 ~-7 ~-9 ~3 ~2 ~-9 minecraft:stone_bricks",
                        "fill ~-8 ~ ~5 ~8 ~6 ~5 minecraft:black_stained_glass"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the reflectpool scene");
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
            } else if (meadow) {
                // A lawn of grass with patches of tall grass, large ferns and flowers around the camera
                mc.options.hideGui = true;
                for (String command : new String[]{"time set " + Integer.getInteger("shaderscompanion.devTime", 6000), "weather clear",
                        "gamerule doDaylightCycle false", "gamerule doMobSpawning false"}) {
                    mc.player.connection.sendCommand(command);
                }
                MinecraftServer server = mc.getSingleplayerServer();
                BlockPos at = mc.player.blockPosition().above(20);
                server.execute(() -> {
                    var level = server.overworld();
                    var lower = Blocks.TALL_GRASS.defaultBlockState();
                    var upper = lower.setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                    for (int x = -24; x <= 24; x++) {
                        for (int z = -24; z <= 24; z++) {
                            level.setBlock(at.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
                            for (int y = 0; y <= 6; y++) {
                                level.setBlock(at.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2 | 16);
                            }
                            int pick = Math.floorMod(x * 7 + z * 13 + x * z, 11);
                            if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                                continue;
                            }
                            if (MEADOW_BLOCKS) {
                                // -PdevMeadowBlocks: no plants (they sway); single blocks and short pillars, then one patch of
                                // glowstone and a tree (below), for ambient occlusion and shadows that hold still
                                if (pick == 0 && Math.floorMod(x + 2 * z, 3) == 0) {
                                    int height = 1 + Math.floorMod(x * z, 3);
                                    for (int y = 0; y < height; y++) {
                                        level.setBlock(at.offset(x, y, z), (y + 1 == height ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 2 | 16);
                                    }
                                }
                                continue;
                            }
                            if (pick < 5) {
                                level.setBlock(at.offset(x, 0, z), lower, 2 | 16);
                                level.setBlock(at.offset(x, 1, z), upper, 2 | 16);
                            } else if (pick < 9) {
                                level.setBlock(at.offset(x, 0, z), Blocks.GRASS.defaultBlockState(), 2 | 16);
                            } else if (pick == 9) {
                                level.setBlock(at.offset(x, 0, z), (x + z) % 2 == 0 ? Blocks.DANDELION.defaultBlockState() : Blocks.CORNFLOWER.defaultBlockState(), 2 | 16);
                            }
                        }
                    }
                    if (MEADOW_BLOCKS) {
                        // Ahead after a 90 degree turn right (-X): a grass block beside glowstone in the ground, and a tree
                        level.setBlock(at.offset(-3, 0, 0), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
                        for (int[] g : new int[][]{{-3, -2}, {-4, -2}, {-2, -2}, {-4, -1}}) {
                            level.setBlock(at.offset(g[0], -1, g[1]), Blocks.GLOWSTONE.defaultBlockState(), 2 | 16);
                        }
                        var leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
                        for (int x = -10; x <= -6; x++) {
                            for (int z = 1; z <= 5; z++) {
                                for (int y = 3; y <= 5; y++) {
                                    if (Math.abs(x + 8) + Math.abs(z - 3) + Math.max(0, y - 4) * 2 <= 4) {
                                        level.setBlock(at.offset(x, y, z), leaves, 2 | 16);
                                    }
                                }
                            }
                        }
                        for (int y = 0; y <= 4; y++) {
                            level.setBlock(at.offset(-8, y, 3), Blocks.OAK_LOG.defaultBlockState(), 2 | 16);
                        }
                    }
                    mc.tell(() -> mc.player.connection.sendCommand("tp @s " + (at.getX() + 0.5) + " " + (at.getY() + 0.2) + " " + (at.getZ() + 0.5) + " 0 "
                            + Integer.getInteger("shaderscompanion.devPitch", 35)));
                });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the meadow scene");
            } else if (lodMirror) {
                // A black glass wall ahead, high above the land, so the glass shows the land behind the camera out to
                // Distant Horizons' LODs
                mc.options.hideGui = true;
                int lift = Integer.getInteger("shaderscompanion.devLift", 30);
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 9000), "weather clear",
                        "gamerule doDaylightCycle " + Boolean.getBoolean("shaderscompanion.devCycle"), "gamerule doMobSpawning false",
                        "tp @s ~ ~" + lift + " ~ " + Integer.getInteger("shaderscompanion.devYaw", 0) + " " + Integer.getInteger("shaderscompanion.devPitch", 6)}) {
                    mc.player.connection.sendCommand(command);
                }
                MinecraftServer server = mc.getSingleplayerServer();
                BlockPos from = mc.player.blockPosition();
                int yaw = Integer.getInteger("shaderscompanion.devYaw", 0);
                server.execute(() -> {
                    var level = server.overworld();
                    BlockPos eye = from.above(lift);
                    double dx = -Math.sin(Math.toRadians(yaw));
                    double dz = Math.cos(Math.toRadians(yaw));
                    // The wall 4 blocks ahead, across the view, 19 wide and 14 high
                    for (int s = -9; s <= 9; s++) {
                        for (int y = -8; y <= 5; y++) {
                            BlockPos p = new BlockPos((int) Math.floor(eye.getX() + 0.5 + dx * 4 + dz * s), eye.getY() + y, (int) Math.floor(eye.getZ() + 0.5 + dz * 4 - dx * s));
                            level.setBlock(p, Blocks.BLACK_STAINED_GLASS.defaultBlockState(), 2 | 16);
                        }
                    }
                });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the lodmirror scene");
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
            } else if (exposure) {
                // A lit block of town at night, seen from above: white buildings with glowstone, a lime lawn with sea lanterns
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 18000), "weather clear",
                        "gamerule doDaylightCycle " + Boolean.getBoolean("shaderscompanion.devCycle"), "gamerule doMobSpawning false",
                        "fill ~-24 ~-1 ~-4 ~24 ~-1 ~40 minecraft:lime_concrete", "fill ~-24 ~ ~-4 ~24 ~14 ~40 minecraft:air",
                        "fill ~-24 ~-1 ~10 ~24 ~-1 ~11 minecraft:white_concrete", "fill ~-1 ~-1 ~-4 ~1 ~-1 ~40 minecraft:white_concrete",
                        "fill ~-20 ~-1 ~2 ~-20 ~-1 ~38 minecraft:sea_lantern", "fill ~20 ~-1 ~2 ~20 ~-1 ~38 minecraft:sea_lantern",
                        "fill ~4 ~ ~16 ~16 ~8 ~28 minecraft:white_concrete", "fill ~5 ~1 ~16 ~15 ~7 ~16 minecraft:glowstone",
                        "fill ~5 ~2 ~16 ~15 ~2 ~16 minecraft:white_concrete", "fill ~5 ~5 ~16 ~15 ~5 ~16 minecraft:white_concrete",
                        "fill ~-16 ~ ~18 ~-6 ~5 ~30 minecraft:quartz_block", "fill ~-15 ~6 ~19 ~-7 ~6 ~29 minecraft:glowstone",
                        "fill ~-24 ~-1 ~32 ~24 ~-1 ~40 minecraft:stone_bricks",
                        "tp @s ~ ~16 ~-6 0 " + Integer.getInteger("shaderscompanion.devPitch", 35)}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the exposure scene");
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
            if (Boolean.getBoolean("shaderscompanion.devNightVision")) {
                // -PdevNightVision=true: vanilla night vision for the whole run (any scene)
                mc.player.connection.sendCommand("effect give @s minecraft:night_vision infinite 0 true");
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
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, yaw {}: {}", String.format("%.1f", yaw), mirrorStatus());
            }
        } else if (phase == 1 && turn && ticks - phaseTick >= WAIT_TICKS) {
            // Three quick turns from facing away from the glass wall (yaw -90, the water is mirrored) to facing it (yaw 90)
            // and back, 180 degrees in 6 ticks; the mirrored plane is logged every tick (x is the glass, y the water)
            int t = ticks - phaseTick - WAIT_TICKS;
            int cycle = t % 60;
            float yaw = cycle < 6 ? -90.0F + 30.0F * (cycle + 1) : cycle < 30 ? 90.0F : cycle < 36 ? 90.0F - 30.0F * (cycle - 29) : -90.0F;
            // yRotO keeps the last tick's yaw, so frames turn smoothly between ticks as with a mouse
            mc.player.setYRot(yaw);
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, turn tick {} yaw {}: {}", t, yaw, mirrorStatus());
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
            if (!LeftoverVertexProbe.installed() && Iris.getCurrentPack().isPresent()) {
                try {
                    LeftoverVertexProbe.install(Boolean.getBoolean("shaderscompanion.devPoison"));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    ShadersCompanion.LOGGER.warn("Shaders Companion dev run: no leftover vertex probe ({})", e.toString());
                }
            }
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
            // After leaving: a turn to the right, 6 degrees a tick, with shots on the way (the user saw stray shapes
            // while looking around after a ride), then back to where the run looked
            if (s >= 80 && s < 110) {
                mc.player.setYRot(boatYaw + 6.0F * (s - 79));
            } else if (s == 110) {
                mc.player.setYRot(boatYaw);
                mc.player.yRotO = boatYaw;
            }
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
            String name = s == 0 ? "before" : s == 60 ? "riding" : s == 72 ? "after-early" : s == 85 ? "pan1" : s == 92 ? "pan2"
                    : s == 99 ? "pan3" : s == 106 ? "pan4" : s == 120 ? "after" : s == 160 ? "after-noentities"
                    : s == 200 ? "after-off" : s == 260 ? "after-on" : null;
            if (name != null) {
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-boat-" + tag + "-" + name + ".png", mc.getMainRenderTarget(), message -> { });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, {} {} at {} (vehicle {}): {} | {}", tag, name, mc.player.position(),
                        mc.player.getVehicle(), String.join(" | ", DebugOverlay.statusLines()), LeftoverVertexProbe.summary());
            }
            if (s == 261) {
                finish(mc);
            }
        } else if (phase == 1 && darkSpot) {
            // Sky light 0 on the client at spots ahead of the camera, a shot, then a client relight and another shot
            int t = ticks - phaseTick - WAIT_TICKS;
            String tag = System.getProperty("shaderscompanion.devTag", "run");
            BlockPos at = mc.player.blockPosition();
            int[][] columns = {{-2, 3}, {1, 4}, {3, 5}, {0, 6}, {-4, 6}, {2, 2}};
            var engine = mc.level.getLightEngine();
            if (t == 0) {
                for (int[] c : columns) {
                    for (int y = 0; y <= 1; y++) {
                        BlockPos pos = at.offset(c[0], y, c[1]);
                        var layer = engine.getLayerListener(net.minecraft.world.level.LightLayer.SKY).getDataLayerData(net.minecraft.core.SectionPos.of(pos));
                        int before = engine.getLayerListener(net.minecraft.world.level.LightLayer.SKY).getLightValue(pos);
                        if (layer != null) {
                            layer.set(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, 0);
                        }
                        ShadersCompanion.LOGGER.info("Shaders Companion dev run, dark spot at {} ({}): sky light {} -> {}{}", pos,
                                mc.level.getBlockState(pos).getBlock(), before,
                                engine.getLayerListener(net.minecraft.world.level.LightLayer.SKY).getLightValue(pos), layer == null ? " (no data layer)" : "");
                        mc.levelRenderer.setSectionDirty(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
                    }
                }
            } else if (t == 60) {
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-darkspot-" + tag + "-dark.png", mc.getMainRenderTarget(), message -> { });
            } else if (t == 62) {
                for (int[] c : columns) {
                    for (int y = 0; y <= 1; y++) {
                        engine.checkBlock(at.offset(c[0], y, c[1]));
                    }
                }
            } else if (t == 70) {
                for (int[] c : columns) {
                    BlockPos pos = at.offset(c[0], 0, c[1]);
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, after relight at {}: sky light {}", pos,
                            engine.getLayerListener(net.minecraft.world.level.LightLayer.SKY).getLightValue(pos));
                    for (int y = 0; y <= 1; y++) {
                        mc.levelRenderer.setSectionDirty(pos.getX() >> 4, (pos.getY() + y) >> 4, pos.getZ() >> 4);
                    }
                }
            } else if (t == 130) {
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-darkspot-" + tag + "-fixed.png", mc.getMainRenderTarget(), message -> { });
            } else if (t == 131) {
                finish(mc);
            }
        } else if (phase == 1 && meadow) {
            // Shots around the compass (every 60 degrees) in the morning, at noon and in the afternoon, each 20 ticks
            // after the turn so temporal effects settle
            int t = ticks - phaseTick - WAIT_TICKS;
            // -PdevReloads=N: the pack reloads N times while the scene settles, as when switching packs in game
            int reloads = Integer.getInteger("shaderscompanion.devReloads", 0);
            int sinceBuilt = ticks - phaseTick;
            if (t < 0 && sinceBuilt % 40 == 20 && sinceBuilt / 40 < reloads) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: reloading the shader pack ({} of {})", sinceBuilt / 40 + 1, reloads);
                try {
                    Iris.reload();
                } catch (Exception e) {
                    ShadersCompanion.LOGGER.error("Shaders Companion dev run: reload failed", e);
                }
            }
            int[] times = {Integer.getInteger("shaderscompanion.devTime", 3000), 6000, 9000};
            int[] yaws = {0, 60, 120, 180, 240, 300};
            if (t >= 0 && !TOGGLE.isEmpty()) {
                // -PdevToggle: the usual rounds with the option off and on in turn, from where the scene was built
                prefix = "shaderscompanion-meadow-" + System.getProperty("shaderscompanion.devTag", "run");
                timingRound = 0;
                setPackRound(0);
                phase = 4;
                phaseTick = ticks;
            } else if (t >= 0) {
                int shot = t / 20;
                if (shot >= times.length * yaws.length) {
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run timing with the mod on: {}", frameTiming());
                    finish(mc);
                    return;
                }
                int time = times[shot / yaws.length];
                int yaw = yaws[shot % yaws.length];
                if (t % 20 == 0) {
                    mc.player.connection.sendCommand("time set " + time);
                    mc.player.setYRot(yaw);
                    mc.player.yRotO = yaw;
                } else if (t % 20 == 19) {
                    Screenshot.grab(mc.gameDirectory, "shaderscompanion-meadow-" + System.getProperty("shaderscompanion.devTag", "run") + "-t" + time + "-y" + yaw + ".png",
                            mc.getMainRenderTarget(), message -> { });
                }
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
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, {} {} at {}: {}", tag, name, mc.player.position(), mirrorStatus());
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
        } else if (phase == 1 && exposure) {
            // A shot every -PdevEvery ticks (200), -PdevShots of them (13), with the time of day in the log
            int t = ticks - phaseTick - WAIT_TICKS;
            int every = Integer.getInteger("shaderscompanion.devEvery", 200);
            int shots = Integer.getInteger("shaderscompanion.devShots", 13);
            String tag = System.getProperty("shaderscompanion.devTag", "run");
            if (t >= 0 && t % every == 0) {
                int shot = t / every;
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-exposure-" + tag + "-" + String.format("%02d", shot) + ".png", mc.getMainRenderTarget(), message -> { });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, exposure {} shot {}: day time {}, at {} (block light {}, sky light {})", tag, shot,
                        mc.level.getDayTime() % 24000, mc.player.blockPosition(), mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, mc.player.blockPosition()),
                        mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, mc.player.blockPosition()));
                int step = Integer.getInteger("shaderscompanion.devStep", 0);
                if (shot + 1 >= shots) {
                    finish(mc);
                } else if (step > 0) {
                    // -PdevStep: each next shot at that many more ticks of the day, after -PdevEvery ticks to adapt
                    mc.player.connection.sendCommand("time set " + (Integer.getInteger("shaderscompanion.devTime", 18000) + (shot + 1) * step));
                }
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
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, pose {} (yaw {}): {}", pose, yaws[pose], mirrorStatus());
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
        } else if (phase == 1 && (pool || hand || mobs || corner || grass || ocean || match || materials || shadows || aquarium || overlay || turn || city || reflectPool || facade || glassBox || street || stairWall || doorWall || store || lampGlass || corridor ? ticks - phaseTick >= WAIT_TICKS : CubeView.framesDrawn() >= START_FRAME + DUMP_FRAMES)) {
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
                } else if (!pool && !hand && !glass && !ocean) {
                    // Just below the horizon of the +X face (face 0: column 0, row 0): far ground, LODs with Distant Horizons
                    for (int percent : new int[]{46, 43, 40}) {
                        ShadersCompanion.LOGGER.info("Shaders Companion dev run, +X horizon {}%: {}", percent, CubeDump.describeTexel(size / 2, size * percent / 100));
                    }
                }
                if (overlay) {
                    logOverlayContainer(mc);
                }
                Screenshot.grab(mc.gameDirectory, prefix + (pool || hand || glass || ocean ? "-on.png" : "-main.png"), mc.getMainRenderTarget(), message -> { });
            } catch (Exception e) {
                ShadersCompanion.LOGGER.error("Shaders Companion dev run: saving failed", e);
            }
            ShadersCompanion.LOGGER.info("Shaders Companion dev run at {}: {}", mc.player == null ? "?" : mc.player.position(),
                    String.join(" | ", DebugOverlay.statusLines()));
            if (System.getProperty("shaderscompanion.devCells") != null) {
                logRayCoverage(mc);
            }
            ShadersCompanion.LOGGER.info("Shaders Companion dev run timing with the mod on: {}", frameTiming());
            if (!TOGGLE.isEmpty() && !shadows) {
                // Timing rounds with the option off, on, off, on..., each with a screenshot
                timingRound = 0;
                setPackRound(0);
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
            } else if (pool || hand || glass || ocean) {
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
        } else if (phase == 4 && (PAN != 0.0F || STRAFE) && ticks - phaseTick < TOGGLE_SETTLE_TICKS + TIMING_TICKS) {
            // -PdevPan, -PdevStrafe: every round starts its burst from the same still yaw and spot
            if (Float.isNaN(panBaseYaw)) {
                panBaseYaw = mc.player.getYRot();
                panBasePos = mc.player.position();
            }
            mc.options.keyLeft.setDown(false);
            mc.player.setYRot(panBaseYaw);
            mc.player.yRotO = panBaseYaw;
            if (STRAFE && ticks - phaseTick == TOGGLE_SETTLE_TICKS + TIMING_TICKS - 20) {
                mc.player.connection.sendCommand("tp @s " + panBasePos.x + " " + panBasePos.y + " " + panBasePos.z + " " + panBaseYaw + " " + mc.player.getXRot());
            }
        } else if (phase == 4 && ticks - phaseTick >= TOGGLE_SETTLE_TICKS + TIMING_TICKS) {
            boolean on = timingRound % 2 == 1;
            String label = roundLabel(timingRound);
            // -PdevBurst=N: N shots on consecutive ticks at the round's end (for flicker: the camera holds still, or turns
            // PAN degrees a tick)
            int shot = ticks - phaseTick - TOGGLE_SETTLE_TICKS - TIMING_TICKS;
            Screenshot.grab(mc.gameDirectory, prefix + "-" + TOGGLE + "-" + label + timingRound + (BURST > 1 ? "-b" + String.format("%03d", shot) : "") + ".png",
                    mc.getMainRenderTarget(), message -> { });
            if (SCENE.equals("doorwall") && shot == 0) {
                logDoorwallLight(mc, label);
            }
            if (PAN != 0.0F || STRAFE) {
                mc.player.yRotO = panBaseYaw + PAN * Math.min(shot, PAN_TICKS);
                mc.player.setYRot(panBaseYaw + PAN * Math.min(shot + 1, PAN_TICKS));
                mc.options.keyLeft.setDown(STRAFE && shot + 1 < BURST);
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, pan {} {} shot {}: {}", TOGGLE, label, shot, mirrorStatus());
            }
            if (shot + 1 < BURST) {
                return;
            }
            if (System.getProperty("shaderscompanion.devCells") != null) {
                logRayCoverage(mc);
            }
            String[] lines = DebugOverlay.statusLines();
            ShadersCompanion.LOGGER.info("Shaders Companion dev run timing with {} {}: {} | {}", TOGGLE, label, frameTiming(),
                    lines.length > 3 ? lines[1] + " | " + lines[3] : "");
            phaseTick = ticks;
            if (timingRound == 1 && Boolean.getBoolean("shaderscompanion.devResourceReload")) {
                // -PdevResourceReload: block models are baked again before the third round
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: reloading resources");
                mc.reloadResourcePacks();
            }
            if (++timingRound < TOGGLE_ROUNDS) {
                setPackRound(timingRound);
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

    /**
     * The shore scene's build, on the ocean at {@code at} with the camera {@code lift} blocks up, looking +Z: a far shore
     * 90 to 104 blocks away (a low white wall at the waterline, towers with lit windows behind it) and a black glass
     * facade to the camera's left, like a tall building beside a lake.
     */
    private static void importBlocks(net.minecraft.server.level.ServerLevel level, BlockPos origin) {
        String importFile = System.getProperty("shaderscompanion.devImport", "");
        int placed = 0;
        try {
            var blocks = level.holderLookup(net.minecraft.core.registries.Registries.BLOCK);
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(importFile))) {
                String[] part = line.trim().split(" ", 4);
                if (part.length < 4) {
                    continue;
                }
                try {
                    var state = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(blocks, part[3], false).blockState();
                    level.setBlock(origin.offset(Integer.parseInt(part[0]), Integer.parseInt(part[1]), Integer.parseInt(part[2])), state, 2);
                    placed++;
                } catch (Exception e) {
                    ShadersCompanion.LOGGER.warn("Shaders Companion dev run: can't place {}", line);
                }
            }
        } catch (java.io.IOException e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: can't read {}", importFile, e);
        }
        ShadersCompanion.LOGGER.info("Shaders Companion dev run: placed {} blocks from {} at {}", placed, importFile, origin);
    }

    /**
     * -PdevTrunk: a grass walkway from the camera's spot to its left (the way -PdevStrafe walks), and a cherry tree 4
     * blocks ahead and 2 to the left, its trunk 8 blocks high under a canopy, so walking left uncovers the sky, the
     * clouds and the water behind the trunk.
     */
    private static void buildTrunk(net.minecraft.server.level.ServerLevel level, BlockPos at, int lift, int yaw) {
        double forwardX = -Math.sin(Math.toRadians(yaw));
        double forwardZ = Math.cos(Math.toRadians(yaw));
        int floor = 63 + lift;
        for (int ahead = -1; ahead <= 1; ahead++) {
            for (int left = -2; left <= 8; left++) {
                level.setBlock(trunkOffset(at, floor, ahead, left, forwardX, forwardZ), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        }
        for (int up = 0; up < 8; up++) {
            level.setBlock(trunkOffset(at, floor + up, 4, 2, forwardX, forwardZ), Blocks.CHERRY_LOG.defaultBlockState(), 2);
        }
        for (int ahead = 2; ahead <= 6; ahead++) {
            for (int left = 0; left <= 4; left++) {
                for (int up = 8; up <= 9; up++) {
                    level.setBlock(trunkOffset(at, floor + up, ahead, left, forwardX, forwardZ), Blocks.CHERRY_LEAVES.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
                }
            }
        }
    }

    /** The block at height y, ahead and to the left of at as seen looking along (forwardX, forwardZ). */
    private static BlockPos trunkOffset(BlockPos at, int y, int ahead, int left, double forwardX, double forwardZ) {
        // Left of the view: the forward direction turned a quarter towards +x from +z
        return new BlockPos(at.getX() + (int) Math.round(forwardX * ahead + forwardZ * left), y,
                at.getZ() + (int) Math.round(forwardZ * ahead - forwardX * left));
    }

    private static void buildShore(net.minecraft.server.level.ServerLevel level, BlockPos at, int lift) {
        var wall = Blocks.WHITE_CONCRETE.defaultBlockState();
        var light = Blocks.GLOWSTONE.defaultBlockState();
        for (int x = -70; x <= 70; x++) {
            for (int z = 90; z <= 104; z++) {
                for (int y = 40; y <= 62; y++) {
                    level.setBlock(at.offset(x, y - at.getY(), z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            // The low wall at the waterline: three blocks high, its top lit every 8 blocks
            for (int y = 63; y <= 65; y++) {
                level.setBlock(at.offset(x, y - at.getY(), 90), y == 65 && Math.floorMod(x, 8) == 0 ? light : wall, 2);
            }
        }
        // -PdevMetal: iron towers (Distant Horizons' METAL block material, beyond the chunk terrain at render distance 5)
        var tower = Boolean.getBoolean("shaderscompanion.devMetal") ? Blocks.IRON_BLOCK.defaultBlockState() : wall;
        for (int tx = -60; tx <= 60; tx += 10) {
            int height = 10 + Math.floorMod(tx * 7, 4) * 5;
            for (int y = 63; y < 63 + height; y++) {
                for (int x = 0; x < 3; x++) {
                    for (int z = 0; z < 3; z++) {
                        boolean window = y % 3 == 0 && x == 1 && z == 0;
                        level.setBlock(at.offset(tx + x, y - at.getY(), 96 + z), window ? light : tower, 2);
                    }
                }
            }
        }
        // The facade to the left (+X looking +Z is to the left): black glass from the water to above the camera
        // (-PdevNoFacade leaves it out, so the water is mirrored)
        for (int y = 63; y <= (Boolean.getBoolean("shaderscompanion.devNoFacade") ? 62 : 70 + lift); y++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlock(at.offset(3, y - at.getY(), z), Blocks.BLACK_STAINED_GLASS.defaultBlockState(), 2);
            }
        }
        ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the shore at {}", at);
    }

    /** The status overlay's mirror view line, without its label. */
    /**
     * -PdevCells: for each cell of the mirror plane search's grid (20 x 12 over 1.3 screen halves), the share of 8 x 8
     * rays that reach a water surface, traced against block outlines (as the search did up to 2.0.0-dev5) and against
     * collision shapes (as it does since 2.0.0-dev6), as "outline/collider" in percent; and a star on the search's own
     * cells that hold the mirrored plane now. Rows from the top of the screen (row 0 is the bottom).
     */
    private static void logRayCoverage(Minecraft mc) {
        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 eye = camera.getPosition();
        org.joml.Vector3f look = camera.getLookVector();
        org.joml.Vector3f up = camera.getUpVector();
        org.joml.Vector3f left = camera.getLeftVector();
        double tanY = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        double tanX = tanY * mc.getWindow().getWidth() / mc.getWindow().getHeight();
        long[] held = null;
        long[] heldNanos = null;
        long plane = Long.MIN_VALUE;
        try {
            Class<?> search = Class.forName("com.rinkynooble.shaderscompanion.client.MirrorPlaneSearch");
            java.lang.reflect.Field f = search.getDeclaredField("cellHeldPlanes");
            f.setAccessible(true);
            held = (long[]) f.get(null);
            f = search.getDeclaredField("cellHeldNanos");
            f.setAccessible(true);
            heldNanos = (long[]) f.get(null);
            f = search.getDeclaredField("slotPlanes");
            f.setAccessible(true);
            plane = ((long[]) f.get(null))[0];
        } catch (ReflectiveOperationException e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: no plane search fields", e);
        }
        long now = System.nanoTime();
        for (int row = 11; row >= 0; row--) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < 20; col++) {
                int[] hits = new int[2];
                for (int s = 0; s < 64; s++) {
                    double x = ((col + ((s % 8) + 0.5) / 8.0) / 20.0 * 2.0 - 1.0) * 1.3;
                    double y = ((row + ((s / 8) + 0.5) / 8.0) / 12.0 * 2.0 - 1.0) * 1.3;
                    net.minecraft.world.phys.Vec3 dir = new net.minecraft.world.phys.Vec3(
                            look.x() - left.x() * x * tanX + up.x() * y * tanY,
                            look.y() - left.y() * x * tanX + up.y() * y * tanY,
                            look.z() - left.z() * x * tanX + up.z() * y * tanY).normalize();
                    for (int mode = 0; mode < 2; mode++) {
                        net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(dir.scale(64.0)),
                                mode == 0 ? net.minecraft.world.level.ClipContext.Block.OUTLINE : net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                net.minecraft.world.level.ClipContext.Fluid.WATER, mc.player));
                        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS && hit.getDirection() == net.minecraft.core.Direction.UP
                                && mc.level.getFluidState(hit.getBlockPos()).is(net.minecraft.tags.FluidTags.WATER)) {
                            hits[mode]++;
                        }
                    }
                }
                boolean holds = held != null && held[row * 20 + col] == plane && now - heldNanos[row * 20 + col] < 500_000_000L;
                line.append(String.format(" %2d/%2d%s", hits[0] * 100 / 64, hits[1] * 100 / 64, holds ? "*" : " "));
            }
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, cells row {}:{}", row, line);
        }
    }

    /**
     * The doorwall scene: the light the client's level gives (DoorLight's correction included) and the light engine's own,
     * sky/block, at the doors' cells, the room's cells in front of them and the cells outside them.
     */
    private static void logDoorwallLight(Minecraft mc, String label) {
        net.minecraft.core.BlockPos base = mc.player.blockPosition();
        StringBuilder line = new StringBuilder();
        for (int[] at : new int[][]{{1, 0, 6}, {1, 1, 6}, {3, 0, 6}, {1, 0, 5}, {1, 1, 5}, {1, 0, 7}, {0, 0, 0}, {-2, 0, 3}, {-2, 4, 3}}) {
            net.minecraft.core.BlockPos pos = base.offset(at[0], at[1], at[2]);
            int sky = mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
            int block = mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
            com.rinkynooble.shaderscompanion.client.DoorLight.raw = true;
            int rawSky = mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
            int rawBlock = mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
            com.rinkynooble.shaderscompanion.client.DoorLight.raw = false;
            line.append(String.format(" | %d,%d,%d %s: %d/%d (engine %d/%d)", at[0], at[1], at[2],
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).getPath(), sky, block, rawSky, rawBlock));
        }
        ShadersCompanion.LOGGER.info("Shaders Companion dev run, doorwall light {} (sky/block){}", label, line);
    }

    private static String mirrorStatus() {
        for (String line : DebugOverlay.statusLines()) {
            int at = line.indexOf("Mirror view: ");
            if (at >= 0) {
                return line.substring(at + "Mirror view: ".length());
            }
        }
        return "";
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

    /** A toggle round's name in shots and the log: "off" and "on", or "v<value>" for an option with more than two values. */
    private static String roundLabel(int round) {
        if (TOGGLE_VALUES != null && TOGGLE_VALUES.length > 2) {
            return "v" + TOGGLE_VALUES[round % TOGGLE_VALUES.length];
        }
        return round % 2 == 1 ? "on" : "off";
    }

    /**
     * Sets the shader pack's option named by -PdevToggle for a toggle round and reloads the pack; "config.<name>" sets the
     * mod's own setting of that name instead.
     */
    private static void setPackRound(int round) {
        boolean on = round % 2 == 1;
        String text = TOGGLE_VALUES == null ? Boolean.toString(on)
                : TOGGLE_VALUES.length > 2 ? TOGGLE_VALUES[round % TOGGLE_VALUES.length] : TOGGLE_VALUES[on ? 1 : 0];
        if (TOGGLE.startsWith("config.")) {
            String name = TOGGLE.substring("config.".length());
            if (name.equals("lightLeakFix") && Boolean.parseBoolean(text)) {
                com.rinkynooble.shaderscompanion.client.LightLeaks.logBudget.set(Integer.getInteger("shaderscompanion.devLeakLog", 0));
            }
            for (java.lang.reflect.Field field : Config.class.getFields()) {
                try {
                    if (field.get(null) instanceof net.minecraftforge.common.ForgeConfigSpec.ConfigValue<?> value
                            && value.getPath().get(value.getPath().size() - 1).equals(name)) {
                        if (value instanceof net.minecraftforge.common.ForgeConfigSpec.BooleanValue bool) {
                            bool.set(Boolean.parseBoolean(text));
                        } else if (value instanceof net.minecraftforge.common.ForgeConfigSpec.IntValue integer) {
                            integer.set(Integer.parseInt(text));
                        }
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
            Iris.getShaderPackOptionQueue().put(TOGGLE, text);
            Iris.reload();
        } catch (Exception e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: setting {} failed", TOGGLE, e);
        }
    }

    /** Turns Oculus's shaders on or off, as its toggle key does (saved in its settings file). */
    private static void setShaders(Minecraft mc, boolean on) {
        try {
            Iris.toggleShaders(mc, on);
        } catch (Exception e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: turning shaders {} failed", on ? "on" : "off", e);
        }
    }

    private static void finish(Minecraft mc) {
        if (!shadersOn) {
            setShaders(mc, true);
        }
        if (CubeView.lodRangeForChecks() > 0 || System.getProperty("shaderscompanion.devDh") != null) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: Distant Horizons range {}", CubeView.lodRangeForChecks());
        }
        phase = -1;
        ShadersCompanion.LOGGER.info("Shaders Companion dev run done");
        mc.stop();
    }
}
