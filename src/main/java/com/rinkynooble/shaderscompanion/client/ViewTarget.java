package com.rinkynooble.shaderscompanion.client;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * A framebuffer a view is drawn into: the view's textures as colour attachments, in the order of the programs' outputs
 * ({@link ViewProgram#OUTPUTS}; outputs past the last texture go nowhere), and a depth buffer. Created on first use.
 */
final class ViewTarget {
    private final String name;
    private final CompanionTexture[] colours;
    private int framebuffer;
    private int depthBuffer;
    private int width;
    private int height;

    ViewTarget(String name, CompanionTexture... colours) {
        this.name = name;
        this.colours = colours;
    }

    /** Gives the textures and the depth buffer this size; true when they were (re)allocated. */
    boolean ensureSize(int newWidth, int newHeight) {
        if (this.framebuffer == 0) {
            this.framebuffer = GL30C.glGenFramebuffers();
            this.depthBuffer = GL30C.glGenRenderbuffers();
        }
        if (newWidth == this.width && newHeight == this.height) {
            return false;
        }
        for (CompanionTexture texture : this.colours) {
            texture.allocate(newWidth, newHeight);
        }
        int previousFramebuffer = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        Gl.bindFramebuffer(GL30C.GL_FRAMEBUFFER, this.framebuffer);
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, this.depthBuffer);
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_DEPTH_COMPONENT24, newWidth, newHeight);
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0);
        int[] drawBuffers = new int[ViewProgram.OUTPUTS.length];
        for (int i = 0; i < drawBuffers.length; i++) {
            if (i < this.colours.length) {
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i, GL11C.GL_TEXTURE_2D, this.colours[i].glId(), 0);
                drawBuffers[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
            } else {
                drawBuffers[i] = GL11C.GL_NONE;
            }
        }
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_RENDERBUFFER, this.depthBuffer);
        GL20C.glDrawBuffers(drawBuffers);
        int complete = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        Gl.bindFramebuffer(GL30C.GL_FRAMEBUFFER, previousFramebuffer);
        if (complete != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(this.name + " framebuffer incomplete: 0x" + Integer.toHexString(complete));
        }
        this.width = newWidth;
        this.height = newHeight;
        return true;
    }

    void bind() {
        Gl.bindFramebuffer(GL30C.GL_FRAMEBUFFER, this.framebuffer);
    }

    /**
     * Clears the scissored part of the bound target: every texture to 0 but the distance, which gets {@code distance},
     * and the depth to the far plane.
     */
    void clear(float distance) {
        float[] zero = {0.0F, 0.0F, 0.0F, 0.0F};
        for (int i = 0; i < this.colours.length; i++) {
            GL30C.glClearBufferfv(GL11C.GL_COLOR, i, i == ViewProgram.DISTANCE_OUTPUT ? new float[]{distance, 0.0F, 0.0F, 0.0F} : zero);
        }
        GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[]{1.0F});
    }
}
