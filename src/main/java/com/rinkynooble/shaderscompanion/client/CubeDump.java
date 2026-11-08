package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

/** Saves the cube atlas as PNG files in the screenshots folder, for checking the view without a shader pack reading it. */
public final class CubeDump {
    private CubeDump() {
    }

    /**
     * Writes preview, albedo and distance images, and the mirror view's albedo while it is drawn; returns the file name
     * prefix, or null when nothing has been drawn yet.
     */
    public static String dump() throws IOException {
        int width = CubeTextures.PREVIEW.width();
        int height = CubeTextures.PREVIEW.height();
        if (width == 0 || CubeView.frameCounter == 0) {
            return null;
        }
        File folder = new File(Minecraft.getInstance().gameDirectory, "screenshots");
        folder.mkdirs();
        String prefix = "shaderscompanion-" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int[] pixelBuffers = CompanionTexture.preparePixelTransfer();
        try {
            saveColor(CubeTextures.PREVIEW, new File(folder, prefix + "-preview.png"));
            saveColor(CubeTextures.ALBEDO, new File(folder, prefix + "-albedo.png"));
            saveDistance(new File(folder, prefix + "-distance.png"));
            if (MirrorView.active() && CubeTextures.MIRROR_ALBEDO.width() > 1) {
                saveColor(CubeTextures.MIRROR_ALBEDO, new File(folder, prefix + "-mirror.png"));
            }
        } finally {
            CompanionTexture.restorePixelBuffers(pixelBuffers);
            GlStateManager._bindTexture(previous);
        }
        ShadersCompanion.LOGGER.info("Shaders Companion: cube saved as screenshots/{}-*.png", prefix);
        return prefix;
    }

    /** The stored values of one atlas texel (x, y from the bottom-left), decoded as API.md describes. */
    public static String describeTexel(int x, int y) {
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int[] pixelBuffers = CompanionTexture.preparePixelTransfer();
        try {
            int width = CubeTextures.DATA.width();
            int height = CubeTextures.DATA.height();
            java.nio.ShortBuffer data = MemoryUtil.memAllocShort(width * height * 4);
            FloatBuffer distance = MemoryUtil.memAllocFloat(width * height);
            java.nio.ByteBuffer albedo = MemoryUtil.memAlloc(width * height * 4);
            try {
                GlStateManager._bindTexture(CubeTextures.DATA.glId());
                GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_SHORT, data);
                GlStateManager._bindTexture(CubeTextures.DISTANCE.glId());
                GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RED, GL11C.GL_FLOAT, distance);
                GlStateManager._bindTexture(CubeTextures.ALBEDO.glId());
                GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, albedo);
                int i = y * width + x;
                int normal = data.get(i * 4) & 0xFFFF;
                int light = data.get(i * 4 + 1) & 0xFFFF;
                int id = (data.get(i * 4 + 2) & 0xFFFF) - 1;
                int emission = data.get(i * 4 + 3) & 0xFFFF;
                return String.format("texel %d,%d: albedo %d %d %d %d, normal %d/%d, block light %d/255, sky light %d/255, id %d, light value %.2f, distance %.2f",
                        x, y, albedo.get(i * 4) & 255, albedo.get(i * 4 + 1) & 255, albedo.get(i * 4 + 2) & 255, albedo.get(i * 4 + 3) & 255,
                        normal >> 8, normal & 255, light >> 8, light & 255, id, emission / 65535.0, distance.get(i));
            } finally {
                MemoryUtil.memFree(data);
                MemoryUtil.memFree(distance);
                MemoryUtil.memFree(albedo);
            }
        } finally {
            CompanionTexture.restorePixelBuffers(pixelBuffers);
            GlStateManager._bindTexture(previous);
        }
    }

    private static void saveColor(CompanionTexture texture, File file) throws IOException {
        GlStateManager._bindTexture(texture.glId());
        try (NativeImage image = new NativeImage(texture.width(), texture.height(), false)) {
            image.downloadTexture(0, false);
            image.flipY();
            image.writeToFile(file);
        }
    }

    /** Distance as grey: black is empty (sky), white is at the far limit, with a square-root curve so nearby detail shows. */
    private static void saveDistance(File file) throws IOException {
        CompanionTexture texture = CubeTextures.DISTANCE;
        int width = texture.width();
        int height = texture.height();
        FloatBuffer data = MemoryUtil.memAllocFloat(width * height);
        try (NativeImage image = new NativeImage(width, height, false)) {
            GlStateManager._bindTexture(texture.glId());
            GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RED, GL11C.GL_FLOAT, data);
            float limit = Math.max(1.0F, CubeView.lastMaxDistance());
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float distance = data.get(y * width + x);
                    int grey = distance <= 0.0F ? 0 : 40 + (int) (215.0F * Math.min(1.0F, (float) Math.sqrt(distance / limit)));
                    image.setPixelRGBA(x, height - 1 - y, 0xFF000000 | grey << 16 | grey << 8 | grey);
                }
            }
            image.writeToFile(file);
        } finally {
            MemoryUtil.memFree(data);
        }
    }
}
