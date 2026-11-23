package com.rinkynooble.shaderscompanion.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.rinkynooble.shaderscompanion.client.far.FarStore;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * {@code /shaderscompanion debug} shows the cube faces in a corner, {@code status} prints the state, {@code dump} saves
 * the views as pictures, and {@code on} and {@code off} start and stop all drawing. {@code lod map <level> [radius]}
 * saves a map of the far store's level around you and {@code lod stats} prints its sizes (both in development).
 */
public final class DebugOverlay {
    private static boolean visible;

    private DebugOverlay() {
    }

    public static void setVisible(boolean show) {
        visible = show;
    }

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shaderscompanion")
                .then(Commands.literal("on").executes(context -> setEnabled(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setEnabled(context.getSource(), false)))
                .then(Commands.literal("debug").executes(context -> {
                    visible = !visible;
                    context.getSource().sendSuccess(() -> Component.literal("Shaders Companion view " + (visible ? "shown" : "hidden")), false);
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    for (String line : statusLines()) {
                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("dump").executes(context -> {
                    String message;
                    try {
                        String prefix = CubeDump.dump();
                        message = prefix == null ? "Nothing drawn yet" : "Saved screenshots/" + prefix + "-preview.png, -albedo.png and -distance.png";
                    } catch (Exception e) {
                        message = "Saving failed: " + e;
                    }
                    String line = message;
                    context.getSource().sendSuccess(() -> Component.literal(line), false);
                    return 1;
                }))
                .then(Commands.literal("lod")
                        .then(Commands.literal("map")
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, 6))
                                        .executes(context -> lodMap(context.getSource(), IntegerArgumentType.getInteger(context, "level"), 4))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 32))
                                                .executes(context -> lodMap(context.getSource(), IntegerArgumentType.getInteger(context, "level"),
                                                        IntegerArgumentType.getInteger(context, "radius"))))))
                        .then(Commands.literal("stats").executes(context -> {
                            reply(context.getSource(), FarStore.stats());
                            return 1;
                        }))));
    }

    /** The far store's map of a level, radius nodes around you, written by its worker; the chat says when it is saved. */
    private static int lodMap(CommandSourceStack source, int level, int radius) {
        reply(source, FarStore.mapAroundCamera(level, radius).thenApply(line -> new String[]{line}));
        return 1;
    }

    /** Sends lines to the chat once the far store's worker has them (on the main thread). */
    private static void reply(CommandSourceStack source, CompletableFuture<String[]> lines) {
        lines.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            for (String line : error != null ? new String[]{"Failed: " + error} : result) {
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }));
    }

    /** Turns all of the mod's drawing on or off, now and in the config file. */
    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        Config.ENABLED.set(enabled);
        Config.ENABLED.save();
        source.sendSuccess(() -> Component.literal(enabled
                ? "Shaders Companion on: the extra views are drawn while the shader pack reads them"
                : "Shaders Companion off: no extra views are drawn, and reflections fall back to the shader pack's own"), false);
        return 1;
    }

    public static String[] statusLines() {
        String gpu = GpuTimer.nanos < 0 ? "n/a" : String.format("%.2f ms", GpuTimer.nanos / 1.0e6);
        return new String[]{
                "Shaders Companion: " + (CubeView.hookCalled ? CubeView.status : "frame hook not running (another mod may block it)"),
                String.format("Faces %d px, %d per frame; CPU %.2f ms (entities %.2f, mirror view %.2f + plane search %.2f), GPU %s", CubeView.faceSize,
                        Config.FACES_PER_FRAME.get(), CubeView.cpuNanos / 1.0e6, (CubeView.playerNanos + CubeView.collectNanos) / 1.0e6, CubeView.mirrorNanos / 1.0e6,
                        MirrorView.cpuNanos / 1.0e6, gpu),
                String.format("Last frame: %d sections, %d draws, %d quads, %d queued to build",
                        ViewRenderer.sectionsDrawn, ViewRenderer.drawCalls, ViewRenderer.quadsDrawn, CubeView.sectionsQueued),
                String.format("Entities: %d, block entities: %d (%d kept from earlier frames; %s); Distant Horizons: %s", CubeView.entitiesDrawn,
                        CubeView.blockEntitiesDrawn, CubeView.entitiesReused, CubeView.entityMemory(),
                        CubeView.lodRangeForChecks() > 0 ? ViewRenderer.lodContainers + " LOD sections, range " + CubeView.lodRange + " blocks"
                                : !Config.DISTANT_HORIZONS.get() ? "turned off in the config" : Lods.reason()),
                String.format("Mirror view: %s%s", MirrorView.status, MirrorView.active() && CubeView.mirrorPlayerDrawn ? ", your player in it" : ""),
                "Rain cover: " + RainCover.status,
                "Lattice: " + Lattice.status,
                "Materials: " + Materials.status,
                "Light leak fix: " + LightLeaks.status(),
                "Closed doors: " + DoorLight.status(),
                "Far store: " + FarStore.status(),
                "Bundled shader pack: " + BundledPack.status
                        + (Gl.mismatches == 0 ? "" : "; GL state out of step with Minecraft's record " + Gl.mismatches + " times (see the log)")
        };
    }

    static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        if (!visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int textureWidth = CubeTextures.PREVIEW.width();
        int textureHeight = CubeTextures.PREVIEW.height();
        int face = Math.max(32, Math.min(screenWidth / 7, screenHeight / 5));
        int x0 = 4;
        int y0 = 4;
        graphics.fill(x0 - 2, y0 - 2, x0 + face * 3 + 2, y0 + face * 2 + 2, 0xC0101010);
        if (textureWidth > 0) {
            // GL textures start at the bottom row, the GUI at the top, so the image is drawn flipped.
            graphics.blit(CubeTextures.PREVIEW_ID, x0, y0, face * 3, face * 2, 0.0F, textureHeight, textureWidth, -textureHeight, textureWidth, textureHeight);
        }
        for (int i = 0; i < 6; i++) {
            int column = i % 3;
            int row = 1 - i / 3;
            graphics.drawString(font, CubeView.FACE_NAMES[i], x0 + column * face + 3, y0 + row * face + 3, 0xFFFFFF00, true);
        }
        int mirrorWidth = CubeTextures.MIRROR_ALBEDO.width();
        int mirrorHeight = CubeTextures.MIRROR_ALBEDO.height();
        if (MirrorView.active() && mirrorWidth > 1) {
            int w = face * 3 / 2;
            int h = Math.max(1, w * mirrorHeight / mirrorWidth);
            int x1 = x0 + face * 3 + 8;
            graphics.fill(x1 - 2, y0 - 2, x1 + w + 2, y0 + h + 2, 0xC0101010);
            graphics.blit(CubeTextures.MIRROR_ALBEDO_ID, x1, y0, w, h, 0.0F, mirrorHeight, mirrorWidth, -mirrorHeight, mirrorWidth, mirrorHeight);
            graphics.drawString(font, "mirror", x1 + 3, y0 + 3, 0xFFFFFF00, true);
        }
        int y = y0 + face * 2 + 6;
        for (String line : statusLines()) {
            graphics.drawString(font, line, x0, y, ChatFormatting.WHITE.getColor(), true);
            y += font.lineHeight + 1;
        }
    }
}
