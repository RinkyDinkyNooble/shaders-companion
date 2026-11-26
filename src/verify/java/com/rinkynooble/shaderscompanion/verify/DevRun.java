package com.rinkynooble.shaderscompanion.verify;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.Config;
import com.rinkynooble.shaderscompanion.client.CubeDump;
import com.rinkynooble.shaderscompanion.client.CubeView;
import com.rinkynooble.shaderscompanion.client.DebugOverlay;
import com.rinkynooble.shaderscompanion.client.FarTextures;
import com.rinkynooble.shaderscompanion.client.far.FarRenderer;
import com.rinkynooble.shaderscompanion.client.far.FarChecks;
import com.rinkynooble.shaderscompanion.client.far.FarStore;
import com.rinkynooble.shaderscompanion.client.far.FarTerrain;
import com.rinkynooble.shaderscompanion.client.far.FarViews;
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
 * -PdevScene=far (M0 of the far-terrain work, research/lod/BASELINE.md): a high vantage over mountains and sea in its
 * own world (run/saves/shaderscompanion-far, kept between runs so Distant Horizons' database grows in it), the feet on
 * a barrier, looking east. With -PdevDh it waits until Distant Horizons' LODs hold steady (at most -PdevWait ticks,
 * logging DhProbe's line and the video memory every 10 seconds), then times twelve rounds: Distant Horizons drawing and
 * not, then two black glass walls beside the camera with the pack's mirror off, at 1 and at 2 planes (FAR_ROUNDS).
 * -PdevFarFreeze pauses Distant Horizons' generation; -PdevFarSearch logs candidate vantages and quits; -PdevFarFill
 * times how LODs fill fresh terrain, held still and then flown at 30 blocks a second (a fresh world each run);
 * -PdevChunky -PdevFarChunky=R has Chunky generate real chunks in R blocks around the vantage first.
 * <p>
 * -PdevScene=far -PdevFarStore (M1a of the far-terrain work, research/lod/M1.md): the far store on for the run (not
 * saved; its cache -PdevFarStoreCache MB, the config's by default since M1b) and -PdevFarRender=N the render
 * distance for the run (16 loads the whole 512-block square around the vantage at once). Instead of the M0 rounds it
 * waits at least a minute until the store has settled (FarStore.settledForChecks for 5 seconds), logging the main
 * thread's time per frame every second while sections wait; then writes the maps of levels 0 to 4 of the same 512-block
 * square (screenshots/shaderscompanion-lod-far-L0.png to -L4.png) and the command's own map, logs the stats, turns
 * the store off and on again, and flies east across Chunky's square at 30 blocks a second at render distance 5
 * (-PdevFillFly ticks, 520 by default) logging the main thread's time every second. -PdevFarDim then goes to the
 * Nether and back (the store must stop and start for each dimension). -PdevFarShapes floats thin test shapes at y 200
 * over the plains south of the vantage while the store fills (a one-block roof, a slab roof, a wall, a diagonal wall,
 * a glass wall, fence posts and a half-filled leaf canopy; FAR_SHAPES) and takes them away again at the end.
 * <p>
 * M1b (the store on disk, research/lod/M1.md): the store's cache is the config's unless -PdevFarStoreCache=N.
 * -PdevFarRejoin: once the store has settled, logs its stats, leaves the world to the title screen, joins it again and
 * waits until the store has settled again, logging what the second join stored (it should be nearly nothing new).
 * -PdevFarImport=R: 2 seconds in, starts /shaderscompanion lod import R and waits for it, logging the status every 10
 * seconds; once the store has settled writes the maps of levels 0 and 3 of the 512-block square
 * (shaderscompanion-lod-import-L0.png, -L3.png) and of level 3 over Chunky's whole square (-L3-all.png), logs the stats
 * and the compression measurement, then imports the same again (it should store nothing) and quits.
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
 * with -PdevToggle=COMPANION_DOOR_LIGHT (the pack's option, off by default; config.lightLeakFix must be on too) the room
 * stays as dark as with a solid wall in the "on" rounds.
 * -PdevScene=bedroom: a white concrete room at night lit by a floor lantern, a black stained glass wall on the right
 * reflecting a quartz wardrobe, a wool dresser and a chest on screen and oak and wool furniture behind the camera (the
 * user's bedroom of 2026-11-22: where reflections of what is on screen meet those of what isn't). With devpack.py
 * litcheck, the mirror's lit colour of surfaces also on screen against the screen's.
 * <p>
 * -PdevToggle=profile:LOW:MEDIUM:HIGH:ULTRA:MAX applies the pack's presets by name in turn (two rounds each), for a
 * preset's cost in one run.
 * <p>
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
    /** The far scene (M0 of the far-terrain work): a high vantage over mountains and sea, -PdevDh for Distant Horizons. */
    private static final boolean FAR = SCENE.equals("far");
    /** -PdevFarFill: the far scene's fill check instead (a fresh world each run, deleted and made again). */
    private static final boolean FAR_FILL = Boolean.getBoolean("shaderscompanion.devFarFill");
    /**
     * -PdevFarFreeze: the far scene pauses Distant Horizons' generation of unvisited terrain from the start (through its
     * API, not saved), so it times the LODs its database already holds with no generation load.
     */
    private static final boolean FAR_FREEZE = Boolean.getBoolean("shaderscompanion.devFarFreeze");
    /** -PdevFarSearch: the far scene logs candidate vantages around the spawn and quits. */
    private static final boolean FAR_SEARCH = Boolean.getBoolean("shaderscompanion.devFarSearch");
    /** -PdevFarStore: the far scene checks the far store (M1a) instead of timing Distant Horizons. */
    private static final boolean FAR_STORE = Boolean.getBoolean("shaderscompanion.devFarStore");
    /** -PdevFarRejoin: the far store check leaves the world and joins it again (M1b). */
    private static final boolean FAR_REJOIN = Boolean.getBoolean("shaderscompanion.devFarRejoin");
    /** -PdevFarImport=R: the far store check imports the saved chunks within R chunks (M1b). */
    private static final int FAR_IMPORT = Integer.getInteger("shaderscompanion.devFarImport", 0);
    /**
     * -PdevFarTunnel: the import check digs a tunnel across section and node borders deep under the vantage once the
     * store has settled, with granite hidden around it beforehand, compares the store with the world before, after and
     * once the tunnel is filled again (M1c, hidden voxels).
     */
    private static final boolean FAR_TUNNEL = Boolean.getBoolean("shaderscompanion.devFarTunnel");
    /** -PdevFarRawStore=path: the import check compares its store with a store written without hidden voxels (M1c). */
    private static final String FAR_RAW_STORE = System.getProperty("shaderscompanion.devFarRawStore", "");
    /** -PdevFarFlight: the far store check joins, teleports away at once, then flies across Chunky's square (M1c). */
    private static final boolean FAR_FLIGHT = Boolean.getBoolean("shaderscompanion.devFarFlight");
    /** -PdevFarStoreCache=N: the far store's cache for the run in MB (0, the default: the config's). */
    private static final int FAR_STORE_CACHE = Integer.getInteger("shaderscompanion.devFarStoreCache", 0);
    /** -PdevFarRender=N: the render distance for the run (restored at the end). */
    private static final int FAR_RENDER = Integer.getInteger("shaderscompanion.devFarRender", 0);
    private static int renderBefore = -1;
    /** -PdevFov=N: the field of view for the run (restored at the end; the dev client's is 70, the user's 90). */
    private static final int DEV_FOV = Integer.getInteger("shaderscompanion.devFov", 0);
    private static int fovBefore = -1;
    /**
     * The far scene's world is kept between runs (Distant Horizons' database in it grows run by run, so later runs draw
     * the same terrain); the fill check's is made fresh every run; every other scene's is the usual dev world.
     */
    private static final String WORLD = FAR ? (FAR_FILL ? "shaderscompanion-fill" : "shaderscompanion-far") : "shaderscompanion-dev";
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
        if (FAR_STORE) {
            // On for this run only, before the world loads so the whole join is captured
            FarChecks.force(true, FAR_STORE_CACHE);
            // -PdevFarDiff=N: what differs in the first N sections that change
            FarChecks.logChanges(Integer.getInteger("shaderscompanion.devFarDiff", 0));
            // -PdevFarRaw: sections stored as captured, hidden voxels kept (M1c)
            FarChecks.hideVoxels(!Boolean.getBoolean("shaderscompanion.devFarRaw"));
            // -PdevFarMemoryBorders: the coarser levels' exposure test at node borders sees only nodes in memory (M1a, M1b)
            FarChecks.diskBorders(!Boolean.getBoolean("shaderscompanion.devFarMemoryBorders"));
            // -PdevFarNoStale: sections next to an opened voxel are not marked stale (the tunnel check's negative control)
            FarChecks.markStale(!Boolean.getBoolean("shaderscompanion.devFarNoStale"));
            if (FAR_DRAW) {
                // M2b, M3a: far terrain on for the run (for the pack when it binds the far textures, else the debug view;
                // meshing follows it); -PdevFarOverlay: the debug view even while the pack binds them
                FarChecks.draw(true);
                FarChecks.overlay(FAR_OVERLAY);
            }
            if (FAR_MESH || FAR_DRAW) {
                // M2a: the far terrain meshed for the run, at -PdevFarRange (the config's by default), corner shading with -PdevFarCornerShading
                FarChecks.meshing(FAR_MESH);
                FarChecks.farRange(MESH_RANGE);
                FarChecks.cornerShading(Boolean.getBoolean("shaderscompanion.devFarCornerShading"));
                // Integration round A: -PdevFarEncodedAverage, the faces' colours averaged as before 3.0.0-dev4
                com.rinkynooble.shaderscompanion.client.far.StateColors.encodedAverageForChecks(Boolean.getBoolean("shaderscompanion.devFarEncodedAverage"));
                // -PdevFarFillFrom=1: dark air filled from level 1, as before 3.0.0-dev6 (level 0's caves kept)
                FarChecks.caveFillFrom(Integer.getInteger("shaderscompanion.devFarFillFrom", 0));
            }
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: far store on for the run, heap {} MB", Runtime.getRuntime().maxMemory() >> 20);
        }
        if (FAR_RENDER > 0) {
            renderBefore = mc.options.renderDistance().get();
            mc.options.renderDistance().set(FAR_RENDER);
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: render distance {} for the run (was {})", FAR_RENDER, renderBefore);
        }
        if (DEV_FOV > 0) {
            fovBefore = mc.options.fov().get();
            mc.options.fov().set(DEV_FOV);
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: field of view {} for the run (was {})", DEV_FOV, fovBefore);
        }
        if (SHADERS_LATE > 0) {
            setShaders(mc, false);
        }
        if (FAR && !FAR_FILL && mc.getLevelSource().levelExists(WORLD)) {
            // The far scene's world from an earlier run: loaded, with Distant Horizons' database
            mc.tell(() -> {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: loading world {}", WORLD);
                mc.createWorldOpenFlows().loadLevel(new TitleScreen(), WORLD);
            });
            return;
        }
        if (FAR_FILL) {
            // The fill check's world starts fresh every run
            java.nio.file.Path old = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
            if (java.nio.file.Files.isDirectory(old)) {
                try (var walk = java.nio.file.Files.walk(old)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                } catch (java.io.IOException e) {
                    ShadersCompanion.LOGGER.error("Shaders Companion dev run: deleting {} failed", old, e);
                }
            }
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
                if (flightFrames != null) {
                    flightFrames.add((now - lastFrameStart) / 1.0E6);
                    flightGpu.add((end - start) / 1.0E6);
                }
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
        PassProfiler.log(profileTitle != null ? profileTitle : TOGGLE.isEmpty() ? SCENE : TOGGLE + " round " + timingRound);
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
        if (stopAt >= 0) {
            // M4: finish waits for the glass walls' removal
            if (ticks >= stopAt) {
                stopAt = -1;
                finish(mc);
            }
            return;
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
            // Fix round 3: with -PdevLateShots a shot every 2 seconds while shaders are off (the far terrain's debug view
            // from the join on)
            // -PdevResizeAt=N (with -PdevLateShots): the window goes to 1024 x 768 N ticks in, and back to the run's size N ticks later
            int resizeAt = Integer.getInteger("shaderscompanion.devResizeAt", 0);
            if (mc.player != null && resizeAt > 0 && (lateTicks == resizeAt || lateTicks == 2 * resizeAt)) {
                boolean small = lateTicks == resizeAt;
                String[] size = System.getProperty("shaderscompanion.devSize", "854x480").split("x");
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), small ? 1024 : Integer.parseInt(size[0]), small ? 768 : Integer.parseInt(size[1]));
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: window resized at {} ticks ({})", lateTicks, small ? "1024 x 768" : "back");
            }
            // -PdevLateAloneToggle: the far terrain alone (far show alone) on and off in turn every 40 ticks
            if (mc.player != null && Boolean.getBoolean("shaderscompanion.devLateAloneToggle") && lateTicks % 40 == 20) {
                FarRenderer.aloneForChecks((lateTicks / 40) % 2 == 0);
            }
            // 3.0.0-dev7 check: -PdevLatePitch=N holds the camera at that pitch (and -PdevYaw) while shaders are off, after
            // -PdevLateLift=N blocks up (flying) on the first tick; with -PdevLateShots each shot also writes
            // shaderscompanion-late-<tag>-tNNNN-mask.png from the overlay's own moment (red: the screen's depth reads sky,
            // green: the far layer holds a surface; yellow is where the overlay draws) and logs the depth state
            if (mc.player != null && System.getProperty("shaderscompanion.devLatePitch") != null) {
                if (lateTicks == 0) {
                    mc.player.getAbilities().flying = true;
                    int lift = Integer.getInteger("shaderscompanion.devLateLift", 0);
                    if (lift != 0) {
                        mc.player.connection.sendCommand("tp @s ~ ~" + lift + " ~");
                    }
                }
                if (lateTicks == 2 && Boolean.getBoolean("shaderscompanion.devLateCmdBlocks")) {
                    // -PdevLateCmdBlocks: a floating 9 x 9 slab of command blocks 80 blocks east, 10 below the camera
                    // (past the chunk terrain's cut: far terrain), for farHiddenBlocks
                    mc.player.connection.sendCommand("fill ~76 ~-10 ~-4 ~84 ~-10 ~4 minecraft:command_block");
                }
                mc.player.setXRot(Integer.getInteger("shaderscompanion.devLatePitch", 60));
                mc.player.setYRot(Integer.getInteger("shaderscompanion.devYaw", 0));
                mc.player.setDeltaMovement(0.0, 0.0, 0.0);
            }
            if (mc.player != null && Boolean.getBoolean("shaderscompanion.devLateShots") && lateTicks % 40 == 0
                    && System.getProperty("shaderscompanion.devLatePitch") != null) {
                String mask = String.format("shaderscompanion-late-%s-t%04d-mask.png", System.getProperty("shaderscompanion.devTag", "run"), lateTicks);
                FarRenderer.frameStartProbeForChecks = LateDepthProbe::readFrameStart;
                FarRenderer.overlayProbeForChecks = () -> LateDepthProbe.run(mc, mask);
            }
            if (mc.player != null && Boolean.getBoolean("shaderscompanion.devLateShots") && lateTicks % 40 == 0) {
                Screenshot.grab(mc.gameDirectory, String.format("shaderscompanion-late-%s-t%04d.png", System.getProperty("shaderscompanion.devTag", "run"), lateTicks),
                        mc.getMainRenderTarget(), message -> { });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, shaders off {} ticks: Far terrain: {}", lateTicks, FarTerrain.terrainStatus());
            }
            // -PdevShadersLate: shaders come on once the world has run that long without them (with -PdevLateQuit the game
            // quits instead)
            if (mc.player != null && lateTicks + 1 >= SHADERS_LATE && Boolean.getBoolean("shaderscompanion.devLateQuit")) {
                lateTicks = Integer.MIN_VALUE / 2;
                finish(mc);
                return;
            }
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
        if (farHolding && phase == 4) {
            hold(mc);
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
        boolean bedroom = SCENE.equals("bedroom");
        boolean corridor = SCENE.equals("corridor");
        boolean street = SCENE.equals("street");
        boolean darkSpot = SCENE.equals("darkspot");
        boolean meadow = SCENE.equals("meadow") || darkSpot;
        boolean lodMirror = SCENE.equals("lodmirror");
        boolean glass = mirror || corner || grass || shadows || aquarium || overlay || city || reflectPool || facade || glassBox || skyRoom || enclosure || lodMirror || street || store || lampGlass || bedroom || corridor || SCENE.equals("match") || SCENE.equals("materials");
        boolean shore = SCENE.equals("shore");
        boolean ocean = SCENE.equals("ocean") || shore;
        boolean match = SCENE.equals("match");
        boolean materials = SCENE.equals("materials");
        boolean rainCover = SCENE.equals("raincover");
        boolean far = FAR;
        if (far && !FAR_FILL && !FAR_SEARCH && phase >= 1 && mc.player != null) {
            // The far scene's view held still (the window takes mouse input while it has the focus); M4: -PdevYaw with
            // -PdevFarGlass, to look at a glass wall, and in a held camera's toggle rounds (until M4 they looked east, the
            // far scene's heading, whatever -PdevYaw said: this ran after hold)
            float farYaw = FAR_GLASS || farHolding ? FAR_SHIFT_YAW : FAR_YAW;
            mc.player.setYRot(farYaw);
            mc.player.yRotO = farYaw;
            mc.player.setXRot(FAR_PITCH);
            mc.player.xRotO = FAR_PITCH;
        }
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
            } else if (far) {
                // A high vantage over mountains and sea (FAR_X/Y/Z, FAR_YAW: found by -PdevFarSearch), standing on a barrier;
                // -PdevRain: rain from the start (M3c; the far store check leaves phase 1 at once, before the scenes' rain)
                mc.options.hideGui = true;
                for (String command : new String[]{"time set " + Integer.getInteger("shaderscompanion.devTime", 6000),
                        Boolean.getBoolean("shaderscompanion.devRain") ? "weather rain" : "weather clear",
                        // Round B: -PdevCycle lets the time run (the far shadow map's redraws as the sun moves)
                        "gamerule doDaylightCycle " + Boolean.getBoolean("shaderscompanion.devCycle"), "gamerule doWeatherCycle false", "gamerule doMobSpawning false"}) {
                    mc.player.connection.sendCommand(command);
                }
                MinecraftServer server = mc.getSingleplayerServer();
                if (FAR_SEARCH) {
                    server.execute(() -> {
                        farSearch(server);
                        mc.tell(() -> finish(mc));
                    });
                } else if (FAR_FILL) {
                    // Fresh terrain far from the spawn, looking +X, the way the flight goes
                    mc.player.connection.sendCommand("tp @s " + FILL_X + ".5 " + FILL_Y + " 0.5 -90 " + FAR_PITCH);
                } else {
                    if (FAR_FREEZE && dhLoaded()) {
                        DhProbe.setGeneration(false);
                        ShadersCompanion.LOGGER.info("Shaders Companion dev run: far scene with Distant Horizons' generation paused");
                    }
                    server.execute(() -> {
                        var level = server.overworld();
                        if (farY < 0) {
                            // The feet two blocks above the generated ground (leaves left out), on a barrier
                            level.getChunk(FAR_X >> 4, FAR_Z >> 4);
                            farY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, FAR_X, FAR_Z) + 1;
                            ShadersCompanion.LOGGER.info("Shaders Companion dev run: far scene feet at y {} (from the ground)", farY);
                        }
                        // Placed by the server, which loads the chunk; the space above it cleared (a tree, say)
                        level.setBlock(new BlockPos(FAR_X, farY - 1, FAR_Z), Blocks.BARRIER.defaultBlockState(), 3);
                        for (int y = farY; y <= farY + 2; y++) {
                            level.setBlock(new BlockPos(FAR_X, y, FAR_Z), Blocks.AIR.defaultBlockState(), 3);
                        }
                        farGlass(level, false);
                        if (FAR_STORE && Boolean.getBoolean("shaderscompanion.devFarShapes")) {
                            farShapes(level, true);
                        }
                        if (FAR_STORE && FAR_TUNNEL) {
                            farTunnel(level, 0);
                        }
                        int chunkyRadius = Integer.getInteger("shaderscompanion.devFarChunky", 0);
                        if (chunkyRadius > 0) {
                            farChunky(chunkyRadius);
                        }
                        mc.tell(() -> mc.player.connection.sendCommand("tp @s " + FAR_X + ".5 " + farY + " " + FAR_Z + ".5 " + FAR_YAW + " " + FAR_PITCH));
                    });
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the far scene (DH {}, range {} blocks)", dhLoaded() ? "loaded" : "not loaded",
                        dhLoaded() ? farDhRange() : 0);
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
            } else if (bedroom) {
                // The user's dark bedroom with a black glass wall (2026-11-22, the seam between reflections of what is on
                // screen and what isn't): a white concrete room at night lit by one lantern on the floor, a black stained
                // glass wall on the right reflecting the quartz wardrobe, wool dresser and chest on the left and oak and
                // red wool furniture behind the camera
                mc.options.hideGui = true;
                for (String command : new String[]{
                        "time set " + Integer.getInteger("shaderscompanion.devTime", 18000), "weather clear", "gamerule doDaylightCycle false", "gamerule doMobSpawning false",
                        "tp @s " + (Math.floor(mc.player.getX()) + 0.5) + " " + (Math.floor(mc.player.getY()) + 20) + " " + (Math.floor(mc.player.getZ()) + 0.5) + " "
                                + Integer.getInteger("shaderscompanion.devYaw", 25) + " " + Integer.getInteger("shaderscompanion.devPitch", 10),
                        "fill ~-6 ~-2 ~-6 ~6 ~6 ~10 minecraft:air",
                        "fill ~-5 ~-1 ~-5 ~5 ~4 ~9 minecraft:white_concrete", "fill ~-4 ~ ~-4 ~4 ~3 ~8 minecraft:air",
                        "fill ~-4 ~-1 ~-4 ~4 ~-1 ~8 minecraft:dark_oak_planks",
                        "fill ~-3 ~ ~1 ~-3 ~3 ~7 minecraft:black_stained_glass",
                        "fill ~3 ~ ~4 ~4 ~2 ~5 minecraft:quartz_block", "fill ~3 ~ ~1 ~4 ~ ~2 minecraft:white_wool",
                        "setblock ~2 ~ ~7 minecraft:chest[facing=west]", "setblock ~1 ~ ~8 minecraft:lantern",
                        "fill ~3 ~ ~-3 ~4 ~2 ~-2 minecraft:oak_planks", "fill ~-1 ~ ~-4 ~1 ~1 ~-4 minecraft:red_wool"}) {
                    mc.player.connection.sendCommand(command);
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run: built the bedroom scene");
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
            phase = far && FAR_SEARCH ? 9 : far && FAR_FILL ? 7 : 1;
            phaseTick = ticks;
            farRefTick = ticks;
        } else if (phase == 9) {
            // -PdevFarSearch: the search runs on the server and quits
        } else if ((phase == 1 || phase >= 10) && far && FAR_STORE && !FAR_DH_SHOT) {
            farStoreCheck(mc);
        } else if (phase == 1 && far && FAR_DH_SHOT) {
            // -PdevFarDhShot: one shot once Distant Horizons has settled, for comparing with the far terrain's (M2b); with
            // -PdevFarStore -PdevFarDraw the far terrain is on too and must draw nothing (M3a: farTerrain = auto)
            if (ticks - phaseTick == 1 && FAR_GLASS) {
                placeGlass(mc, new BlockPos(FAR_X, farY, FAR_Z), FAR_GLASS_YAW);
            }
            if (ticks - phaseTick == 200 && FAR_DRAW) {
                dhGlFramesFrom = FarRenderer.glFramesForChecks();
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far scene with Distant Horizons: Far terrain: {}", FarTerrain.terrainStatus());
            }
            if (farSettled(mc) && (!FAR_DRAW || ticks - phaseTick > 200)) {
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-fardraw-" + System.getProperty("shaderscompanion.devTag", "run") + "-dh.png",
                        mc.getMainRenderTarget(), message -> { });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far scene: Distant Horizons shot taken | {} | {}", vram(), farProbe(mc, true));
                if (FAR_DRAW) {
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, far scene with Distant Horizons drawing: frames whose hook made far terrain GL calls "
                                    + "since 10 s in: {} (of {} s); Far terrain: {} | Far store: {}", FarRenderer.glFramesForChecks() - dhGlFramesFrom,
                            (ticks - phaseTick - 200) / 20, FarTerrain.terrainStatus(), FarStore.status());
                    // M4: the mod's views draw its far terrain while Distant Horizons draws the screen
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain in the views with Distant Horizons drawing: {} | {}", viewsLine(),
                            String.join(" | ", DebugOverlay.statusLines()));
                    try {
                        ShadersCompanion.LOGGER.info("Shaders Companion dev run, views dumped with Distant Horizons drawing as {}", CubeDump.dump());
                    } catch (java.io.IOException e) {
                        ShadersCompanion.LOGGER.error("Shaders Companion dev run: dump failed", e);
                    }
                }
                phase = 2000;
                phaseTick = ticks;
            }
        } else if (phase == 2000) {
            // M4: with the far terrain on, Distant Horizons' rendering turned off through its API for 10 s (Distant Horizons
            // writes it to its config file: put the file back afterwards): the mod's check (FarCoexistence, by reflection) must
            // see it and draw the screen's far terrain again
            boolean dhOff = FAR_DRAW && dhLoaded();
            if (dhOff && ticks - phaseTick == 1) {
                DhProbe.setRendering(false);
            } else if (dhOff && ticks - phaseTick == 200) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far scene with Distant Horizons' rendering turned off: Far terrain: {}", FarTerrain.terrainStatus());
                DhProbe.clearRendering();
            }
            if (ticks - phaseTick >= (dhOff ? 240 : 40)) {
                finish(mc);
            }
        } else if (phase == 1 && far && !farSettled(mc)) {
            // The far scene waits until Distant Horizons' LODs (and Chunky's chunks) have settled, at most -PdevWait ticks
        } else if (phase == 7) {
            farFill(mc, false);
        } else if (phase == 8) {
            farFill(mc, true);
        } else if (phase == 6 && !farRoundReady && ticks - phaseTick >= FAR_SETTLE_TICKS) {
            // Distant Horizons rebuilds its render data from its database when its rendering comes back on: the round's
            // timed frames start once the uploaded sections are back to the settled count and bytes
            farRoundReady = farLodsBack(mc);
            if (farRoundReady) {
                phaseTick = ticks - FAR_SETTLE_TICKS;
            }
        } else if (phase == 6 && farRoundReady && ticks - phaseTick >= FAR_SETTLE_TICKS + TIMING_TICKS) {
            farRoundEnd(mc);
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
        } else if (phase == 1 && (far || (pool || hand || mobs || corner || grass || ocean || match || materials || shadows || aquarium || overlay || turn || city || reflectPool || facade || glassBox || street || stairWall || doorWall || store || lampGlass || bedroom || corridor ? ticks - phaseTick >= WAIT_TICKS : CubeView.framesDrawn() >= START_FRAME + DUMP_FRAMES))) {
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
                } else if (!pool && !hand && !glass && !ocean && !far) {
                    // Just below the horizon of the +X face (face 0: column 0, row 0): far ground, LODs with Distant Horizons
                    for (int percent : new int[]{46, 43, 40}) {
                        ShadersCompanion.LOGGER.info("Shaders Companion dev run, +X horizon {}%: {}", percent, CubeDump.describeTexel(size / 2, size * percent / 100));
                    }
                }
                if (overlay) {
                    logOverlayContainer(mc);
                }
                Screenshot.grab(mc.gameDirectory, prefix + (far ? "-far.png" : pool || hand || glass || ocean ? "-on.png" : "-main.png"), mc.getMainRenderTarget(), message -> { });
            } catch (Exception e) {
                ShadersCompanion.LOGGER.error("Shaders Companion dev run: saving failed", e);
            }
            ShadersCompanion.LOGGER.info("Shaders Companion dev run at {}: {}", mc.player == null ? "?" : mc.player.position(),
                    String.join(" | ", DebugOverlay.statusLines()));
            if (System.getProperty("shaderscompanion.devCells") != null) {
                logRayCoverage(mc);
            }
            ShadersCompanion.LOGGER.info("Shaders Companion dev run timing with the mod on: {}", frameTiming());
            if (far) {
                // Timing rounds with Distant Horizons on and off, then with the glass walls and the mirror view
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far scene rounds start: {} | {}", vram(), farProbe(mc, true));
                timingRound = 0;
                farRound(mc, 0);
                phase = 6;
                phaseTick = ticks;
            } else if (!TOGGLE.isEmpty() && !shadows) {
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
            if (HEAP) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, heap with {} {}: {}", TOGGLE, label, heapLine());
            }
            if (FAR) {
                // Round B: the far shadow map over the round
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far shadow map with {} {}: {}", TOGGLE, label, shadowLine());
                // M4: the far terrain in the views over the round
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain in the views with {} {}: {} | Far terrain: {}", TOGGLE, label, viewsLine(),
                        FarTerrain.terrainStatus());
                if (FAR_VIEW_DUMP && timingRound < 2) {
                    try {
                        ShadersCompanion.LOGGER.info("Shaders Companion dev run, views dumped with {} {} as {}", TOGGLE, label, CubeDump.dump());
                    } catch (java.io.IOException e) {
                        ShadersCompanion.LOGGER.error("Shaders Companion dev run: dump failed", e);
                    }
                }
            }
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

    // The far scene (-PdevScene=far: M0 of the far-terrain work, research/lod/BASELINE.md)

    /**
     * The far scene's vantage and view, the best candidate of -PdevFarSearch in the dev worlds' seed (20261108; 2026-11-22:
     * a 175-block peak 640 blocks west of the spawn, looking east over sea and peaks). The feet stand on a barrier a block
     * above the ground; FAR_Y_FIXED is that height (176, logged by the first run), or -1 to take it from the generated ground.
     */
    private static final int FAR_X = -544;
    private static final int FAR_Y_FIXED = 186;
    private static final int FAR_Z = -320;
    private static final float FAR_YAW = 270.0F;
    private static int farY = FAR_Y_FIXED;
    /** The far scene's pitch (-PdevPitch, 6 degrees down by default): the horizon a little above the middle. */
    private static final float FAR_PITCH = Integer.getInteger("shaderscompanion.devPitch", 6);
    /** -PdevWait for the far scene: the longest it waits for Distant Horizons' LODs to settle (30 minutes by default). */
    private static final int FAR_WAIT_TICKS = Integer.getInteger("shaderscompanion.devWait", 36000);
    /** Settled: the uploaded LOD sections' count and bytes within 0.5% for this long, after at least FAR_MIN_WAIT. */
    private static final int FAR_STABLE_TICKS = 2400;
    private static final int FAR_MIN_WAIT_TICKS = 1200;
    /** -PdevFarSettle: ticks a far round waits after its changes (a pack reload) before its timed frames. */
    private static final int FAR_SETTLE_TICKS = Integer.getInteger("shaderscompanion.devFarSettle", 200);
    /** The fill check: fresh terrain this far along +X from the spawn, at this height, held still and then flown. */
    private static final int FILL_X = 30000;
    private static final int FILL_Y = 300;
    private static final int FILL_HOLD_TICKS = Integer.getInteger("shaderscompanion.devFillHold", 4800);
    private static final int FILL_FLY_TICKS = Integer.getInteger("shaderscompanion.devFillFly", 2400);
    /** 30 blocks a second. */
    private static final double FILL_SPEED = 1.5;
    /**
     * The far scene's timing rounds: Distant Horizons drawing (1, its own rendering and the mod's views) or not (0); the
     * two black glass walls (1) or none (0); the mirror view's planes (0: the pack's mirror off). Each round reloads the
     * shader pack.
     */
    private static final int[][] FAR_ROUNDS = {
            {1, 0, 0}, {0, 0, 0}, {1, 0, 0}, {0, 0, 0},
            {1, 1, 0}, {0, 1, 0}, {1, 1, 1}, {0, 1, 1}, {1, 1, 2}, {0, 1, 2}, {1, 1, 0}, {0, 1, 0}};
    private static int farRefTick;
    private static long farRefCount = -1;
    private static long farRefBytes = -1;
    private static boolean farGlassPlaced;
    private static boolean farRoundReady;
    private static int farBackTicks;
    private static String profileTitle;

    /** Distant Horizons loaded (-PdevDh): checked here, so DhProbe and its Distant Horizons classes load only then. */
    private static boolean dhLoaded() {
        return net.minecraftforge.fml.ModList.get().isLoaded("distanthorizons");
    }

    private static String farLabel(int[] spec) {
        return "DH " + (spec[0] == 1 ? "on" : "off") + ", " + (spec[1] == 1 ? "glass walls" : "no glass") + ", "
                + (spec[2] == 0 ? "mirror off" : "mirror " + spec[2] + (spec[2] == 1 ? " plane" : " planes"));
    }

    private static int farDhRange() {
        return dhLoaded() ? DhProbe.range() : 0;
    }

    /** Distant Horizons' LOD sections (DhProbe) seen from the camera along the view, or why there are none. */
    private static String farProbe(Minecraft mc, boolean f3) {
        if (!dhLoaded() || mc.player == null) {
            return "DH probe: Distant Horizons not loaded";
        }
        try {
            float yaw = FAR_FILL ? -90.0F : FAR_YAW;
            return DhProbe.status(mc.player.getX(), mc.player.getZ(), -Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw)), f3);
        } catch (RuntimeException e) {
            return "DH probe failed: " + e;
        }
    }

    /** Video memory from GL_NVX_gpu_memory_info (the whole GPU's, as the driver reports it), in MiB. */
    private static String vram() {
        if (!org.lwjgl.opengl.GL.getCapabilities().GL_NVX_gpu_memory_info) {
            return "VRAM: no GL_NVX_gpu_memory_info";
        }
        int dedicated = org.lwjgl.opengl.GL11C.glGetInteger(0x9047);
        int total = org.lwjgl.opengl.GL11C.glGetInteger(0x9048);
        int available = org.lwjgl.opengl.GL11C.glGetInteger(0x9049);
        int evictions = org.lwjgl.opengl.GL11C.glGetInteger(0x904A);
        int evicted = org.lwjgl.opengl.GL11C.glGetInteger(0x904B);
        return String.format("VRAM (NVX): %.0f MiB in use of %.0f (dedicated %.0f, %.0f available), %d evictions (%.0f MiB)", (total - available) / 1024.0,
                total / 1024.0, dedicated / 1024.0, available / 1024.0, evictions, evicted / 1024.0);
    }

    /**
     * The far scene's wait: logs every 10 seconds and is done once Distant Horizons' uploaded sections have held steady
     * for two minutes (and Chunky has finished, with -PdevFarChunky), or after -PdevWait ticks.
     */
    private static boolean farSettled(Minecraft mc) {
        int waited = ticks - phaseTick;
        if (waited % 200 == 0) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, far scene waiting {} s: frame {} ms, GPU {} ms | {} | {}{}", waited / 20,
                    String.format("%.2f", median(frameMillis)), String.format("%.2f", median(gpuMillis)), vram(), farProbe(mc, waited % 1200 == 0),
                    chunkyRunning() ? " | Chunky running" : "");
        }
        if (waited >= FAR_WAIT_TICKS) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, far scene: waited {} s (the most -PdevWait allows); LODs last changed {} s ago{}",
                    waited / 20, (ticks - farRefTick) / 20, chunkyRunning() ? ", Chunky still running" : "");
            return true;
        }
        if (chunkyRunning()) {
            farRefTick = ticks;
            return false;
        }
        if (!dhLoaded()) {
            return waited >= Math.min(FAR_WAIT_TICKS, 600);
        }
        if (waited % 20 == 0) {
            long[] now = DhProbe.counts();
            if (Math.abs(now[0] - farRefCount) > Math.max(2, farRefCount / 200) || Math.abs(now[1] - farRefBytes) > Math.max(65536, farRefBytes / 200)) {
                farRefCount = now[0];
                farRefBytes = now[1];
                farRefTick = ticks;
            }
        }
        if (waited >= FAR_MIN_WAIT_TICKS && ticks - farRefTick >= FAR_STABLE_TICKS) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, far scene settled after {} s: {} uploaded sections, {} MiB, steady for {} s", waited / 20,
                    farRefCount, String.format("%.1f", farRefBytes / 1048576.0), (ticks - farRefTick) / 20);
            return true;
        }
        return false;
    }

    /** Applies a far round: Distant Horizons drawing or not, the glass walls, the pack's mirror and its planes (a reload). */
    private static void farRound(Minecraft mc, int round) {
        int[] spec = FAR_ROUNDS[round];
        boolean dh = spec[0] == 1;
        boolean glassWanted = spec[1] == 1;
        if (glassWanted != farGlassPlaced) {
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> farGlass(server.overworld(), glassWanted));
            farGlassPlaced = glassWanted;
        }
        try {
            Iris.getShaderPackOptionQueue().put("COMPANION_MIRROR", Boolean.toString(spec[2] > 0));
            if (spec[2] > 0) {
                Iris.getShaderPackOptionQueue().put("COMPANION_MIRROR_PLANES", Integer.toString(spec[2]));
            }
            // Distant Horizons' rendering changed through its API: Oculus's own check reloads the pack (with the queued
            // options) when it sees the change. A reload of our own at the same time, before that check, compiled
            // dh_terrain with the old state and failed ("dhMaterialId" declared twice), so Oculus reloads it here
            boolean before = net.irisshaders.iris.compat.dh.DHCompat.hasRenderingEnabled();
            if (dhLoaded()) {
                DhProbe.setRendering(dh);
            }
            if (!dhLoaded() || before == dh) {
                Iris.reload();
            } else {
                net.irisshaders.iris.compat.dh.DHCompat.checkFrame();
            }
        } catch (Exception e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: far round {} failed", round, e);
        }
        farRoundReady = false;
        farBackTicks = 0;
        ShadersCompanion.LOGGER.info("Shaders Companion dev run: far round {}: {}", round, farLabel(spec));
    }

    /**
     * A far round with Distant Horizons on may start timing once its uploaded sections are back to at least 99% of the
     * settled count and 98% of the settled bytes (checked once a second), or after a minute (logged either way).
     */
    private static boolean farLodsBack(Minecraft mc) {
        if (!dhLoaded() || FAR_ROUNDS[timingRound][0] == 0 || farRefCount <= 0) {
            return true;
        }
        if (++farBackTicks % 20 != 0) {
            return false;
        }
        long[] now = DhProbe.counts();
        boolean back = now[0] >= farRefCount * 0.99 && now[1] >= farRefBytes * 0.98;
        if (back || farBackTicks >= 1200) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, far round {}: LODs {} after {} s more ({} of {} sections, {} of {} MiB)", timingRound,
                    back ? "back" : "not back", farBackTicks / 20, now[0], farRefCount, String.format("%.1f", now[1] / 1048576.0),
                    String.format("%.1f", farRefBytes / 1048576.0));
            return true;
        }
        return false;
    }

    private static void farRoundEnd(Minecraft mc) {
        int[] spec = FAR_ROUNDS[timingRound];
        String[] lines = DebugOverlay.statusLines();
        profileTitle = "far round " + timingRound + ": " + farLabel(spec);
        ShadersCompanion.LOGGER.info("Shaders Companion dev run timing, far round {} ({}): {} | {} | {} | {}", timingRound, farLabel(spec), frameTiming(), vram(),
                lines.length > 4 ? lines[1] + " | " + lines[3] + " | " + lines[4] : "", farProbe(mc, false));
        profileTitle = null;
        Screenshot.grab(mc.gameDirectory, prefix + "-far" + timingRound + ".png", mc.getMainRenderTarget(), message -> { });
        if (HEAP) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, heap in far round {} ({}): {}", timingRound, farLabel(spec), heapLine());
        }
        phaseTick = ticks;
        if (++timingRound < FAR_ROUNDS.length) {
            farRound(mc, timingRound);
            return;
        }
        // Back to Distant Horizons drawing, the mod's setting on, no glass
        if (dhLoaded()) {
            DhProbe.clearRendering();
            DhProbe.clearGeneration();
        }
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> farGlass(server.overworld(), false));
        ShadersCompanion.LOGGER.info("Shaders Companion dev run, far scene done: {} | {}", vram(), farProbe(mc, true));
        finish(mc);
    }

    /**
     * The far scene's two black glass walls, 4 blocks to either side of the camera along the view, from 3 blocks below
     * the feet to 6 above and from 3 behind to 12 ahead: placed only in air, removed only where they are glass.
     */
    private static void farGlass(net.minecraft.server.level.ServerLevel level, boolean place) {
        glassWalls(level, new BlockPos(FAR_X, farY, FAR_Z), FAR_YAW, place);
    }

    /**
     * Two black glass walls, 4 blocks to either side of a spot along a heading, from 3 blocks below it to 6 above and
     * from 3 behind to 12 ahead: placed only in air, removed only where they are glass (the far scene's, and M4's at a
     * held camera).
     */
    private static void glassWalls(net.minecraft.server.level.ServerLevel level, BlockPos at, float yaw, boolean place) {
        double forwardX = -Math.sin(Math.toRadians(yaw));
        double forwardZ = Math.cos(Math.toRadians(yaw));
        int changed = 0;
        for (int side : new int[]{-4, 4}) {
            for (int ahead = -3; ahead <= 12; ahead++) {
                for (int y = at.getY() - 3; y <= at.getY() + 6; y++) {
                    BlockPos pos = trunkOffset(at, y, ahead, side, forwardX, forwardZ);
                    var state = level.getBlockState(pos);
                    if (place ? state.isAir() : state.is(Blocks.BLACK_STAINED_GLASS)) {
                        level.setBlock(pos, (place ? Blocks.BLACK_STAINED_GLASS : Blocks.AIR).defaultBlockState(), 2);
                        changed++;
                    }
                }
            }
        }
        ShadersCompanion.LOGGER.info("Shaders Companion dev run: glass walls at {} along yaw {} {} ({} blocks)", at.toShortString(), yaw, place ? "placed" : "removed",
                changed);
    }

    /**
     * The fill check (-PdevFarFill), in a fresh world: from FILL_X (30,000 blocks along +X, never generated) held still
     * for -PdevFillHold ticks, then flown along +X at 30 blocks a second for -PdevFillFly ticks; Distant Horizons'
     * sections logged once a second (DhProbe: the count, and how far along the flight line they reach).
     */
    private static void farFill(Minecraft mc, boolean flying) {
        if (mc.player == null) {
            return;
        }
        int t = ticks - phaseTick;
        if (flying) {
            mc.player.setDeltaMovement(0.0, 0.0, 0.0);
            mc.player.setPos(mc.player.getX() + FILL_SPEED, FILL_Y, 0.5);
        }
        mc.player.setYRot(-90.0F);
        mc.player.yRotO = -90.0F;
        mc.player.setXRot(FAR_PITCH);
        mc.player.xRotO = FAR_PITCH;
        if (t % 20 == 0) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, fill {} {} s at x {}: frame {} ms, GPU {} ms | {}", flying ? "fly" : "hold", t / 20,
                    String.format("%.0f", mc.player.getX()), String.format("%.2f", median(frameMillis)), String.format("%.2f", median(gpuMillis)),
                    farProbe(mc, t % 200 == 0));
        }
        if (!flying && t >= FILL_HOLD_TICKS) {
            phase = 8;
            phaseTick = ticks;
        } else if (flying && t >= FILL_FLY_TICKS) {
            finish(mc);
        }
    }

    /** -PdevFarChunky=R: Chunky (-PdevChunky) generates real chunks in a square of radius R blocks around the vantage. */
    private static void farChunky(int radius) {
        try {
            Object chunky = Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
            Object api = chunky.getClass().getMethod("getApi").invoke(chunky);
            Class<?> apiClass = Class.forName("org.popcraft.chunky.api.ChunkyAPI");
            boolean started = (boolean) apiClass.getMethod("startTask", String.class, String.class, double.class, double.class, double.class, double.class, String.class)
                    .invoke(api, "minecraft:overworld", "square", FAR_X + 0.5, FAR_Z + 0.5, (double) radius, (double) radius, "concentric");
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: Chunky task around {} {} radius {}: {}", FAR_X, FAR_Z, radius, started ? "started" : "not started");
        } catch (ReflectiveOperationException | LinkageError e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: Chunky failed (needs -PdevChunky)", e);
        }
    }

    private static boolean chunkyRunning() {
        if (Integer.getInteger("shaderscompanion.devFarChunky", 0) <= 0) {
            return false;
        }
        try {
            Object chunky = Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
            Object api = chunky.getClass().getMethod("getApi").invoke(chunky);
            return (boolean) Class.forName("org.popcraft.chunky.api.ChunkyAPI").getMethod("isRunning", String.class).invoke(api, "minecraft:overworld");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    /**
     * -PdevFarSearch: the terrain's height every 32 blocks within 4,096 of the spawn from the world generator's noise
     * (no chunks generated), then for each peak of 140 blocks or more and each view along an axis, the share of sea
     * (ground below 58) and of mountains (110 and up) in a 100 degree cone from 128 to 4,000 blocks out. Logs the best
     * candidates and the surface of the best one (that chunk generated).
     */
    private static void farSearch(MinecraftServer server) {
        var level = server.overworld();
        var source = level.getChunkSource();
        var generator = source.getGenerator();
        var random = source.randomState();
        BlockPos spawn = level.getSharedSpawnPos();
        int step = 32;
        int half = 4096 / step;
        int n = half * 2 + 1;
        int[] height = new int[n * n];
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                height[i * n + j] = generator.getBaseHeight(spawn.getX() + (i - half) * step, spawn.getZ() + (j - half) * step,
                        net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, random);
            }
        }
        ShadersCompanion.LOGGER.info("Shaders Companion dev run, far search: {} heights around the spawn {} in {} s", n * n, spawn,
                String.format("%.1f", (System.nanoTime() - start) / 1.0e9));
        java.util.List<double[]> found = new java.util.ArrayList<>();
        for (int i = 4; i < n - 4; i++) {
            for (int j = 4; j < n - 4; j++) {
                int h = height[i * n + j];
                if (h < 140 || (i - half) * (i - half) + (j - half) * (j - half) > (3000 / step) * (3000 / step)) {
                    continue;
                }
                boolean peak = true;
                for (int a = -2; a <= 2 && peak; a++) {
                    for (int b = -2; b <= 2; b++) {
                        if (height[(i + a) * n + j + b] > h) {
                            peak = false;
                            break;
                        }
                    }
                }
                if (!peak) {
                    continue;
                }
                for (int yaw = 0; yaw < 360; yaw += 90) {
                    int samples = 0;
                    int sea = 0;
                    int mountain = 0;
                    int blocked = 0;
                    for (int d = 128; d <= 4000; d += 64) {
                        for (int a = -50; a <= 50; a += 5) {
                            double angle = Math.toRadians(yaw + a);
                            int si = i + (int) Math.round(-Math.sin(angle) * d / step);
                            int sj = j + (int) Math.round(Math.cos(angle) * d / step);
                            if (si < 0 || sj < 0 || si >= n || sj >= n) {
                                continue;
                            }
                            int sh = height[si * n + sj];
                            samples++;
                            if (sh < 58) {
                                sea++;
                            } else if (sh >= 110) {
                                mountain++;
                            }
                            if (d < 600 && sh > h - 5) {
                                blocked++;
                            }
                        }
                    }
                    if (samples == 0) {
                        continue;
                    }
                    double seaShare = sea / (double) samples;
                    double mountainShare = mountain / (double) samples;
                    double score = Math.min(seaShare, 0.5) * Math.min(mountainShare, 0.35) * Math.max(0.0, 1.0 - 4.0 * blocked / samples) * (h / 160.0);
                    found.add(new double[]{spawn.getX() + (i - half) * step, spawn.getZ() + (j - half) * step, h, yaw, seaShare, mountainShare,
                            blocked / (double) samples, score});
                }
            }
        }
        found.sort((x, y) -> Double.compare(y[7], x[7]));
        for (int k = 0; k < Math.min(15, found.size()); k++) {
            double[] c = found.get(k);
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, far search candidate {}: x {} z {} height {} yaw {}: sea {}%, mountains {}%, near ground above {}%, score {}",
                    k, (int) c[0], (int) c[1], (int) c[2], (int) c[3], Math.round(c[4] * 100), Math.round(c[5] * 100), Math.round(c[6] * 100),
                    String.format("%.4f", c[7]));
        }
        if (!found.isEmpty()) {
            double[] best = found.get(0);
            int x = (int) best[0];
            int z = (int) best[1];
            level.getChunk(x >> 4, z >> 4);
            int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, far search best: x {} z {} yaw {}, generated surface {} (noise height {})", x, z, (int) best[3],
                    surface, (int) best[2]);
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
        if (TOGGLE.equals("time")) {
            // The dev4 smoke check: -PdevToggle=time:6000:12500:18000, the time of day per round (noon, sunset, night)
            Minecraft.getInstance().player.connection.sendCommand("time set " + text);
            return;
        }
        if (TOGGLE.equals("renderDistance")) {
            // Integration round A: -PdevToggle=renderDistance:5:8, the render distance per round (restored at the end with -PdevFarRender);
            // round B: a value "5@0.85" also sets the far terrain's planted faces' colour factor for the round (the mod's own otherwise)
            String[] parts = text.split("@");
            Minecraft.getInstance().options.renderDistance().set(Integer.parseInt(parts[0]));
            FarRenderer.plantShadeForChecks(parts.length > 1 ? Float.parseFloat(parts[1]) : Float.NaN);
            return;
        }
        try {
            if (TOGGLE.equals("profile")) {
                // -PdevToggle=profile:LOW:MEDIUM:HIGH: the pack's preset of that name, all its options at once
                net.irisshaders.iris.shaderpack.option.Profile[] found = new net.irisshaders.iris.shaderpack.option.Profile[1];
                Iris.getCurrentPack().ifPresent(pack -> pack.getMenuContainer().getProfiles().forEach((name, profile) -> {
                    if (name.equals(text)) {
                        found[0] = profile;
                    }
                }));
                if (found[0] == null) {
                    ShadersCompanion.LOGGER.error("Shaders Companion dev run: no preset {}", text);
                    return;
                }
                Iris.queueShaderPackOptionsFromProfile(found[0]);
            } else {
                Iris.getShaderPackOptionQueue().put(TOGGLE, text);
            }
            Iris.reload();
        } catch (Exception e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: setting {} failed", TOGGLE, e);
        }
    }

    /** Turns Oculus's shaders on or off, as its toggle key does (saved in its settings file). */
    // The far store check (-PdevScene=far -PdevFarStore: M1a of the far-terrain work, research/lod/M1.md)

    /** The 512-block square around the vantage the maps of every level show (aligned to level 4's 16-block voxels). */
    private static final int STORE_MAP_X = Math.floorDiv(FAR_X - 256, 16) * 16;
    private static final int STORE_MAP_Z = Math.floorDiv(FAR_Z - 256, 16) * 16;
    private static final int STORE_MAP_SIZE = 512;
    /** The flight: east across Chunky's square (x -1056 to -32) at render distance 5, never past what it generated. */
    private static final int STORE_FLY_X = -980;
    private static final int STORE_FLY_Y = 230;
    private static final int STORE_FLY_TICKS = Integer.getInteger("shaderscompanion.devFillFly", 520);
    private static int storeSettledTicks;
    private static int storeMapsLeft;
    private static double storeFlyMax;
    private static double storeFlySum;
    private static int storeFlySeconds;

    /**
     * Phases: 1 (and 10) wait until the store has settled; 11 waits for the maps and stats; 12 has the store off for 2
     * seconds, then on again; 13 moves to the flight's start at render distance 5 and waits 5 seconds; 14 flies.
     */
    private static void farStoreCheck(Minecraft mc) {
        int t = ticks - phaseTick;
        double[] frame = FarChecks.frameMicros();
        if (phase == 1) {
            phase = FAR_IMPORT > 0 ? 40 : FAR_FLIGHT ? 50 : FAR_DRAW ? 80 : FAR_MESH ? 60 : 10;
            phaseTick = ticks;
            storeSettledTicks = 0;
            return;
        }
        if (phase >= 80) {
            farDrawCheck(mc, t);
            return;
        }
        if (phase >= 60) {
            farMeshCheck(mc, t);
            return;
        }
        if (phase >= 50) {
            farStoreFlight(mc, t);
            return;
        }
        if (phase >= 30) {
            farStoreDisk(mc, t);
            return;
        }
        if (phase == 10) {
            if (t % 20 == 0 && (frame[2] > 0 || t < 1200)) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store {} s: main thread {} us a frame (max {}), {} sections waiting, frame {} ms | {}", t / 20,
                        String.format("%.0f", frame[0]), String.format("%.0f", frame[1]), (int) frame[2], String.format("%.2f", median(frameMillis)), FarStore.status());
            }
            storeSettledTicks = FarChecks.settled() ? storeSettledTicks + 1 : 0;
            boolean timeUp = t >= Math.min(FAR_WAIT_TICKS, 6000);
            if (t >= 1200 && storeSettledTicks >= 100 || timeUp) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store {} after {} s: {}", timeUp ? "not settled" : "settled", t / 20, FarStore.status());
                if (FAR_REJOIN) {
                    storeMapsLeft = 1;
                    logWhenDone(FarStore.stats());
                    phase = 30;
                    phaseTick = ticks;
                    return;
                }
                storeMapsLeft = 7;
                for (int level = 0; level <= 4; level++) {
                    logWhenDone(FarStore.map(level, STORE_MAP_X, STORE_MAP_Z, STORE_MAP_SIZE, STORE_MAP_SIZE, "shaderscompanion-lod-far-L" + level + ".png")
                            .thenApply(line -> new String[]{line}));
                }
                // And the command's own: level 0, 4 nodes around the camera
                logWhenDone(FarStore.mapAroundCamera(0, 4).thenApply(line -> new String[]{line}));
                logWhenDone(FarStore.stats());
                phase = 11;
                phaseTick = ticks;
            }
        } else if (phase == 11) {
            if (storeMapsLeft == 0 || t >= 1200) {
                // The switch off at runtime: capture stops and the store is let go
                FarChecks.force(false, FAR_STORE_CACHE);
                phase = 12;
                phaseTick = ticks;
            }
        } else if (phase == 12) {
            if (t == 40) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store turned off: {}", FarStore.status());
                FarChecks.force(true, FAR_STORE_CACHE);
            } else if (t == 80) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store on again: {}", FarStore.status());
                if (FAR_RENDER > 0) {
                    mc.options.renderDistance().set(renderBefore);
                }
                mc.player.connection.sendCommand("tp @s " + STORE_FLY_X + ".5 " + STORE_FLY_Y + " " + FAR_Z + ".5 " + FAR_YAW + " " + FAR_PITCH);
                phase = 13;
                phaseTick = ticks;
            }
        } else if (phase == 13) {
            if (t % 20 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store before the flight {} s: main thread {} us a frame (max {}), {} sections waiting | {}", t / 20,
                        String.format("%.0f", frame[0]), String.format("%.0f", frame[1]), (int) frame[2], FarStore.status());
            }
            if (t >= 200) {
                phase = 14;
                phaseTick = ticks;
                storeFlyMax = 0;
                storeFlySum = 0;
                storeFlySeconds = 0;
            }
        } else if (phase == 14) {
            mc.player.setDeltaMovement(0.0, 0.0, 0.0);
            mc.player.setPos(mc.player.getX() + FILL_SPEED, STORE_FLY_Y, FAR_Z + 0.5);
            if (t % 20 == 19) {
                storeFlyMax = Math.max(storeFlyMax, frame[1]);
                storeFlySum += frame[0];
                storeFlySeconds++;
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store flying {} s at x {}: main thread {} us a frame (max {}), {} sections waiting, frame {} ms | {}",
                        t / 20, String.format("%.0f", mc.player.getX()), String.format("%.0f", frame[0]), String.format("%.0f", frame[1]), (int) frame[2],
                        String.format("%.2f", median(frameMillis)), FarStore.status());
            }
            if (t >= STORE_FLY_TICKS) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store flight done: main thread {} us a frame on average over {} s, at most {} in a frame | {}",
                        String.format("%.0f", storeFlySum / Math.max(1, storeFlySeconds)), storeFlySeconds, String.format("%.0f", storeFlyMax), FarStore.status());
                phase = 15;
                phaseTick = ticks;
                logWhenDone(FarStore.stats());
            }
        } else if (phase == 15 && t == 1) {
            // Back to the vantage, so the next run joins there (the world saves the player where the run ends)
            mc.player.connection.sendCommand("tp @s " + FAR_X + ".5 " + farY + " " + FAR_Z + ".5 " + FAR_YAW + " " + FAR_PITCH);
            if (Boolean.getBoolean("shaderscompanion.devFarShapes")) {
                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> farShapes(server.overworld(), false));
            }
        } else if (phase == 15 && t >= 100) {
            if (Boolean.getBoolean("shaderscompanion.devFarDim")) {
                // Another dimension and back: the store stops with the level and starts for the next one
                mc.player.connection.sendCommand("execute in minecraft:the_nether run tp @s 0.5 120 0.5");
                phase = 16;
                phaseTick = ticks;
            } else {
                finish(mc);
            }
        } else if (phase == 16) {
            if (t % 40 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store in {} {} s: {}", mc.level.dimension().location(), t / 20, FarStore.status());
            }
            if (t == 300) {
                mc.player.connection.sendCommand("execute in minecraft:overworld run tp @s " + FAR_X + ".5 " + farY + " " + FAR_Z + ".5 " + FAR_YAW + " " + FAR_PITCH);
                phase = 17;
                phaseTick = ticks;
            }
        } else if (phase == 17) {
            if (t % 40 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store back in {} {} s: {}", mc.level.dimension().location(), t / 20, FarStore.status());
            }
            if (t >= 200) {
                logWhenDone(FarStore.stats());
                phase = 18;
                phaseTick = ticks;
            }
        } else if (phase == 18 && t >= 40) {
            finish(mc);
        }
    }

    private static String importBefore;
    private static int storeJoins;

    /**
     * M1b's checks (research/lod/M1.md). -PdevFarRejoin: 30 waits for the stats, then leaves the world; 31 joins it again
     * from the title screen; 32 waits for the player; 33 waits until the store has settled again and logs it.
     * -PdevFarImport: 40 starts the import; 41 waits for it; 42 waits until the store has settled, then the maps, stats
     * and compression measurement; 43 imports again; 44 waits for that and logs the stats; 45 quits.
     */
    private static void farStoreDisk(Minecraft mc, int t) {
        if (phase == 30) {
            if (storeMapsLeft <= 0 || t >= 600) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store: leaving the world (join {})", ++storeJoins);
                // As the pause menu's button does, outside the tick (clearLevel ticks the client while the server stops)
                phase = 31;
                phaseTick = ticks;
                mc.tell(() -> {
                    mc.level.disconnect();
                    mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(net.minecraft.network.chat.Component.literal("Saving")));
                    mc.setScreen(new TitleScreen());
                });
            }
        } else if (phase == 31) {
            if (t == 100) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store at the title screen: {}", FarStore.status());
                mc.createWorldOpenFlows().loadLevel(new TitleScreen(), WORLD);
                phase = 32;
                phaseTick = ticks;
            }
        } else if (phase == 32) {
            if (mc.player != null && mc.level != null) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store: joined again");
                phase = 33;
                phaseTick = ticks;
                storeSettledTicks = 0;
            }
        } else if (phase == 33) {
            if (mc.player != null) {
                mc.player.setYRot(FAR_YAW);
                mc.player.yRotO = FAR_YAW;
                mc.player.setXRot(FAR_PITCH);
                mc.player.xRotO = FAR_PITCH;
            }
            if (t % 100 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store second join {} s: {}", t / 20, FarStore.status());
            }
            storeSettledTicks = FarChecks.settled() ? storeSettledTicks + 1 : 0;
            if (t >= 1200 && storeSettledTicks >= 100 || t >= 6000) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store second join {} after {} s: {}", t >= 6000 ? "not settled" : "settled", t / 20,
                        FarStore.status());
                storeMapsLeft = 1;
                logWhenDone(FarStore.stats());
                phase = 34;
                phaseTick = ticks;
            }
        } else if (phase == 34) {
            if (storeMapsLeft <= 0 || t >= 600) {
                finish(mc);
            }
        } else if (phase == 40) {
            if (t == 40) {
                importBefore = FarChecks.importProgress();
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store import: {}", FarStore.startImport(FAR_IMPORT));
                phase = 41;
                phaseTick = ticks;
            }
        } else if (phase == 41 || phase == 44) {
            String state = FarChecks.importProgress();
            if (t % 200 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store importing {} s: {}", t / 20, FarStore.status());
            }
            if (state != null && state.startsWith("Far store import") && !state.equals(importBefore)) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store import ended after {} s: {}", t / 20, state);
                storeSettledTicks = 0;
                if (phase == 44) {
                    storeMapsLeft = 1;
                    logWhenDone(FarStore.stats());
                    phase = 45;
                } else {
                    phase = 42;
                }
                phaseTick = ticks;
            }
        } else if (phase == 42) {
            storeSettledTicks = FarChecks.settled() ? storeSettledTicks + 1 : 0;
            if (t % 200 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store after the import {} s: {}", t / 20, FarStore.status());
            }
            if ((storeSettledTicks >= 100 || t >= 12000) && FAR_TUNNEL && tunnelStep == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store tunnel: settled after the import, comparing with the world | {}", FarStore.status());
                logWhenDone(tunnelCheck());
                tunnelStep = 1;
                phase = 47;
                phaseTick = ticks;
                return;
            }
            if (storeSettledTicks >= 100 || t >= 12000) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store {} {} s after the import: {}", t >= 12000 ? "not settled" : "settled", t / 20,
                        FarStore.status());
                storeMapsLeft = 9;
                if (!FAR_RAW_STORE.isEmpty()) {
                    storeMapsLeft++;
                    // The chunks the client showed (render distance 5 around the vantage, and one more) are left out
                    int cx = FAR_X >> 4;
                    int cz = FAR_Z >> 4;
                    logWhenDone(FarChecks.compareWithRawStore(java.nio.file.Path.of(FAR_RAW_STORE), new int[]{cx - 7, cz - 7, cx + 7, cz + 7}));
                }
                for (int level = 0; level <= 4; level++) {
                    logWhenDone(FarStore.map(level, STORE_MAP_X, STORE_MAP_Z, STORE_MAP_SIZE, STORE_MAP_SIZE, "shaderscompanion-lod-import-L" + level + ".png")
                            .thenApply(line -> new String[]{line}));
                }
                int all = FAR_IMPORT * 16;
                int x0 = Math.floorDiv((FAR_X >> 4) * 16 - all, 64) * 64;
                int z0 = Math.floorDiv((FAR_Z >> 4) * 16 - all, 64) * 64;
                logWhenDone(FarStore.map(3, x0, z0, 2 * all + 64, 2 * all + 64, "shaderscompanion-lod-import-L3-all.png").thenApply(line -> new String[]{line}));
                logWhenDone(FarStore.mapAroundCamera(0, 4).thenApply(line -> new String[]{line}));
                logWhenDone(FarStore.stats());
                logWhenDone(FarChecks.compression());
                phase = 43;
                phaseTick = ticks;
            }
        } else if (phase == 47 || phase == 48) {
            // The tunnel (-PdevFarTunnel): dug, compared once settled, filled again, compared once settled
            MinecraftServer server = mc.getSingleplayerServer();
            if (phase == 47 && t == 40) {
                server.execute(() -> farTunnel(server.overworld(), 1));
            }
            storeSettledTicks = FarChecks.settled() ? storeSettledTicks + 1 : 0;
            if (t >= 240 && storeSettledTicks >= 100 || t >= 1200) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store tunnel {} ({} s): comparing with the world | {}", phase == 47 ? "dug" : "filled again",
                        t / 20, FarStore.status());
                logWhenDone(tunnelCheck());
                if (phase == 47) {
                    server.execute(() -> farTunnel(server.overworld(), 2));
                    phase = 48;
                } else {
                    tunnelStep = 2;
                    phase = 42;
                }
                phaseTick = ticks;
                storeSettledTicks = 0;
            }
        } else if (phase == 43) {
            if (storeMapsLeft <= 0 || t >= 6000) {
                importBefore = FarChecks.importProgress();
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store import again: {}", FarStore.startImport(FAR_IMPORT));
                phase = 44;
                phaseTick = ticks;
            }
        } else if (phase == 45) {
            if (storeMapsLeft <= 0 || t >= 600) {
                finish(mc);
            }
        }
    }

    // The far store's flight (-PdevFarFlight, M1c): research/lod/M1.md, "M1c"

    /**
     * The flight: a diagonal across Chunky's square (blocks x -1568 to 495, z -1344 to 719) from A to B, at least 20
     * chunks inside its edges so that render distance 16 never asks the server for a chunk it hasn't generated; 1,980
     * blocks at 30 blocks a second, 66 seconds.
     */
    private static final double FLIGHT_AX = -1240.5;
    private static final double FLIGHT_AZ = -1020.5;
    private static final double FLIGHT_BX = 159.5;
    private static final double FLIGHT_BZ = 379.5;
    private static java.util.List<Double> flightFrames;
    private static java.util.List<Double> flightGpu;
    private static long[] flightCounters;
    private static long flightWorkerCpu;
    private static long flightRenderCpu;
    private static long flightServerCpu;
    private static long flightWall;
    private static long serverThreadId = -1;
    /** M3c (-PdevFarFlight -PdevFarDraw): unbuiltColumns every tick of the flight out, and the jitter counters at its start. */
    private static java.util.List<int[]> flightUnbuilt;
    private static long[] flightJitter;

    /**
     * M3c: the chunk columns whose centre lies inside the far terrain's cylinder ((render distance - 1) x 16 blocks, where it
     * draws nothing) and whose top section Embeddium hasn't built (or whose chunk the client hasn't got): the holes M5's
     * coverage mask would fill. {inside, not built, not built ahead (within 60 degrees of the view)}.
     */
    private static int[] unbuiltColumns(Minecraft mc) {
        var renderer = me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
        if (renderer == null || mc.level == null) {
            return new int[3];
        }
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        int radius = (mc.options.getEffectiveRenderDistance() - 1) * 16;
        double yaw = Math.toRadians(mc.player.getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        int cx = (int) Math.floor(cam.x) >> 4;
        int cz = (int) Math.floor(cam.z) >> 4;
        int reach = radius / 16 + 1;
        int inside = 0;
        int unbuilt = 0;
        int ahead = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                double ox = x * 16 + 8 - cam.x;
                double oz = z * 16 + 8 - cam.z;
                double d2 = ox * ox + oz * oz;
                if (d2 > (double) radius * radius) {
                    continue;
                }
                inside++;
                var chunk = mc.level.getChunkSource().getChunk(x, z, net.minecraft.world.level.chunk.ChunkStatus.FULL, false);
                boolean built = false;
                if (chunk != null) {
                    int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, 8, 8);
                    built = renderer.isSectionReady(x, Math.floorDiv(top, 16), z);
                }
                if (!built) {
                    unbuilt++;
                    double d = Math.sqrt(d2);
                    if (d < 16.0 || ox * fx + oz * fz > 0.5 * d) {
                        ahead++;
                    }
                }
            }
        }
        return new int[]{inside, unbuilt, ahead};
    }

    /** M3c: the flight's unbuilt columns: average, the most, the share of ticks with any, and ahead of the view. */
    private static String unbuiltSummary() {
        if (flightUnbuilt == null || flightUnbuilt.isEmpty()) {
            return "no unbuilt column samples";
        }
        long inside = 0;
        long sum = 0;
        long ahead = 0;
        int max = 0;
        int any = 0;
        int anyAhead = 0;
        for (int[] u : flightUnbuilt) {
            inside += u[0];
            sum += u[1];
            ahead += u[2];
            max = Math.max(max, u[1]);
            any += u[1] > 0 ? 1 : 0;
            anyAhead += u[2] > 0 ? 1 : 0;
        }
        int n = flightUnbuilt.size();
        return String.format("columns inside the radius %.0f on average, not built %.2f on average (at most %d; %.0f%% of %d ticks with any), ahead of the view %.2f "
                + "(%.0f%% of ticks with any)", inside / (double) n, sum / (double) n, max, 100.0 * any / n, n, ahead / (double) n, 100.0 * anyAhead / n);
    }

    /** M3c: frames of the flight window much slower than its median (hitches a player feels). */
    private static String slowFramesSummary() {
        if (flightFrames == null || flightFrames.isEmpty()) {
            return "no frames";
        }
        double[] f = flightFrames.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double median = f[f.length / 2];
        int twice = 0;
        int over33 = 0;
        int over50 = 0;
        for (double v : f) {
            twice += v > 2 * median ? 1 : 0;
            over33 += v > 33.3 ? 1 : 0;
            over50 += v > 50.0 ? 1 : 0;
        }
        return String.format("slow frames: %d over twice the median (%.2f ms), %d over 33 ms, %d over 50 ms, of %d (%.1f FPS on average)", twice, median, over33, over50,
                f.length, f.length / (java.util.Arrays.stream(f).sum() / 1000.0));
    }

    /**
     * -PdevFarFlight (with -PdevFarStore and -PdevFarRender=N): 50 waits a second after joining and teleports to the
     * flight's start, 1,000 blocks away, while the join's sections are still being copied (M1a's open case: every
     * loaded chunk unloads); 51 records every frame's main-thread time for 20 seconds; 52 waits until the store has
     * settled; 53 flies A to B at 30 blocks a second with the store on, recording the main thread's time per frame, the
     * frame and GPU times, the worker's processor time (ThreadMXBean) and its counters; 54 turns the store off; 55 flies
     * back with the store off (frame and GPU times only); 56 goes back to the vantage and quits.
     */
    private static void farStoreFlight(Minecraft mc, int t) {
        double flightLength = Math.hypot(FLIGHT_BX - FLIGHT_AX, FLIGHT_BZ - FLIGHT_AZ);
        int flightTicks = (int) Math.ceil(flightLength / FILL_SPEED);
        double[] frame = FarChecks.frameMicros();
        if (phase == 50) {
            if (t == 20) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store flight: joined, teleporting to the start | {}", FarStore.status());
                FarChecks.recordFrames(true);
                mc.player.connection.sendCommand("tp @s " + FLIGHT_AX + " " + STORE_FLY_Y + " " + FLIGHT_AZ + " " + FAR_YAW + " " + FAR_PITCH);
                phase = 51;
                phaseTick = ticks;
            }
        } else if (phase == 51) {
            if (t > 2) {
                holdAt(mc, FLIGHT_AX, FLIGHT_AZ);
            }
            if (FAR_DRAW && (t == 60 || t == 200 || t == 399)) {
                // M3c: what the screen shows while the chunks after the teleport load (holes inside the radius: M5)
                Screenshot.grab(mc.gameDirectory, String.format("shaderscompanion-farflight-%s-teleport-%04d.png", System.getProperty("shaderscompanion.devTag", "run"), t),
                        mc.getMainRenderTarget(), message -> { });
                int[] u = unbuiltColumns(mc);
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far flight teleport shot {} s: {} of {} columns inside the radius not built ({} ahead) | Far terrain: {}",
                        t / 20, u[1], u[0], u[2], FarTerrain.terrainStatus());
            }
            if (t % 20 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store after the teleport {} s: main thread {} us a frame (max {}), {} sections waiting | {}", t / 20,
                        String.format("%.0f", frame[0]), String.format("%.0f", frame[1]), (int) frame[2], FarStore.status());
            }
            if (t >= 400) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store teleport after joining, the 20 s from the teleport: {}",
                        framesSummary(FarChecks.recordFrames(false)));
                phase = 52;
                phaseTick = ticks;
                storeSettledTicks = 0;
            }
        } else if (phase == 52) {
            holdAt(mc, FLIGHT_AX, FLIGHT_AZ);
            boolean ready = FarChecks.settled();
            if (FAR_DRAW) {
                // M3c: the far terrain meshed and on the GPU too before the flight
                if (t % 20 == 0) {
                    FarChecks.terrainNumbers().whenComplete((n, e) -> meshNumbers = n);
                }
                long[] n = meshNumbers;
                ready &= n != null && n[n.length - 11 + 5] == 0 && FarChecks.gpuNumbers(false)[0] == 0;
            }
            storeSettledTicks = ready ? storeSettledTicks + 1 : 0;
            if (storeSettledTicks >= 100 || t >= 2400) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store before the flight ({} after {} s) | {}", storeSettledTicks >= 100 ? "settled" : "not settled",
                        t / 20, FarStore.status());
                logWhenDone(FarStore.stats());
                if (FAR_DRAW) {
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, far flight start: Far terrain: {}", FarTerrain.terrainStatus());
                    // Round B: the far shadow map's redraws counted from here
                    FarRenderer.shadowNumbersForChecks();
                    startMeshWindow();
                    flightUnbuilt = new java.util.ArrayList<>();
                    flightJitter = FarRenderer.jitterForChecks();
                }
                startFlightWindow(true);
                phase = 53;
                phaseTick = ticks;
                storeFlyMax = 0;
                storeFlySum = 0;
                storeFlySeconds = 0;
            }
        } else if (phase == 53 || phase == 55) {
            boolean out = phase == 53;
            double f = Math.min(1.0, (double) t / flightTicks);
            double x = out ? FLIGHT_AX + (FLIGHT_BX - FLIGHT_AX) * f : FLIGHT_BX + (FLIGHT_AX - FLIGHT_BX) * f;
            double z = out ? FLIGHT_AZ + (FLIGHT_BZ - FLIGHT_AZ) * f : FLIGHT_BZ + (FLIGHT_AZ - FLIGHT_BZ) * f;
            holdAt(mc, x, z);
            if (out && FAR_DRAW && t < flightTicks) {
                // M3c: the chunk columns inside the radius Embeddium hasn't built, every tick; a shot every 2 s and a
                // burst of 4 consecutive ticks every 20 s (popping, holes at the chunk edge, flicker, fog while moving)
                flightUnbuilt.add(unbuiltColumns(mc));
                if (t % 40 == 20 || t % 400 >= 200 && t % 400 < 204) {
                    Screenshot.grab(mc.gameDirectory, String.format("shaderscompanion-farflight-%s-%04d.png", System.getProperty("shaderscompanion.devTag", "run"), t),
                            mc.getMainRenderTarget(), message -> { });
                }
                if (t % 20 == 19) {
                    int s = flightUnbuilt.size();
                    int sum = 0;
                    int max = 0;
                    int ahead = 0;
                    for (int i = Math.max(0, s - 20); i < s; i++) {
                        sum += flightUnbuilt.get(i)[1];
                        max = Math.max(max, flightUnbuilt.get(i)[1]);
                        ahead += flightUnbuilt.get(i)[2];
                    }
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, far flight {} s: columns inside the radius {}, not built {} on average (at most {}), {} ahead "
                                    + "| Far terrain: {}", t / 20, flightUnbuilt.get(s - 1)[0], String.format("%.1f", sum / 20.0), max, String.format("%.1f", ahead / 20.0),
                            FarTerrain.terrainStatus());
                }
            }
            if (t % 20 == 19) {
                if (out) {
                    storeFlyMax = Math.max(storeFlyMax, frame[1]);
                    storeFlySum += frame[0];
                    storeFlySeconds++;
                }
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store flying {} {} s at x {} z {}: main thread {} us a frame (max {}), {} sections waiting, frame {} ms, "
                                + "GPU {} ms | {}", out ? "out (store on)" : "back (store off)", t / 20, String.format("%.0f", x), String.format("%.0f", z),
                        String.format("%.0f", frame[0]), String.format("%.0f", frame[1]), (int) frame[2], String.format("%.2f", median(frameMillis)),
                        String.format("%.2f", median(gpuMillis)), FarStore.status());
            }
            if (t >= flightTicks) {
                if (out) {
                    long[] frames = FarChecks.recordFrames(false);
                    String loads = loadsSummary(FarChecks.recordedLoads());
                    long[] before = flightCounters;
                    long wall = System.nanoTime() - flightWall;
                    long worker = FarChecks.workerCpuNanos() - flightWorkerCpu;
                    long render = renderCpu() - flightRenderCpu;
                    long server = serverCpu() - flightServerCpu;
                    String frameTimes = frameTimesSummary();
                    int distance = mc.options.getEffectiveRenderDistance();
                    FarChecks.counters().whenComplete((after, error) -> mc.execute(() -> ShadersCompanion.LOGGER.info(
                            "Shaders Companion dev run, far store flight with the store on ({} s, render distance {}): main thread {}; {}; {}; "
                                    + "processor: the far store's worker {}% of a core, the render thread {}%, the server thread {}%; worker: {}",
                            String.format("%.1f", wall / 1.0e9), distance, framesSummary(frames), loads, frameTimes, String.format("%.1f", 100.0 * worker / wall),
                            String.format("%.1f", 100.0 * render / wall), String.format("%.1f", 100.0 * server / wall), FarChecks.countersSummary(before, after))));
                    logWhenDone(FarStore.stats());
                    if (FAR_DRAW) {
                        ShadersCompanion.LOGGER.info("Shaders Companion dev run, far flight with far terrain drawn: {}; {}; {}; frames drawn for the pack {}, with "
                                        + "another frame counter than Oculus's {} | Far terrain: {}", unbuiltSummary(), slowFramesSummary(), meshWindowSummary(),
                                FarRenderer.jitterForChecks()[0] - flightJitter[0], FarRenderer.jitterForChecks()[1] - flightJitter[1], FarTerrain.terrainStatus());
                        ShadersCompanion.LOGGER.info("Shaders Companion dev run, far flight's far shadow map: {}", shadowLine());
                    }
                    phase = 54;
                } else {
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store flight with the store off ({} s, render distance {}): {}{}",
                            String.format("%.1f", (System.nanoTime() - flightWall) / 1.0e9), mc.options.getEffectiveRenderDistance(), frameTimesSummary(),
                            FAR_DRAW ? "; " + slowFramesSummary() : "");
                    flightFrames = null;
                    flightGpu = null;
                    phase = 56;
                }
                phaseTick = ticks;
            }
        } else if (phase == 54) {
            holdAt(mc, FLIGHT_BX, FLIGHT_BZ);
            if (t == 100) {
                flightFrames = null;
                flightGpu = null;
                FarChecks.force(false, FAR_STORE_CACHE);
            } else if (t == 200) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store off for the flight back: {}", FarStore.status());
                startFlightWindow(false);
                phase = 55;
                phaseTick = ticks;
            }
        } else if (phase == 56) {
            if (t == 1) {
                mc.player.connection.sendCommand("tp @s " + FAR_X + ".5 " + farY + " " + FAR_Z + ".5 " + FAR_YAW + " " + FAR_PITCH);
            } else if (t >= 100) {
                finish(mc);
            }
        }
    }

    // The far terrain check (-PdevScene=far -PdevFarStore -PdevFarMesh: M2a, research/lod/M2.md)

    /** -PdevFarMesh: the far store check meshes the far terrain instead (M2a). */
    private static final boolean FAR_MESH = Boolean.getBoolean("shaderscompanion.devFarMesh");
    /** -PdevFarRange=N: the far range of the run's first fills (0: the config's farRange). */
    private static final int MESH_RANGE = Integer.getInteger("shaderscompanion.devFarRange", 0);
    private static long[] meshNumbers;
    private static long meshFillsBefore;
    private static boolean meshWindowOpen;
    private static long meshWall;
    private static long meshWorkerCpu;
    private static long meshMesherCpu;
    private static long meshRenderCpu;
    private static long meshServerCpu;
    private static long[] meshCounters;

    /**
     * -PdevFarMesh (with -PdevFarStore): 60 waits after joining at the vantage until the resident set is meshed and the
     * store has settled, and logs the join (the residency's own fill time and the threads' processor time over it);
     * 61 writes the mesh maps of levels 0 to 2 beside LodMap's (the 512-block square of the store maps); 62 sets the
     * far range to 4,096 and waits for that fill, 63 sets it back; 64 teleports to the M1c flight's start, 1,000
     * blocks away, and waits for the fill there; 65 flies the M1c diagonal at 30 blocks a second, logging the threads'
     * shares of a core; 66 goes back to the vantage and quits.
     */
    private static void farMeshCheck(Minecraft mc, int t) {
        // Settled first: the numbers' request below is a task in the worker's queue for a moment
        storeSettledTicks = FarChecks.settled() ? storeSettledTicks + 1 : 0;
        if (t % 20 == 0) {
            FarChecks.terrainNumbers().whenComplete((n, e) -> meshNumbers = n);
        }
        long[] n = meshNumbers;
        int at = n == null ? 0 : n.length - 11;
        long fills = n == null ? 0 : n[at + 7];
        boolean filledSince = n != null && n[at + 5] == 0 && fills > meshFillsBefore;
        double[] frame = FarChecks.frameMicros();
        if (phase == 60 || phase == 62 || phase == 63 || phase == 64) {
            if (!meshWindowOpen) {
                meshWindowOpen = true;
                // The join: any fill since the store started counts (once nothing fills any more)
                meshFillsBefore = phase == 60 ? 0 : fills;
                startMeshWindow();
                if (phase == 62) {
                    FarChecks.farRange(4096);
                } else if (phase == 63) {
                    FarChecks.farRange(MESH_RANGE);
                } else if (phase == 64) {
                    mc.player.connection.sendCommand("tp @s " + FLIGHT_AX + " " + STORE_FLY_Y + " " + FLIGHT_AZ + " " + FAR_YAW + " " + FAR_PITCH);
                }
                return;
            }
            if (phase == 64 && t > 2) {
                holdAt(mc, FLIGHT_AX, FLIGHT_AZ);
            }
            if (t % 40 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain {} s (phase {}): main thread {} us a frame (max {}) | Far store: {} | Far terrain: {}", t / 20, phase,
                        String.format("%.0f", frame[0]), String.format("%.0f", frame[1]), FarStore.status(), FarTerrain.terrainStatus());
            }
            boolean done = filledSince && meshNumbers != null && storeSettledTicks >= 60 && t >= 200;
            if (done || t >= 4800) {
                String what = phase == 60 ? "the join at the vantage" : phase == 62 ? "the far range set to 4096" : phase == 63 ? "the far range set back"
                        : "a teleport 1,000 blocks to the flight's start";
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain, {}: {}; {}; {}", what, done ? "meshed and settled" : "NOT settled after 240 s",
                        fillSummary(n), meshWindowSummary());
                meshWindowOpen = false;
                phase = phase == 60 ? 61 : phase == 62 ? 63 : phase == 63 ? 64 : 65;
                phaseTick = ticks;
                // The stats, and at 61 the three maps the next phase waits for
                storeMapsLeft = phase == 61 ? 4 : 1;
                logWhenDone(FarTerrain.terrainStats());
                if (phase == 61) {
                    for (int level = 0; level <= 2; level++) {
                        logWhenDone(FarTerrain.meshMap(level, STORE_MAP_X, STORE_MAP_Z, STORE_MAP_SIZE, "shaderscompanion-far"));
                    }
                } else if (phase == 65) {
                    startMeshWindow();
                    FarChecks.recordFrames(true);
                    storeFlyMax = 0;
                    storeFlySum = 0;
                    storeFlySeconds = 0;
                }
            }
        } else if (phase == 61) {
            if (storeMapsLeft <= 0 || t >= 2400) {
                // -PdevFarMeshShort: the join and the maps only (for comparing mesher settings)
                phase = Boolean.getBoolean("shaderscompanion.devFarMeshShort") ? 66 : 62;
                phaseTick = ticks;
            }
        } else if (phase == 65) {
            double flightLength = Math.hypot(FLIGHT_BX - FLIGHT_AX, FLIGHT_BZ - FLIGHT_AZ);
            int flightTicks = (int) Math.ceil(flightLength / FILL_SPEED);
            double f = Math.min(1.0, (double) t / flightTicks);
            double x = FLIGHT_AX + (FLIGHT_BX - FLIGHT_AX) * f;
            double z = FLIGHT_AZ + (FLIGHT_BZ - FLIGHT_AZ) * f;
            holdAt(mc, x, z);
            if (t % 20 == 19) {
                storeFlyMax = Math.max(storeFlyMax, frame[1]);
                storeFlySum += frame[0];
                storeFlySeconds++;
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain flying {} s at x {} z {}: main thread {} us a frame (max {}), frame {} ms | Far terrain: {}",
                        t / 20, String.format("%.0f", x), String.format("%.0f", z), String.format("%.0f", frame[0]), String.format("%.0f", frame[1]),
                        String.format("%.2f", median(frameMillis)), FarTerrain.terrainStatus());
            }
            if (t >= flightTicks) {
                String frames = framesSummary(FarChecks.recordFrames(false));
                FarChecks.recordedLoads();
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain flight ({} blocks at 30 a second, render distance {}): the store's main thread {}; {}; {}",
                        String.format("%.0f", flightLength), mc.options.getEffectiveRenderDistance(), frames, meshWindowSummary(), meshCountersSummary(n));
                logWhenDone(FarTerrain.terrainStats());
                logWhenDone(FarStore.stats());
                phase = 66;
                phaseTick = ticks;
            }
        } else if (phase == 66) {
            if (t == 1) {
                mc.player.connection.sendCommand("tp @s " + FAR_X + ".5 " + farY + " " + FAR_Z + ".5 " + FAR_YAW + " " + FAR_PITCH);
            } else if (t >= 100) {
                finish(mc);
            }
        }
    }

    private static void startMeshWindow() {
        meshWall = System.nanoTime();
        meshWorkerCpu = FarChecks.workerCpuNanos();
        meshMesherCpu = Math.max(0, FarChecks.mesherCpuNanos());
        meshRenderCpu = renderCpu();
        meshServerCpu = serverCpu();
        FarChecks.colourNumbers(true);
        meshCounters = meshNumbers;
    }

    /** The threads' share of a core since startMeshWindow, and the render thread's far terrain part a frame. */
    private static String meshWindowSummary() {
        long wall = System.nanoTime() - meshWall;
        long worker = FarChecks.workerCpuNanos() - meshWorkerCpu;
        long mesher = Math.max(0, FarChecks.mesherCpuNanos()) - meshMesherCpu;
        long render = renderCpu() - meshRenderCpu;
        long server = serverCpu() - meshServerCpu;
        long[] c = FarChecks.colourNumbers(false);
        return String.format("over %.1f s: the far store's worker %.1f%% of a core, the far mesher %.1f%%, the render thread %.1f%%, the server thread %.1f%%; "
                        + "the render thread's far terrain part %.1f us a frame (at most %.0f, %d frames); colour table so far: %d states, %.1f us a state (at most %.0f), "
                        + "%d frames working, %.0f us each (at most %.0f), all states in %.0f ms", wall / 1.0e9, 100.0 * worker / wall, 100.0 * mesher / wall,
                100.0 * render / wall, 100.0 * server / wall, c[9] == 0 ? 0.0 : c[7] / 1.0e3 / c[9], c[8] / 1.0e3, c[9], c[0], c[0] == 0 ? 0.0 : c[1] / 1.0e3 / c[0],
                c[2] / 1.0e3, c[3], c[3] == 0 ? 0.0 : c[4] / 1.0e3 / c[3], c[5] / 1.0e3, c[6] / 1.0e6);
    }

    /** The residency's last fill (its own clock: from the view that started it until nothing waits). */
    private static String fillSummary(long[] n) {
        if (n == null) {
            return "no residency";
        }
        int at = n.length - 11;
        StringBuilder levels = new StringBuilder();
        long quads = 0;
        long bytes = 0;
        for (int level = 0; level < 7; level++) {
            if (n[level * 5] == 0) {
                continue;
            }
            quads += n[level * 5 + 2];
            bytes += n[level * 5 + 4];
            levels.append(String.format("; level %d: %d resident, %d with geometry, %d quads (%d border), %.1f MB", level, n[level * 5], n[level * 5 + 1], n[level * 5 + 2],
                    n[level * 5 + 3], n[level * 5 + 4] / 1048576.0));
        }
        return String.format("fill %.1f s (%d jobs; the mesher's processor %.1f s, the worker's %.1f s); %d quads, %.1f MB in all; %.0f us a node meshed on average, "
                        + "at most %.0f us%s", n[at + 6] / 1.0e9, n[at + 10], n[at + 8] / 1.0e9, n[at + 9] / 1.0e9, quads, bytes / 1048576.0,
                n[at] == 0 ? 0.0 : n[at + 1] / 1.0e3 / n[at], n[at + 2] / 1.0e3, levels);
    }

    /** Meshes and jobs since startMeshWindow. */
    private static String meshCountersSummary(long[] n) {
        long[] a = meshCounters;
        if (n == null || a == null) {
            return "no counters";
        }
        int at = n.length - 11;
        long meshes = n[at] - a[at];
        long nanos = n[at + 1] - a[at + 1];
        long jobs = n[at + 3] - a[at + 3];
        long jobNanos = n[at + 4] - a[at + 4];
        return String.format("%d meshes (%.0f us each), %d jobs (%.0f us of the worker each)", meshes, meshes == 0 ? 0.0 : nanos / 1.0e3 / meshes, jobs,
                jobs == 0 ? 0.0 : jobNanos / 1.0e3 / jobs);
    }

    // The far terrain's debug view (-PdevScene=far -PdevFarStore -PdevFarDraw: M2b, research/lod/M2.md)

    /** -PdevFarDraw: the far store check draws the far terrain in its debug view (M2b). */
    private static final boolean FAR_DRAW = Boolean.getBoolean("shaderscompanion.devFarDraw");
    /** -PdevFarDhShot: the far scene with Distant Horizons takes one shot once it has settled, and quits. */
    private static final boolean FAR_DH_SHOT = Boolean.getBoolean("shaderscompanion.devFarDhShot");
    /** -PdevFarOverlay (M3a): the debug view even while the pack binds the far textures. */
    private static final boolean FAR_OVERLAY = Boolean.getBoolean("shaderscompanion.devFarOverlay");
    /** -PdevFarShort (M3a): no second round with level 0's caves filled. */
    private static final boolean FAR_SHORT = Boolean.getBoolean("shaderscompanion.devFarShort");
    /** -PdevFarDim: the far terrain check ends with a trip to the Nether and back (M3a: nothing drawn there). */
    private static final boolean FAR_DIM = Boolean.getBoolean("shaderscompanion.devFarDim");
    private static long dhGlFramesFrom;
    private static long dimGlFramesFrom;
    private static long[] jitterFrom;

    private static String drawTag() {
        return phase < 84 ? "caves" : "filled";
    }

    /** The far terrain is drawn for the shader pack this frame (it binds the far textures). */
    private static boolean forPack() {
        return FarTerrain.drawMode() == FarTerrain.MODE_PACK || FarTerrain.drawMode() == FarTerrain.MODE_PACK_STILL;
    }

    /** Round B: the far shadow map's redraws since the last call, their GPU time and quads, as a log line. */
    private static String shadowLine() {
        long[] s = FarRenderer.shadowNumbersForChecks();
        return String.format("over %d frames, %d with a redraw; cascade 0 redrawn %d times (GPU median %s ms, max %s, %d quads), cascade 1 %d times "
                        + "(GPU median %s ms, max %s, %d quads); in use: %s", s[8], s[9], s[0], ms(s[1]), ms(s[2]), s[3], s[4], ms(s[5]), ms(s[6]), s[7],
                s[10] == 1 ? "yes" : "no");
    }

    /** The info texture's 16 floats as a log line (M3a). */
    private static String infoLine() {
        float[] f = FarTextures.infoForChecks();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < f.length / 4; i++) {
            s.append(i == 0 ? "" : ", ").append(String.format("texel %d (%s, %s, %s, %s)", i, f[i * 4], f[i * 4 + 1], f[i * 4 + 2], f[i * 4 + 3]));
        }
        float[] planes = FarRenderer.depthPlanesForChecks();
        return s.append(String.format("; this frame N %s, F %s, Oculus's frame counter worked out %d", planes[0], planes[1], (int) planes[2])).toString();
    }

    /**
     * -PdevFarDraw (with -PdevFarStore): 80 waits after joining at the vantage until the resident set is meshed and on
     * the GPU and the store has settled, and logs the fill and the uploads; 81 takes the shots (the debug view alone,
     * then over the frame); 82 times 10 seconds with the far terrain drawn (its selection and draw on the GPU, nodes and
     * quads drawn, its render-thread time, the frame) and 10 seconds with its drawing paused; then the caves are filled
     * at level 0 too and everything meshed again, and 84 to 86 do the same; 87 hides the debug view and quits.
     */
    private static void farDrawCheck(Minecraft mc, int t) {
        storeSettledTicks = FarChecks.settled() ? storeSettledTicks + 1 : 0;
        if (t % 20 == 0) {
            FarChecks.terrainNumbers().whenComplete((n, e) -> meshNumbers = n);
        }
        long[] n = meshNumbers;
        int at = n == null ? 0 : n.length - 11;
        long fills = n == null ? 0 : n[at + 7];
        boolean filledSince = n != null && n[at + 5] == 0 && fills > meshFillsBefore;
        if (phase == 80 || phase == 84) {
            long[] gpu = FarChecks.gpuNumbers(false);
            if (t % 40 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain drawn {} s (phase {}): {} waiting to upload | Far terrain: {}", t / 20, phase, gpu[0],
                        FarTerrain.terrainStatus());
            }
            boolean done = filledSince && gpu[0] == 0 && storeSettledTicks >= 60 && t >= 200;
            if (done || t >= 4800) {
                double seconds = gpu[4] > gpu[3] ? (gpu[4] - gpu[3]) / 1.0e9 : 0.0;
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain drawn, {}: {}; {}; uploads: {} meshes on the GPU, {} MB uploaded in {} s from the first "
                                + "upload to the last ({} MB a second), {} meshes didn't fit; the uploads' render-thread time {} us a frame on average over {} frames, at most {} us",
                        phase == 80 ? "the join at the vantage, caves kept at level 0" : "everything meshed again with level 0's caves filled",
                        done ? "meshed, uploaded and settled" : "NOT settled after 240 s", fillSummary(n), gpu[1], String.format("%.1f", gpu[2] / 1048576.0),
                        String.format("%.1f", seconds), String.format("%.1f", seconds > 0 ? gpu[2] / 1048576.0 / seconds : 0.0), gpu[5],
                        String.format("%.0f", gpu[7] == 0 ? 0.0 : gpu[6] / 1.0e3 / gpu[7]), gpu[7], gpu[8] / 1000);
                logWhenDone(FarTerrain.terrainStats());
                phase++;
                phaseTick = ticks;
            }
        } else if (phase == 81 || phase == 85) {
            if (t == 1) {
                FarRenderer.aloneForChecks(true);
            } else if (t == 30) {
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-fardraw-" + drawTag() + "-alone.png", mc.getMainRenderTarget(), message -> { });
                FarRenderer.aloneForChecks(false);
            } else if (t == 60) {
                // M3a: with the pack reading the far terrain this is the pack's frame (the debug view stays off)
                boolean pack = forPack();
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-fardraw-" + drawTag() + (pack ? "-pack.png" : "-over.png"), mc.getMainRenderTarget(), message -> { });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain {} shot: Far terrain: {}", pack ? "pack" : "over", FarTerrain.terrainStatus());
                if (pack) {
                    ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain info texture: {}", infoLine());
                }
            } else if (t >= 80) {
                if (FAR_SHIFT > 0 || FAR_DIMS) {
                    // Fix round 1: the shift check, then (or only) the trip to the Nether and the End
                    phase = FAR_SHIFT > 0 ? 92 : 100;
                    storeSettledTicks = 0;
                } else if (FAR_AUTO) {
                    // Fix round 2: the auto import (phase 130)
                    phase = 130;
                } else if (!TOGGLE.isEmpty() && FAR_HOLD_Y > 0) {
                    // Integration round A: the toggle rounds from a camera held elsewhere (phase 120 first)
                    phase = 120;
                    storeSettledTicks = 0;
                } else if (!TOGGLE.isEmpty()) {
                    // M3b: -PdevToggle rounds (with -PdevBurst and -PdevProfile) at the vantage after the shots, then quits:
                    // shots shaderscompanion-far-<tag>-<toggle>-<off|on><round>[-bNNN].png
                    prefix = "shaderscompanion-far-" + System.getProperty("shaderscompanion.devTag", "run");
                    timingRound = 0;
                    setPackRound(0);
                    phase = 4;
                } else {
                    phase++;
                }
                phaseTick = ticks;
            }
        } else if (phase == 82 || phase == 86) {
            if (t == 1) {
                FarRenderer.historyForChecks();
                jitterFrom = FarRenderer.jitterForChecks();
            } else if (t == 200) {
                long[] jitter = FarRenderer.jitterForChecks();
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain path ({}): {}; frames drawn for the pack {}, of them with another frame counter than "
                                + "Oculus's {}; info texture: {}", drawTag(), forPack() ? "for the shader pack" : "debug view", jitter[0] - jitterFrom[0], jitter[1] - jitterFrom[1],
                        infoLine());
                long[] h = FarRenderer.historyForChecks();
                long[] memory = FarRenderer.memoryForChecks();
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain timing ({}, render distance {}): over {} frames, the far terrain's GPU time: selection {} ms "
                                + "(max {}), draw {} ms (max {}), together {} ms; overlay {} ms; {} nodes and {} quads drawn (medians; max {} quads); its render-thread time: "
                                + "the draw {} us (max {}), the far store's frame part with uploads {} us (max {}); video memory: geometry {} MB allocated, {} MB used, "
                                + "all its buffers and the far layer {} MB | {} | {}",
                        drawTag(), mc.options.getEffectiveRenderDistance(), h[14], ms(h[0]), ms(h[1]), ms(h[2]), ms(h[3]), ms(h[0] + h[2]), ms(h[4]), h[6], h[8], h[9],
                        h[10] / 1000, h[11] / 1000, h[12] / 1000, h[13] / 1000, memory[0] >> 20, memory[1] >> 20, memory[2] >> 20, vram(), frameTiming());
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain status: {}", FarTerrain.terrainStatus());
                FarRenderer.pauseForChecks(true);
            } else if (t == 400) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain timing ({}), its drawing paused: {}", drawTag(), frameTiming());
                FarRenderer.pauseForChecks(false);
                // Where the draw's time goes: the rasterizer off (vertices and commands only), then one quad per command
                FarRenderer.probeForChecks(1);
                FarRenderer.historyForChecks();
            } else if (t == 600 || t == 800) {
                long[] h = FarRenderer.historyForChecks();
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain timing ({}), {}: selection {} ms, draw {} ms (medians of {} frames), {} quads",
                        drawTag(), t == 600 ? "the rasterizer off (vertices and commands only)" : "one quad per draw command (the commands' own cost)",
                        ms(h[0]), ms(h[2]), h[14], h[8]);
                FarRenderer.probeForChecks(t == 600 ? 2 : 0);
            } else if (t >= 840) {
                if (phase == 82 && FAR_SHORT) {
                    phase = FAR_DIM ? 88 : 87;
                    if (!FAR_DIM) {
                        FarChecks.draw(false);
                    }
                } else if (phase == 82) {
                    // The same with level 0's caves filled: everything meshed again
                    FarChecks.caveFillFrom(0);
                    // A new residency counts its fills from 0
                    meshFillsBefore = 0;
                    meshNumbers = null;
                    FarChecks.restartMeshing();
                    FarChecks.gpuNumbers(true);
                    phase = 84;
                } else if (FAR_DIM) {
                    phase = 88;
                } else {
                    FarChecks.draw(false);
                    phase = 87;
                }
                phaseTick = ticks;
            }
        } else if (phase >= 92 && phase <= 99) {
            farShiftCheck(mc, t);
        } else if (phase >= 100 && phase <= 119) {
            farDimsCheck(mc, t);
        } else if (phase == 130) {
            farAutoCheck(mc, t);
        } else if (phase == 120) {
            // Integration round A (-PdevFarHoldY with -PdevToggle): the camera held at -PdevFarShiftX, -PdevFarHoldY,
            // -PdevFarShiftZ, -PdevYaw, -PdevPitch until the far terrain there is meshed and on the GPU, then the toggle
            // rounds from there (the camera held through them)
            double x = FAR_SHIFT_X + 0.5;
            double z = FAR_SHIFT_Z + 0.5;
            if (t == 1) {
                mc.player.connection.sendCommand("tp @s " + x + " " + FAR_HOLD_Y + " " + z + " " + FAR_SHIFT_YAW + " " + FAR_PITCH);
                if (FAR_GLASS) {
                    placeGlass(mc, BlockPos.containing(x, FAR_HOLD_Y, z), FAR_GLASS_YAW);
                }
                if (FAR_NEAR_BLOCKS) {
                    nearBlocksAt = BlockPos.containing(x, FAR_HOLD_Y - FAR_NEAR_DROP, z);
                    MinecraftServer server = mc.getSingleplayerServer();
                    BlockPos nearAt = nearBlocksAt;
                    server.execute(() -> nearBlocks(server.overworld(), nearAt, FAR_SHIFT_YAW, true));
                }
            }
            if (t > 2) {
                holdAt(mc, x, FAR_HOLD_Y, z, FAR_SHIFT_YAW, FAR_PITCH);
            }
            if ((farReady(t) && t >= 300) || t >= 900) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far hold at {} {} {} (yaw {}, pitch {}) ready after {} s: Far terrain: {}", x, FAR_HOLD_Y, z,
                        FAR_SHIFT_YAW, FAR_PITCH, t / 20, FarTerrain.terrainStatus());
                farHolding = true;
                prefix = "shaderscompanion-far-" + System.getProperty("shaderscompanion.devTag", "run");
                timingRound = 0;
                setPackRound(0);
                phase = 4;
                phaseTick = ticks;
            }
        } else if (phase == 88) {
            // M3a: the Nether has no sky light, so the far terrain draws nothing there and the hook makes no GL call
            if (t == 1) {
                mc.player.connection.sendCommand("execute in minecraft:the_nether run tp @s 0.5 120 0.5");
            } else if (t == 200) {
                dimGlFramesFrom = FarRenderer.glFramesForChecks();
            } else if (t % 100 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain in {} {} s: Far terrain: {} | Far store: {}", mc.level.dimension().location(), t / 20,
                        FarTerrain.terrainStatus(), FarStore.status());
            }
            if (t == 400) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain in {}: frames whose hook made far terrain GL calls from 10 s in: {} (of 10 s); "
                        + "its buffers {} MB", mc.level.dimension().location(), FarRenderer.glFramesForChecks() - dimGlFramesFrom, FarRenderer.memoryForChecks()[2] >> 20);
                mc.player.connection.sendCommand("execute in minecraft:overworld run tp @s " + FAR_X + ".5 " + farY + " " + FAR_Z + ".5 " + FAR_YAW + " " + FAR_PITCH);
                phase = 89;
                phaseTick = ticks;
            }
        } else if (phase == 89) {
            if (t % 100 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain back in {} {} s: Far terrain: {}", mc.level.dimension().location(), t / 20,
                        FarTerrain.terrainStatus());
            }
            if (t >= 600) {
                FarChecks.draw(false);
                phase = 87;
                phaseTick = ticks;
            }
        } else if (phase == 87) {
            if (t == 60) {
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-fardraw-off.png", mc.getMainRenderTarget(), message -> { });
                long[] memory = FarRenderer.memoryForChecks();
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far terrain debug view off: its buffers {} MB | Far terrain: {} | {}", memory[2] >> 20,
                        FarTerrain.terrainStatus(), frameTiming());
            } else if (t >= 100) {
                finish(mc);
            }
        }
    }

    // Fix round 1 (research/lod/M3.md, "Fix round 1"): the shift check and the trip to the Nether and the End

    /** -PdevFarShift=N: far terrain shot from a camera held still, then N blocks to the side, then back, then moving. */
    private static final int FAR_SHIFT = Integer.getInteger("shaderscompanion.devFarShift", 0);
    /** -PdevFarShiftY: the shift check's camera height (the flights' 230 by default); -PdevYaw its heading (east). */
    private static final int FAR_SHIFT_Y = Integer.getInteger("shaderscompanion.devFarShiftY", 230);
    private static final float FAR_SHIFT_YAW = Integer.getInteger("shaderscompanion.devYaw", (int) FAR_YAW);
    /** -PdevFarShiftX, -PdevFarShiftZ: the shift check's spot (the vantage's by default). */
    private static final int FAR_SHIFT_X = Integer.getInteger("shaderscompanion.devFarShiftX", FAR_X);
    private static final int FAR_SHIFT_Z = Integer.getInteger("shaderscompanion.devFarShiftZ", FAR_Z);
    /** The shift check's ticks at each spot before its shot (TAA and the far terrain's history settle). */
    private static final int SHIFT_HOLD_TICKS = 120;
    /** -PdevFarDims (with -PdevChunky): the far draw check visits the Nether and the End, generating and importing terrain there. */
    private static final boolean FAR_DIMS = Boolean.getBoolean("shaderscompanion.devFarDims");
    /** -PdevFarDimsRadius: the square Chunky generates around each visit's spot, in blocks (320 by default). */
    private static final int FAR_DIMS_RADIUS = Integer.getInteger("shaderscompanion.devFarDimsRadius", 320);
    /** Integration round A: -PdevFarHoldY=N holds the -PdevToggle rounds' camera at the shift check's spot, N high. */
    private static final int FAR_HOLD_Y = Integer.getInteger("shaderscompanion.devFarHoldY", 0);
    private static boolean farHolding;
    /**
     * M4: -PdevFarGlass puts two black glass walls (glassWalls) at the held camera (-PdevFarHoldY) or, with
     * -PdevFarDhShot, at the vantage, along -PdevFarGlassYaw (the camera's heading by default), so the mirror view
     * shows the far terrain; removed at the end. The vantage's view then turns to -PdevYaw.
     */
    private static final boolean FAR_GLASS = Boolean.getBoolean("shaderscompanion.devFarGlass");
    private static final float FAR_GLASS_YAW = Integer.getInteger("shaderscompanion.devFarGlassYaw", (int) FAR_SHIFT_YAW);
    /** M4: -PdevFarViewDump dumps the cube (and the mirror view) at the end of the first two toggle rounds. */
    private static final boolean FAR_VIEW_DUMP = Boolean.getBoolean("shaderscompanion.devFarViewDump");
    private static BlockPos glassAt;
    private static int stopAt = -1;
    private static float glassYaw;
    /**
     * Fix round 2: -PdevFarNearBlocks puts a smooth quartz platform (9 x 9, the feet's block row below the held camera)
     * with birch planks and oak slabs on it ahead of the camera, as on the user's rooftop (far shadows on a near slab);
     * placed only in air, removed at the end only where they are those blocks.
     */
    private static final boolean FAR_NEAR_BLOCKS = Boolean.getBoolean("shaderscompanion.devFarNearBlocks");
    /** -PdevFarNearDrop=N: the platform N blocks below the held camera (0: at its feet). */
    private static final int FAR_NEAR_DROP = Integer.getInteger("shaderscompanion.devFarNearDrop", 0);
    private static BlockPos nearBlocksAt;
    /**
     * Fix round 2: -PdevFarAuto (with -PdevChunky, in the far draw check) checks the far store's auto import: the join's
     * scan of the saved chunks (logged until it is up to date), then Chunky generates a square of -PdevFarAutoRadius
     * blocks (160) around -PdevFarAutoX, -PdevFarAutoZ (3000, -320: land the client never loads) while the camera stays
     * at the vantage; once Chunky has ended and the auto import is up to date again, the store's level-2 map of the square
     * (screenshots/shaderscompanion-farauto-L2.png) shows whether its chunks were stored, and the game quits. The far
     * world keeps the square's region files (r.5.-2 to r.6.0: delete them to undo; the store keeps their terrain).
     */
    private static final boolean FAR_AUTO = Boolean.getBoolean("shaderscompanion.devFarAuto");
    private static final int FAR_AUTO_X = Integer.getInteger("shaderscompanion.devFarAutoX", 3000);
    private static final int FAR_AUTO_Z = Integer.getInteger("shaderscompanion.devFarAutoZ", -320);
    private static final int FAR_AUTO_RADIUS = Integer.getInteger("shaderscompanion.devFarAutoRadius", 160);
    private static int farAutoStep;
    private static int farAutoQuietTicks;

    private static void farAutoCheck(Minecraft mc, int t) {
        int[] auto = FarChecks.autoImport();
        boolean upToDate = auto != null && auto[0] == 0;
        if (t % 200 == 0 || t == 1) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, auto import {} s (step {}): waiting {}, read {}{} | Far store: {}", t / 20, farAutoStep,
                    auto == null ? -1 : auto[0], auto == null ? 0 : auto[1], chunkyRunning("minecraft:overworld") ? ", Chunky running" : "", FarStore.status());
        }
        farAutoQuietTicks = upToDate && !chunkyRunning("minecraft:overworld") ? farAutoQuietTicks + 1 : 0;
        if (farAutoStep == 0 && (farAutoQuietTicks >= 100 || t >= 12000)) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, auto import: the join's scan done after {} s ({} read) | Far store: {}", t / 20,
                    auto == null ? 0 : auto[1], FarStore.status());
            chunkyGenerate("minecraft:overworld", FAR_AUTO_X, FAR_AUTO_Z, FAR_AUTO_RADIUS);
            farAutoStep = 1;
            farAutoQuietTicks = 0;
            phaseTick = ticks;
        } else if (farAutoStep == 1 && t > 100 && (farAutoQuietTicks >= 200 || t >= 12000)) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, auto import: Chunky's square read after {} s ({} read this session) | Far store: {}", t / 20,
                    auto == null ? 0 : auto[1], FarStore.status());
            int r = FAR_AUTO_RADIUS + 32;
            FarStore.map(2, FAR_AUTO_X - r, FAR_AUTO_Z - r, 2 * r, 2 * r, "shaderscompanion-farauto-L2.png")
                    .whenComplete((line, error) -> ShadersCompanion.LOGGER.info("Shaders Companion dev run, auto import map: {}", error != null ? error : line));
            farAutoStep = 2;
            phaseTick = ticks;
        } else if (farAutoStep == 2 && t >= 100) {
            finish(mc);
        }
    }
    /** Fix round 2: -PdevHeap logs the Java heap in use after two full collections at the end of each toggle or far round. */
    private static final boolean HEAP = Boolean.getBoolean("shaderscompanion.devHeap");

    private static String heapLine() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        System.gc();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long direct = 0;
        for (var pool : java.lang.management.ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            direct += pool.getMemoryUsed();
        }
        long process = 0;
        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            // Windows: the process's private bytes
            process = os.getCommittedVirtualMemorySize();
        }
        return String.format("heap in use after GC %d MB (committed %d MB, max %d MB), direct buffers %d MB, process private %d MB | %s | Far store: %s", used >> 20,
                runtime.totalMemory() >> 20, runtime.maxMemory() >> 20, direct >> 20, process >> 20, vram(), FarStore.status());
    }

    private static void nearBlocks(net.minecraft.server.level.ServerLevel level, BlockPos at, float yaw, boolean place) {
        double forwardX = -Math.sin(Math.toRadians(yaw));
        double forwardZ = Math.cos(Math.toRadians(yaw));
        int changed = 0;
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new java.util.LinkedHashMap<>();
        for (int ahead = -2; ahead <= 6; ahead++) {
            for (int side = -4; side <= 4; side++) {
                blocks.put(trunkOffset(at, at.getY() - 1, ahead, side, forwardX, forwardZ), Blocks.SMOOTH_QUARTZ.defaultBlockState());
            }
        }
        blocks.put(trunkOffset(at, at.getY(), 3, 1, forwardX, forwardZ), Blocks.BIRCH_PLANKS.defaultBlockState());
        blocks.put(trunkOffset(at, at.getY(), 3, -2, forwardX, forwardZ), Blocks.OAK_SLAB.defaultBlockState());
        blocks.put(trunkOffset(at, at.getY(), 5, 0, forwardX, forwardZ), Blocks.OAK_SLAB.defaultBlockState());
        blocks.put(trunkOffset(at, at.getY(), 5, 3, forwardX, forwardZ), Blocks.BIRCH_PLANKS.defaultBlockState());
        for (var entry : blocks.entrySet()) {
            var state = level.getBlockState(entry.getKey());
            if (place ? state.isAir() : state.is(entry.getValue().getBlock())) {
                level.setBlock(entry.getKey(), place ? entry.getValue() : Blocks.AIR.defaultBlockState(), 2);
                changed++;
            }
        }
        ShadersCompanion.LOGGER.info("Shaders Companion dev run: near blocks at {} along yaw {} {} ({} blocks)", at.toShortString(), yaw, place ? "placed" : "removed",
                changed);
    }

    /** M4: the walls placed (glassWalls), remembered so finish removes them. */
    private static void placeGlass(Minecraft mc, BlockPos at, float yaw) {
        glassAt = at;
        glassYaw = yaw;
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> glassWalls(server.overworld(), at, yaw, true));
    }

    /** M4: the far terrain in the mod's views over the frames since the last call, as a log line. */
    private static String viewsLine() {
        long[] v = FarViews.numbersForChecks();
        return String.format("cube faces GPU %s ms a frame (%d a frame), mirror planes GPU %s ms a frame (%d a frame), of it selection %s ms; %d frames timed; "
                        + "%d views, %d nodes, %d quads a frame (medians of %d frames); %d commands a region%s", ms(v[0]), v[1], ms(v[2]), v[3], ms(v[4]), v[5], v[6], v[7],
                v[8], v[9], v[10], v[11] != 0 ? "; STOPPED after an error" : "");
    }
    /** The visits: dimension, the spot Chunky generates around (x, z), and per view the camera (y, yaw, pitch); y is searched for open air. */
    private static final String[] DIM_IDS = {"minecraft:the_nether", "minecraft:the_end"};
    private static final int[][] DIM_CENTRES = {{0, 0}, {1300, 0}};
    private static final float[][][] DIM_VIEWS = {
            {{40, 0, 5}, {40, 180, 5}, {45, 90, -25}, {100, 270, 20}, {150, 0, 40}},
            {{80, 0, 10}, {80, 180, 10}, {80, 270, 25}, {170, 90, 45}}};
    private static final String[] DIM_TAGS = {"nether", "end"};
    private static int dimIndex;
    private static int dimView;
    private static double holdX;
    private static double holdY;
    private static double holdZ;
    private static float holdYaw;
    private static float holdPitch;
    private static long dimGlFrom;
    private static String dimImportBefore;

    private static void hold(Minecraft mc) {
        mc.player.setDeltaMovement(0.0, 0.0, 0.0);
        mc.player.setPos(holdX, holdY, holdZ);
        mc.player.setYRot(holdYaw);
        mc.player.yRotO = holdYaw;
        mc.player.setXRot(holdPitch);
        mc.player.xRotO = holdPitch;
    }

    private static void holdAt(Minecraft mc, double x, double y, double z, float yaw, float pitch) {
        holdX = x;
        holdY = y;
        holdZ = z;
        holdYaw = yaw;
        holdPitch = pitch;
        hold(mc);
    }

    /** The far terrain meshed and on the GPU, and the store settled, for this many ticks in a row (counted in storeSettledTicks). */
    private static boolean farReady(int t) {
        if (t % 20 == 0) {
            FarChecks.terrainNumbers().whenComplete((n, e) -> meshNumbers = n);
        }
        long[] n = meshNumbers;
        boolean ready = FarChecks.settled() && n != null && n[n.length - 11 + 5] == 0 && FarChecks.gpuNumbers(false)[0] == 0;
        storeSettledTicks = ready ? storeSettledTicks + 1 : 0;
        return storeSettledTicks >= 100;
    }

    private static void shiftShot(Minecraft mc, String name) {
        String tag = System.getProperty("shaderscompanion.devTag", "run");
        Screenshot.grab(mc.gameDirectory, "shaderscompanion-farshift-" + tag + "-" + name + ".png", mc.getMainRenderTarget(), message -> { });
        ShadersCompanion.LOGGER.info("Shaders Companion dev run, far shift shot {} at {} {} {} (yaw {}, pitch {}): Far terrain: {}", name, String.format("%.2f", holdX),
                String.format("%.2f", holdY), String.format("%.2f", holdZ), holdYaw, holdPitch, FarTerrain.terrainStatus());
    }

    /**
     * -PdevFarShift=N: 92 holds the camera at the spot (-PdevFarShiftX/Y/Z, -PdevYaw, -PdevPitch) until the far terrain
     * is meshed and on the GPU and takes shot "a"; 93 holds it N blocks to the right and takes "b"; 94 back at the spot,
     * "c"; 95 moves right at 15 blocks a second from the spot and takes a shot every tick ("m0" to "m7"); 96 holds at the
     * spot again for "d", then quits or goes on to -PdevFarDims. Shots shaderscompanion-farshift-<tag>-<name>.png.
     */
    private static void farShiftCheck(Minecraft mc, int t) {
        double x = FAR_SHIFT_X + 0.5;
        double z = FAR_SHIFT_Z + 0.5;
        double yawRad = Math.toRadians(FAR_SHIFT_YAW);
        // Minecraft's look vector is (-sin yaw, cos yaw); its right is (-cos yaw, -sin yaw)
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);
        if (phase == 92) {
            if (t == 1) {
                mc.player.connection.sendCommand("tp @s " + x + " " + FAR_SHIFT_Y + " " + z + " " + FAR_SHIFT_YAW + " " + FAR_PITCH);
            }
            if (t > 2) {
                holdAt(mc, x, FAR_SHIFT_Y, z, FAR_SHIFT_YAW, FAR_PITCH);
            }
            if (t % 200 == 0) {
                long[] n = meshNumbers;
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far shift waiting {} s: store settled {}, filling {}, meshes waiting to upload {} | Far terrain: {}", t / 20,
                        FarChecks.settled(), n == null ? -1 : n[n.length - 11 + 5], FarChecks.gpuNumbers(false)[0], FarTerrain.terrainStatus());
            }
            if ((farReady(t) && t >= 300) || t >= 900) {
                shiftShot(mc, "a");
                phase = 93;
                phaseTick = ticks;
            }
        } else if (phase == 93) {
            holdAt(mc, x + rightX * FAR_SHIFT, FAR_SHIFT_Y, z + rightZ * FAR_SHIFT, FAR_SHIFT_YAW, FAR_PITCH);
            if (t >= SHIFT_HOLD_TICKS) {
                shiftShot(mc, "b");
                phase = 94;
                phaseTick = ticks;
            }
        } else if (phase == 94) {
            holdAt(mc, x, FAR_SHIFT_Y, z, FAR_SHIFT_YAW, FAR_PITCH);
            if (t >= SHIFT_HOLD_TICKS) {
                shiftShot(mc, "c");
                phase = 95;
                phaseTick = ticks;
            }
        } else if (phase == 95) {
            // 15 blocks a second to the right, a shot every tick (the frame drawn at that tick's position)
            double d = Math.max(0, t - 1) * 0.75;
            holdAt(mc, x + rightX * d, FAR_SHIFT_Y, z + rightZ * d, FAR_SHIFT_YAW, FAR_PITCH);
            if (t >= 1 && t <= 8) {
                shiftShot(mc, "m" + (t - 1));
            }
            if (t >= 8) {
                phase = 96;
                phaseTick = ticks;
            }
        } else if (phase == 96) {
            holdAt(mc, x, FAR_SHIFT_Y, z, FAR_SHIFT_YAW, FAR_PITCH);
            if (t >= SHIFT_HOLD_TICKS) {
                shiftShot(mc, "d");
                if (FAR_DIMS) {
                    phase = 100;
                    storeSettledTicks = 0;
                } else {
                    mc.player.connection.sendCommand("tp @s " + FAR_X + ".5 " + farY + " " + FAR_Z + ".5 " + FAR_YAW + " " + FAR_PITCH);
                    FarChecks.draw(false);
                    phase = 87;
                }
                phaseTick = ticks;
            }
        }
    }

    /** Chunky (-PdevChunky) generates a square of the radius around a spot in a dimension. */
    private static void chunkyGenerate(String dimension, int x, int z, int radius) {
        try {
            Object chunky = Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
            Object api = chunky.getClass().getMethod("getApi").invoke(chunky);
            Class<?> apiClass = Class.forName("org.popcraft.chunky.api.ChunkyAPI");
            boolean started = (boolean) apiClass.getMethod("startTask", String.class, String.class, double.class, double.class, double.class, double.class, String.class)
                    .invoke(api, dimension, "square", x + 0.5, z + 0.5, (double) radius, (double) radius, "concentric");
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: Chunky task in {} around {} {} radius {}: {}", dimension, x, z, radius, started ? "started" : "not started");
        } catch (ReflectiveOperationException | LinkageError e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: Chunky failed (needs -PdevChunky)", e);
        }
    }

    private static boolean chunkyRunning(String dimension) {
        try {
            Object chunky = Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
            Object api = chunky.getClass().getMethod("getApi").invoke(chunky);
            return (boolean) Class.forName("org.popcraft.chunky.api.ChunkyAPI").getMethod("isRunning", String.class).invoke(api, dimension);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    /**
     * The nearest height to y (within 40 blocks, the given column first, then columns around it, out to 96 blocks) with
     * 5 x 5 x 5 blocks of air around the camera and 48 blocks of air along the view.
     */
    private static double[] openAir(Minecraft mc, double x, double y, double z, float yaw, float pitch) {
        double lookX = -Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
        double lookY = -Math.sin(Math.toRadians(pitch));
        double lookZ = Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int r = 0; r <= 96; r += 8) {
            for (int dx = -r; dx <= r; dx += 8) {
                for (int dz = -r; dz <= r; dz += 8) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy = 0; dy <= 40; dy++) {
                        for (int sign = -1; sign <= 1; sign += 2) {
                            int cy = (int) Math.floor(y) + sign * dy;
                            boolean open = true;
                            for (int ox = -2; ox <= 2 && open; ox++) {
                                for (int oy = -2; oy <= 2 && open; oy++) {
                                    for (int oz = -2; oz <= 2 && open; oz++) {
                                        open = mc.level.getBlockState(pos.set((int) Math.floor(x) + dx + ox, cy + oy, (int) Math.floor(z) + dz + oz)).isAir();
                                    }
                                }
                            }
                            for (int step = 3; step <= 48 && open; step++) {
                                open = mc.level.getBlockState(pos.set((int) Math.floor(Math.floor(x) + dx + 0.5 + lookX * step), (int) Math.floor(cy + 0.2 + lookY * step),
                                        (int) Math.floor(Math.floor(z) + dz + 0.5 + lookZ * step))).isAir();
                            }
                            if (open) {
                                return new double[]{Math.floor(x) + dx + 0.5, cy + 0.2, Math.floor(z) + dz + 0.5};
                            }
                        }
                    }
                }
            }
        }
        return new double[]{x, y, z};
    }

    /**
     * -PdevFarDims (with -PdevChunky): per dimension (the Nether, then the End around its outer islands), 100 goes there,
     * Chunky generates -PdevFarDimsRadius around the spot and the world is saved; 101 imports the saved chunks into that
     * dimension's far store; 102 waits until the import ended and the far terrain is meshed and on the GPU; 103 to 104
     * take each view's shot (camera at open air near the view's height), logging the far terrain's status and the frames
     * with far terrain GL calls; then the next dimension, and back to the overworld (87). Shots
     * shaderscompanion-fardims-<tag>-<dimension>-<view>.png. Delete DIM-1 and DIM1's region, entities and poi folders
     * and the store's minecraft_the_nether and minecraft_the_end folders afterwards (keep DIM-1/data and DIM1/data).
     */
    private static void farDimsCheck(Minecraft mc, int t) {
        int d = Math.min(dimIndex, DIM_IDS.length - 1);
        String dim = DIM_IDS[d];
        int cx = DIM_CENTRES[d][0];
        int cz = DIM_CENTRES[d][1];
        float[] view = DIM_VIEWS[d][Math.min(dimView, DIM_VIEWS[d].length - 1)];
        if (phase == 100) {
            if (t == 1) {
                mc.player.connection.sendCommand("execute in " + dim + " run tp @s " + cx + ".5 " + (int) view[0] + " " + cz + ".5 " + view[1] + " " + view[2]);
            } else if (t == 60) {
                chunkyGenerate(dim, cx, cz, FAR_DIMS_RADIUS);
            }
            if (t > 2) {
                holdAt(mc, cx + 0.5, view[0], cz + 0.5, view[1], view[2]);
            }
            if (t % 200 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far dims: {} generating {} s | Far store: {}", dim, t / 20, FarStore.status());
            }
            if (t >= 200 && !chunkyRunning(dim) || t >= 12000) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far dims: {} generated after {} s", dim, t / 20);
                mc.player.connection.sendCommand("save-all flush");
                phase = 101;
                phaseTick = ticks;
            }
        } else if (phase == 101) {
            holdAt(mc, cx + 0.5, view[0], cz + 0.5, view[1], view[2]);
            if (t == 100) {
                dimImportBefore = FarChecks.importProgress();
                String line = FarStore.startImport(FAR_DIMS_RADIUS / 16 + 2);
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far dims: {} import: {}", dim, line);
                phase = 102;
                phaseTick = ticks;
                storeSettledTicks = 0;
            }
        } else if (phase == 102) {
            holdAt(mc, cx + 0.5, view[0], cz + 0.5, view[1], view[2]);
            String imported = FarChecks.importProgress();
            boolean importDone = imported != null && imported.startsWith("Far store import") && !imported.equals(dimImportBefore);
            boolean ready = farReady(t) && importDone;
            if (t % 200 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far dims: {} {} s, import {} | Far terrain: {}", dim, t / 20, imported, FarTerrain.terrainStatus());
            }
            if (ready && t >= 400 || t >= 1200) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far dims: {} {} after {} s: import {} | Far store: {} | Far terrain: {}", dim,
                        ready ? "ready" : "NOT ready", t / 20, imported, FarStore.status(), FarTerrain.terrainStatus());
                logWhenDone(FarTerrain.terrainStats());
                dimView = 0;
                phase = 103;
                phaseTick = ticks;
            }
        } else if (phase == 103) {
            if (t == 1) {
                double[] spot = openAir(mc, cx + 0.5, view[0], cz + 0.5, view[1], view[2]);
                holdAt(mc, spot[0], spot[1], spot[2], view[1], view[2]);
                mc.player.connection.sendCommand("tp @s " + spot[0] + " " + spot[1] + " " + spot[2] + " " + view[1] + " " + view[2]);
                dimGlFrom = FarRenderer.glFramesForChecks();
            } else if (t > 2) {
                hold(mc);
            }
            if (t == 200) {
                String tag = System.getProperty("shaderscompanion.devTag", "run");
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-fardims-" + tag + "-" + DIM_TAGS[dimIndex] + "-" + dimView + ".png", mc.getMainRenderTarget(), message -> { });
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far dims: {} view {} at {} {} {} (yaw {}, pitch {}): frames with far terrain GL calls {} of the last 10 s; "
                                + "info texture: {} | Far terrain: {} | {}", dim, dimView, String.format("%.1f", holdX), String.format("%.1f", holdY), String.format("%.1f", holdZ),
                        holdYaw, holdPitch, FarRenderer.glFramesForChecks() - dimGlFrom, infoLine(), FarTerrain.terrainStatus(), frameTiming());
                // The far terrain alone over a plain sky, for what it holds there
                FarRenderer.aloneForChecks(true);
            } else if (t == 230) {
                String tag = System.getProperty("shaderscompanion.devTag", "run");
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-fardims-" + tag + "-" + DIM_TAGS[dimIndex] + "-" + dimView + "-alone.png", mc.getMainRenderTarget(),
                        message -> { });
                FarRenderer.aloneForChecks(false);
            } else if (t >= 250) {
                dimView++;
                phaseTick = ticks;
                if (dimView >= DIM_VIEWS[dimIndex].length) {
                    phase = 104;
                }
            }
        } else if (phase == 104) {
            if (t == 1) {
                logWhenDone(FarTerrain.terrainStats());
            } else if (t >= 40) {
                dimIndex++;
                dimView = 0;
                phaseTick = ticks;
                storeSettledTicks = 0;
                if (dimIndex < DIM_IDS.length) {
                    phase = 100;
                } else {
                    mc.player.connection.sendCommand("execute in minecraft:overworld run tp @s " + FAR_X + ".5 " + farY + " " + FAR_Z + ".5 " + FAR_YAW + " " + FAR_PITCH);
                    phase = 105;
                }
            }
        } else if (phase == 105) {
            if (t % 100 == 0) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far dims: back in {} {} s | Far terrain: {}", mc.level.dimension().location(), t / 20, FarTerrain.terrainStatus());
            }
            if (t >= 600) {
                String tag = System.getProperty("shaderscompanion.devTag", "run");
                Screenshot.grab(mc.gameDirectory, "shaderscompanion-fardims-" + tag + "-overworld.png", mc.getMainRenderTarget(), message -> { });
                FarChecks.draw(false);
                phase = 87;
                phaseTick = ticks;
            }
        }
    }

    private static String ms(long nanos) {
        return String.format("%.3f", nanos / 1.0e6);
    }

    /** Keeps the player at a spot in the air at the flight's height (client side, as the other flights do). */
    private static void holdAt(Minecraft mc, double x, double z) {
        mc.player.setDeltaMovement(0.0, 0.0, 0.0);
        mc.player.setPos(x, STORE_FLY_Y, z);
        mc.player.setYRot(FAR_YAW);
        mc.player.yRotO = FAR_YAW;
        mc.player.setXRot(FAR_PITCH);
        mc.player.xRotO = FAR_PITCH;
    }

    private static void startFlightWindow(boolean store) {
        flightFrames = new java.util.ArrayList<>();
        flightGpu = new java.util.ArrayList<>();
        flightWall = System.nanoTime();
        if (store) {
            FarChecks.recordFrames(true);
            flightWorkerCpu = FarChecks.workerCpuNanos();
            flightRenderCpu = renderCpu();
            flightServerCpu = serverCpu();
            flightCounters = null;
            FarChecks.counters().whenComplete((counters, error) -> flightCounters = counters);
        }
    }

    private static long renderCpu() {
        return java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    private static long serverCpu() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (serverThreadId < 0) {
            for (var info : bean.getThreadInfo(bean.getAllThreadIds())) {
                if (info != null && info.getThreadName().equals("Server thread")) {
                    serverThreadId = info.getThreadId();
                }
            }
        }
        return serverThreadId < 0 ? 0 : bean.getThreadCpuTime(serverThreadId);
    }

    /** The main thread's time per frame from FarStore.recordFramesForChecks: average, 99th percentile, max, per section. */
    private static String framesSummary(long[] recorded) {
        if (recorded == null || recorded.length < 2) {
            return "no frames";
        }
        int n = recorded.length - 2;
        long sections = recorded[n];
        long lost = recorded[n + 1];
        long[] frames = java.util.Arrays.copyOf(recorded, n);
        java.util.Arrays.sort(frames);
        long sum = 0;
        int over1ms = 0;
        for (long f : frames) {
            sum += f;
            if (f > 1_000_000L) {
                over1ms++;
            }
        }
        return String.format("%d frames, %.1f us a frame on average, 99th percentile %.0f us, max %.0f us, %d frames over 1 ms; %d sections copied (%.2f us each), "
                        + "%d not copied at unload", n, n == 0 ? 0.0 : sum / 1.0e3 / n, n == 0 ? 0.0 : frames[Math.min(n - 1, (int) (n * 0.99))] / 1.0e3,
                n == 0 ? 0.0 : frames[n - 1] / 1.0e3, over1ms, sections, sections == 0 ? 0.0 : sum / 1.0e3 / sections, lost);
    }

    /** Chunk load to first copy (FarStore.recordedLoadsForChecks): median, 90th percentile, max, and the share within 2 seconds. */
    private static String loadsSummary(long[] loads) {
        if (loads.length == 0) {
            return "no chunk loads";
        }
        java.util.Arrays.sort(loads);
        int within = 0;
        for (long l : loads) {
            if (l <= 2_000_000_000L) {
                within++;
            }
        }
        return String.format("%d chunks loaded, first copy after %.2f s (median), %.2f s (90th percentile), %.2f s at most, %.0f%% within 2 s", loads.length,
                loads[loads.length / 2] / 1.0e9, loads[Math.min(loads.length - 1, (int) (loads.length * 0.9))] / 1.0e9, loads[loads.length - 1] / 1.0e9,
                100.0 * within / loads.length);
    }

    private static String frameTimesSummary() {
        if (flightFrames == null || flightFrames.isEmpty()) {
            return "no frame times";
        }
        double[] f = flightFrames.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double[] g = flightGpu.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return String.format("frames %d: frame time median %.2f ms, 99th percentile %.2f, max %.2f; GPU median %.2f ms, 99th percentile %.2f", f.length, f[f.length / 2],
                f[Math.min(f.length - 1, (int) (f.length * 0.99))], f[f.length - 1], g[g.length / 2], g[Math.min(g.length - 1, (int) (g.length * 0.99))]);
    }

    private static int tunnelStep;
    private static final java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> tunnelSaved = new java.util.LinkedHashMap<>();

    /** The world check around the tunnel: chunks -37 to -33 by -22 to -18, sections 2 to 6 (y 32 to 111). */
    private static java.util.concurrent.CompletableFuture<String[]> tunnelCheck() {
        return FarChecks.compareWithWorld(-35, -20, 2, 2, 6);
    }

    /**
     * -PdevFarTunnel: step 0 fills a box around the tunnel with stone and puts granite where its walls will be, hidden
     * until the tunnel opens it: the layer below (y 63, the next section and node down), the row to the north (z -321,
     * the next chunk and node), the row to the south and the layer above (the same section) and the two end blocks (the
     * next chunk's sections in the same node). Step 1 digs the tunnel: y 64, z -320, x -560 to -529, along a node corner
     * and across the chunk and node border at x -544. Step 2 puts every block of the box back as it was.
     */
    private static void farTunnel(net.minecraft.server.level.ServerLevel level, int step) {
        int changed = 0;
        if (step == 0) {
            // A solid stone box around the tunnel (x -562 to -527, y 61 to 67, z -323 to -317: the far world has a cave
            // there), granite around the tunnel's line and the line itself stone, every block kept to put back
            tunnelSaved.clear();
            for (int x = -562; x <= -527; x++) {
                for (int y = 61; y <= 67; y++) {
                    for (int z = -323; z <= -317; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        tunnelSaved.put(pos, level.getBlockState(pos));
                        boolean line = y == 64 && z == -320 && x >= -560 && x <= -529;
                        boolean ring = x >= -560 && x <= -529 && (z == -320 && (y == 63 || y == 65) || y == 64 && (z == -321 || z == -319))
                                || y == 64 && z == -320 && (x == -561 || x == -528);
                        var wanted = ring && !line ? Blocks.GRANITE.defaultBlockState() : Blocks.STONE.defaultBlockState();
                        if (level.getBlockState(pos) != wanted) {
                            level.setBlock(pos, wanted, 2);
                            changed++;
                        }
                    }
                }
            }
        } else {
            for (var entry : tunnelSaved.entrySet()) {
                BlockPos pos = entry.getKey();
                boolean tunnel = pos.getY() == 64 && pos.getZ() == -320 && pos.getX() >= -560 && pos.getX() <= -529;
                var wanted = step == 2 ? entry.getValue() : tunnel ? Blocks.AIR.defaultBlockState() : null;
                if (wanted != null && level.getBlockState(pos) != wanted) {
                    level.setBlock(pos, wanted, 2);
                    changed++;
                }
            }
        }
        ShadersCompanion.LOGGER.info("Shaders Companion dev run: far store tunnel step {} ({} blocks changed)", step, changed);
    }

    /**
     * -PdevFarShapes: thin shapes floating at y 200 over the plains south of the vantage, for the coarser levels' rules
     * (placed only in air, taken away only where they are what was placed): from x -600, z -230, a one-block oak roof
     * (24 x 24), a stone wall (24 long, 8 high, along x at z -200), a diagonal stone brick wall, a glass wall (along z at
     * x -540), fence posts 3 blocks apart, a stone slab roof and an oak leaf canopy filled in a checkerboard.
     */
    private static void farShapes(net.minecraft.server.level.ServerLevel level, boolean place) {
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> shapes = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            for (int j = 0; j < 24; j++) {
                shapes.put(new BlockPos(-600 + i, 200, -230 + j), Blocks.OAK_PLANKS.defaultBlockState());
                shapes.put(new BlockPos(-520 + i, 200, -230 + j), Blocks.STONE_SLAB.defaultBlockState());
                if ((i + j & 1) == 0) {
                    shapes.put(new BlockPos(-490 + i, 200, -230 + j),
                            Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
                }
            }
            for (int y = 196; y <= 203; y++) {
                shapes.put(new BlockPos(-600 + i, y, -200), Blocks.STONE.defaultBlockState());
                shapes.put(new BlockPos(-570 + i, y, -230 + i), Blocks.STONE_BRICKS.defaultBlockState());
                shapes.put(new BlockPos(-540, y, -230 + i), Blocks.GLASS.defaultBlockState());
            }
        }
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                for (int y = 196; y <= 200; y++) {
                    shapes.put(new BlockPos(-530 + 3 * i, y, -230 + 3 * j), Blocks.OAK_FENCE.defaultBlockState());
                }
            }
        }
        int changed = 0;
        for (var entry : shapes.entrySet()) {
            var state = level.getBlockState(entry.getKey());
            if (place ? state.isAir() : state.is(entry.getValue().getBlock())) {
                level.setBlock(entry.getKey(), place ? entry.getValue() : Blocks.AIR.defaultBlockState(), 2);
                changed++;
            }
        }
        ShadersCompanion.LOGGER.info("Shaders Companion dev run: far store test shapes {} ({} blocks)", place ? "placed" : "removed", changed);
    }

    /** Logs a far store answer when the worker has it (and counts down the maps the check waits for). */
    private static void logWhenDone(java.util.concurrent.CompletableFuture<String[]> lines) {
        lines.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            for (String line : error != null ? new String[]{"failed: " + error} : result) {
                ShadersCompanion.LOGGER.info("Shaders Companion dev run, far store: {}", line);
            }
            storeMapsLeft--;
        }));
    }

    private static void setShaders(Minecraft mc, boolean on) {
        try {
            Iris.toggleShaders(mc, on);
        } catch (Exception e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: turning shaders {} failed", on ? "on" : "off", e);
        }
    }

    private static void finish(Minecraft mc) {
        if (nearBlocksAt != null && mc.getSingleplayerServer() != null) {
            BlockPos at = nearBlocksAt;
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> nearBlocks(server.overworld(), at, FAR_SHIFT_YAW, false));
            nearBlocksAt = null;
            if (glassAt == null) {
                stopAt = ticks + 40;
                return;
            }
        }
        if (glassAt != null && mc.getSingleplayerServer() != null) {
            // M4: the walls go first, and the game stops 2 seconds later (the far store sees the change too)
            BlockPos at = glassAt;
            float yaw = glassYaw;
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> glassWalls(server.overworld(), at, yaw, false));
            glassAt = null;
            stopAt = ticks + 40;
            return;
        }
        if (renderBefore >= 0) {
            mc.options.renderDistance().set(renderBefore);
        }
        if (fovBefore >= 0) {
            mc.options.fov().set(fovBefore);
        }
        if (!shadersOn) {
            setShaders(mc, true);
        }
        if (System.getProperty("shaderscompanion.devDh") != null) {
            ShadersCompanion.LOGGER.info("Shaders Companion dev run: Distant Horizons range {}", farDhRange());
        }
        phase = -1;
        ShadersCompanion.LOGGER.info("Shaders Companion dev run done");
        mc.stop();
    }
}
