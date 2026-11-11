package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferUploader;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

/**
 * The GL state the mod's drawing changes, read from GL before drawing and put back afterwards, in GL and in
 * Minecraft's record alike (see {@link Gl}). From capture to restore texture unit 0 is the active one.
 */
record GlState(int drawFramebuffer, int readFramebuffer, int[] viewport, int[] scissorBox, int program, int activeTexture,
               int texture0, boolean depthTest, boolean cull, boolean blend, boolean scissor, int depthFunc, boolean depthMask,
               boolean[] colorMask) {
    static GlState capture() {
        int[] viewport = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        int[] scissorBox = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissorBox);
        int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        int texture0 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL13C.glActiveTexture(activeTexture);
        Gl.checkRecord(activeTexture, texture0);
        Gl.activeTexture(GL13C.GL_TEXTURE0);
        byte[] mask = new byte[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var buffer = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, buffer);
            buffer.get(mask);
        }
        return new GlState(
                GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                viewport, scissorBox,
                GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),
                activeTexture, texture0,
                GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
                GL11C.glIsEnabled(GL11C.GL_BLEND),
                GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
                GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC),
                GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                new boolean[]{mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0});
    }

    void restore() {
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();
        GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        // Whatever was bound may be gone by now: entity renderers drawn in between can delete textures
        Gl.useProgram(this.program != 0 && GL20C.glIsProgram(this.program) ? this.program : 0);
        Gl.activeTexture(GL13C.GL_TEXTURE0);
        Gl.bindTextureIfAlive(this.texture0);
        Gl.activeTexture(this.activeTexture);
        Gl.bindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, this.drawFramebuffer != 0 && GL30C.glIsFramebuffer(this.drawFramebuffer) ? this.drawFramebuffer : 0);
        Gl.bindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, this.readFramebuffer != 0 && GL30C.glIsFramebuffer(this.readFramebuffer) ? this.readFramebuffer : 0);
        GlStateManager._viewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
        GlStateManager._scissorBox(this.scissorBox[0], this.scissorBox[1], this.scissorBox[2], this.scissorBox[3]);
        if (this.scissor) GlStateManager._enableScissorTest(); else GlStateManager._disableScissorTest();
        if (this.depthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
        if (this.cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
        if (this.blend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
        GlStateManager._depthFunc(this.depthFunc);
        GlStateManager._depthMask(this.depthMask);
        GlStateManager._colorMask(this.colorMask[0], this.colorMask[1], this.colorMask[2], this.colorMask[3]);
    }
}
