package com.rinkynooble.shaderscompanion.client;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * A framebuffer a view is drawn into: the view's textures as colour attachments, in the order of the programs' outputs
 * ({@link ViewProgram#OUTPUTS}; outputs past the last texture go nowhere), and a depth buffer (unless it only receives
 * copies). Created on first use.
 */
final class ViewTarget {
    private final String name;
    private final CompanionTexture[] colours;
    private final boolean depth;
    private final int[] drawBuffers = new int[ViewProgram.OUTPUTS.length];
    private int framebuffer;
    private int depthBuffer;
    private int width;
    private int height;

    ViewTarget(String name, CompanionTexture... colours) {
        this(name, true, colours);
    }

    ViewTarget(String name, boolean depth, CompanionTexture... colours) {
        this.name = name;
        this.depth = depth;
        this.colours = colours;
    }

    /** Gives the textures and the depth buffer this size; true when they were (re)allocated. */
    boolean ensureSize(int newWidth, int newHeight) {
        if (this.framebuffer == 0) {
            this.framebuffer = GL30C.glGenFramebuffers();
            this.depthBuffer = this.depth ? GL30C.glGenRenderbuffers() : 0;
        }
        if (newWidth == this.width && newHeight == this.height) {
            return false;
        }
        for (CompanionTexture texture : this.colours) {
            texture.allocate(newWidth, newHeight);
        }
        int previousFramebuffer = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        Gl.bindFramebuffer(GL30C.GL_FRAMEBUFFER, this.framebuffer);
        if (this.depth) {
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, this.depthBuffer);
            GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_DEPTH_COMPONENT24, newWidth, newHeight);
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0);
            GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_RENDERBUFFER, this.depthBuffer);
        }
        for (int i = 0; i < this.drawBuffers.length; i++) {
            if (i < this.colours.length) {
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i, GL11C.GL_TEXTURE_2D, this.colours[i].glId(), 0);
                this.drawBuffers[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
            } else {
                this.drawBuffers[i] = GL11C.GL_NONE;
            }
        }
        GL20C.glDrawBuffers(this.drawBuffers);
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
     * Copies a rectangle of each colour texture to the same texture of another target of this size with the same
     * formats (glBlitFramebuffer, one attachment at a time), then binds this target again.
     */
    void copyTo(ViewTarget other, int x, int y, int copyWidth, int copyHeight) {
        Gl.bindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, this.framebuffer);
        Gl.bindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, other.framebuffer);
        for (int i = 0; i < Math.min(this.colours.length, other.colours.length); i++) {
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0 + i);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0 + i);
            GL30C.glBlitFramebuffer(x, y, x + copyWidth, y + copyHeight, x, y, x + copyWidth, y + copyHeight, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
        }
        GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
        GL20C.glDrawBuffers(other.drawBuffers);
        this.bind();
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
        if (this.depth) {
            GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[]{1.0F});
        }
    }
}
