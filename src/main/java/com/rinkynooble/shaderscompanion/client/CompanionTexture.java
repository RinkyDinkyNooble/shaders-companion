package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.system.MemoryUtil;

/**
 * A GL texture owned by this mod, registered in Minecraft's texture manager so shader packs can bind it by name.
 * Oculus asks the texture manager for a pack's custom textures on every bind and reads {@link #getId()}; a tracked
 * texture counts those requests, so the mod can tell whether the active pack reads the cube or the mirror view. The
 * mod's own code uses {@link #glId()}, which isn't counted.
 */
final class CompanionTexture extends AbstractTexture {
    static final int UNTRACKED = 0;
    static final int CUBE = 1;
    static final int MIRROR = 2;
    /** The mirror view's copy of what lies behind water and glass ({@link CubeTextures#MIRROR_BEHIND_ALBEDO}). */
    static final int MIRROR_BEHIND = 3;
    /** Where rain falls to ({@link RainCover}). */
    static final int RAIN = 4;

    /** Per group: how often a texture's id was asked for from outside the mod (render thread only). */
    private static final long[] requests = new long[5];

    private final int group;
    private final int internalFormat;
    private final int format;
    private final int type;
    private final int filter;
    private int width;
    private int height;

    CompanionTexture(int group, int internalFormat, int format, int type, int filter) {
        this.group = group;
        this.internalFormat = internalFormat;
        this.format = format;
        this.type = type;
        this.filter = filter;
    }

    @Override
    public void load(ResourceManager resourceManager) {
    }

    @Override
    public int getId() {
        requests[this.group]++;
        return super.getId();
    }

    /** How often the textures of a group were asked for from outside the mod so far. */
    static long requests(int group) {
        return requests[group];
    }

    /** The GL texture name, for the mod's own use (not counted as a request). */
    int glId() {
        return super.getId();
    }

    @Override
    public void releaseId() {
        super.releaseId();
        this.width = 0;
        this.height = 0;
    }

    int width() {
        return this.width;
    }

    int height() {
        return this.height;
    }

    /**
     * Sets the pixel transfer state for tightly packed client memory. Minecraft leaves row length and skip values
     * from its own texture uploads (font glyphs, for one), and a transfer made with them reads or writes past the
     * buffer. Returns the bound pixel unpack buffer, which is unbound here and must be put back with
     * {@link #restorePixelBuffers}.
     */
    static int[] preparePixelTransfer() {
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._pixelStore(GL11C.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11C.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11C.GL_PACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11C.GL_PACK_ALIGNMENT, 4);
        int[] buffers = {GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING), GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING)};
        if (buffers[0] != 0) {
            GlStateManager._glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        }
        if (buffers[1] != 0) {
            GlStateManager._glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
        }
        return buffers;
    }

    static void restorePixelBuffers(int[] buffers) {
        if (buffers[0] != 0) {
            GlStateManager._glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, buffers[0]);
        }
        if (buffers[1] != 0) {
            GlStateManager._glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, buffers[1]);
        }
    }

    /**
     * Writes the first row's first {@code texels} RGBA float texels (an info texture's contents) and puts back the
     * texture that was bound: this mod's textures never stay bound.
     */
    void writeRow(FloatBuffer data, int texels) {
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        Gl.bindTexture(this.glId());
        int[] pixelBuffers = preparePixelTransfer();
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, texels, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, data);
        restorePixelBuffers(pixelBuffers);
        Gl.bindTextureIfAlive(previous);
    }

    /** Gives the texture storage of this size; keeps its GL name, so framebuffer attachments stay valid. */
    void allocate(int width, int height) {
        if (width == this.width && height == this.height) {
            return;
        }
        int id = this.glId();
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        Gl.bindTexture(id);
        int[] pixelBuffers = preparePixelTransfer();
        // Small textures (the info texture) start zero-filled, so nothing reads as a valid cube before the first frame
        ByteBuffer zeros = width * height <= 4096 ? MemoryUtil.memCalloc(width * height * 16) : null;
        try {
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, this.internalFormat, width, height, 0, this.format, this.type, zeros);
        } finally {
            if (zeros != null) {
                MemoryUtil.memFree(zeros);
            }
        }
        restorePixelBuffers(pixelBuffers);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, this.filter);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, this.filter);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        Gl.bindTextureIfAlive(previous);
        this.width = width;
        this.height = height;
    }
}
