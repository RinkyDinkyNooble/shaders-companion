package com.rinkynooble.shaderscompanion.client;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;

/**
 * An upload or a read back made with {@link CompanionTexture#preparePixelTransfer}'s state, for try-with-resources.
 * Opening it reads the texture binding it puts back (and binds a texture, for {@link #bind2D}), then prepares the
 * transfer; closing it puts back the unpack alignment if {@link #unpackAlignment} changed it, then the pixel buffers,
 * then the texture binding: a 2D one through {@link Gl#bindTextureIfAlive}, a 3D one (not in Minecraft's record) with
 * glBindTexture.
 */
final class PixelTransfer implements AutoCloseable {
    /** The unpack alignment preparePixelTransfer sets, which {@link #close} puts back. */
    private static final int ALIGNMENT = 4;

    /** GL_TEXTURE_2D or GL_TEXTURE_3D, the binding put back on close; 0 for none. */
    private final int target;
    private final int previous;
    private final int[] buffers;
    private boolean alignmentChanged;

    private PixelTransfer(int target, int previous) {
        this.target = target;
        this.previous = previous;
        this.buffers = CompanionTexture.preparePixelTransfer();
    }

    /** Binds a 2D texture for the transfer; the 2D texture bound before it is bound again on close. */
    static PixelTransfer bind2D(int id) {
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        Gl.bindTexture(id);
        return new PixelTransfer(GL11C.GL_TEXTURE_2D, previous);
    }

    /** Keeps the 2D binding to put back on close; the caller binds the textures it reads or writes. */
    static PixelTransfer save2D() {
        return new PixelTransfer(GL11C.GL_TEXTURE_2D, GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D));
    }

    /** As {@link #save2D} for the 3D binding. */
    static PixelTransfer save3D() {
        return new PixelTransfer(GL12C.GL_TEXTURE_3D, GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D));
    }

    /** The pixel transfer state alone: the caller binds what it needs, and its own caller puts the binding back. */
    static PixelTransfer stateOnly() {
        return new PixelTransfer(0, 0);
    }

    /** Sets the unpack alignment for the rest of the transfer; closing puts back preparePixelTransfer's 4. */
    void unpackAlignment(int alignment) {
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, alignment);
        this.alignmentChanged = true;
    }

    @Override
    public void close() {
        if (this.alignmentChanged) {
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, ALIGNMENT);
        }
        CompanionTexture.restorePixelBuffers(this.buffers);
        if (this.target == GL11C.GL_TEXTURE_2D) {
            Gl.bindTextureIfAlive(this.previous);
        } else if (this.target == GL12C.GL_TEXTURE_3D) {
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, this.previous);
        }
    }
}
