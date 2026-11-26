package com.rinkynooble.shaderscompanion.verify;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.FarTextures;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.FloatBuffer;

/**
 * Dev check (3.0.0-dev7, the user's dev6 test): at the debug view overlay's own moment, reads the screen's depth and the
 * far layer's depth and writes a mask (red where the screen's depth reads sky, green where the far layer holds a
 * surface), with the depth state that decides what reads as sky.
 */
final class LateDepthProbe {
    private LateDepthProbe() {
    }

    /** The scissor state just before the level renders this frame (set from DevRun's frame-start hook). */
    static volatile String frameStartState = "not read";

    static void readFrameStart() {
        int[] box = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, box);
        frameStartState = "scissor test " + GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST) + ", box " + java.util.Arrays.toString(box);
    }

    static void run(Minecraft mc, String name) {
        RenderTarget main = mc.getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        FloatBuffer scene = MemoryUtil.memAllocFloat(w * h);
        FloatBuffer far = MemoryUtil.memAllocFloat(w * h * 2);
        int readBefore = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int drawBefore = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int packBuffer = GL11C.glGetInteger(GL30C.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 4);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GL11C.glReadPixels(0, 0, w, h, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, scene);
            int depthTexture = main.getDepthTextureId();
            int unitBefore = GL11C.glGetInteger(GL30C.GL_ACTIVE_TEXTURE);
            GL30C.glActiveTexture(GL30C.GL_TEXTURE0);
            int boundBefore = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTexture);
            int format = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
            int depthBits = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_TEXTURE_DEPTH_SIZE);
            int texW = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
            int texH = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
            int attached = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                    GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, FarTextures.depth());
            GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RG, GL11C.GL_FLOAT, far);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, boundBefore);
            GL30C.glActiveTexture(unitBefore);
            boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
            int[] box = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, box);
            int[] viewport = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, depth probe {}: scissor test {}, box {}, viewport {}; at the frame's start: {}",
                    name, scissor, java.util.Arrays.toString(box), java.util.Arrays.toString(viewport), frameStartState);
            boolean clamp = GL11C.glIsEnabled(org.lwjgl.opengl.GL32C.GL_DEPTH_CLAMP);
            int clipDepth = GL11C.glGetInteger(GL45C.GL_CLIP_DEPTH_MODE);
            Matrix4f p = new Matrix4f(RenderSystem.getProjectionMatrix());
            // A perspective matrix's planes: n = m32 / (m22 - 1), f = m32 / (m22 + 1)
            float near = p.m32() / (p.m22() - 1.0F);
            float farPlane = p.m32() / (p.m22() + 1.0F);
            NativeImage image = new NativeImage(w, h, false);
            int sky = 0;
            int farSurface = 0;
            int drawn = 0;
            int nearOne = 0;
            float maxBelowOne = 0.0F;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float d = scene.get(i);
                    boolean isSky = d >= 1.0F;
                    float fd = far.get(i * 2 + 1) < 1.0F ? far.get(i * 2 + 1) : far.get(i * 2);
                    boolean hasFar = fd < 1.0F;
                    if (isSky) {
                        sky++;
                    } else {
                        maxBelowOne = Math.max(maxBelowOne, d);
                        if (d > 0.99999F) {
                            nearOne++;
                        }
                    }
                    if (hasFar) {
                        farSurface++;
                    }
                    if (isSky && hasFar) {
                        drawn++;
                    }
                    int r = isSky ? 255 : 0;
                    int g = hasFar ? 255 : 0;
                    int grey = isSky || hasFar ? 0 : 60;
                    image.setPixelRGBA(x, h - 1 - y, 0xFF000000 | (grey << 16) | ((g | grey) << 8) | (r | grey));
                }
            }
            File dir = new File(mc.gameDirectory, "screenshots");
            dir.mkdirs();
            image.writeToFile(new File(dir, name));
            image.close();
            ShadersCompanion.LOGGER.info("Shaders Companion dev run, depth probe {}: {} x {}, the screen's depth texture {} (attached {}, format 0x{}, {} bits, {} x {}); "
                            + "depth clamp {}, clip depth mode 0x{}, projection planes {} to {}; sky {} px ({}%), far surface {} px, overlay {} px ({}%), "
                            + "terrain depth over 0.99999 {} px, the largest below 1 {}",
                    name, w, h, depthTexture, attached, Integer.toHexString(format), depthBits, texW, texH, clamp, Integer.toHexString(clipDepth), near, farPlane,
                    sky, String.format("%.2f", 100.0 * sky / (w * h)), farSurface, drawn, String.format("%.2f", 100.0 * drawn / (w * h)), nearOne, maxBelowOne);
        } catch (Exception e) {
            ShadersCompanion.LOGGER.error("Shaders Companion dev run: depth probe failed", e);
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readBefore);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawBefore);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, packBuffer);
            MemoryUtil.memFree(scene);
            MemoryUtil.memFree(far);
        }
    }
}
