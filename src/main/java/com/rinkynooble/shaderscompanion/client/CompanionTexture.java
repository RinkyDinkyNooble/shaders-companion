package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
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
    /** The lattice's textures ({@link Lattice}). */
    static final int LATTICE = 5;
    /** The lattice's openness field ({@link LatticeField}). */
    static final int LATTICE_FIELD = 6;
    /** mirror_info_2 to _4, the mirror info texture under the names that ask for that many planes ({@link #mirrorPlanesGroup}). */
    private static final int MIRROR_PLANES_2 = 7;
    /** The far terrain's layers ({@link FarTextures}). */
    static final int FAR = MIRROR_PLANES_2 + MirrorView.MAX_PLANES - 1;
    /** The far terrain's info texture as {@code far_info} (the layers jittered for the pack's TAA). */
    static final int FAR_INFO = FAR + 1;
    /** The same texture as {@code far_info_still} (the layers not jittered). */
    static final int FAR_INFO_STILL = FAR + 2;
    /** The far terrain's shadow map ({@code far_shadow}). */
    static final int FAR_SHADOW = FAR + 3;

    /** Per group: how often a texture's id was asked for from outside the mod (render thread only). */
    private static final long[] requests = new long[FAR_SHADOW + 1];

    private final int group;
    private final int internalFormat;
    private final int format;
    private final int type;
    private final int filter;
    /** A depth texture read through a shadow sampler: compared (less or equal) against the lookup's reference. */
    private final boolean compare;
    private int width;
    private int height;

    CompanionTexture(int group, int internalFormat, int format, int type, int filter) {
        this(group, internalFormat, format, type, filter, false);
    }

    CompanionTexture(int group, int internalFormat, int format, int type, int filter, boolean compare) {
        this.group = group;
        this.internalFormat = internalFormat;
        this.format = format;
        this.type = type;
        this.filter = filter;
        this.compare = compare;
    }

    @Override
    public void load(ResourceManager resourceManager) {
    }

    @Override
    public int getId() {
        requests[this.group]++;
        return super.getId();
    }

    /** Counts a request for a group's texture that isn't bound through the texture manager (the lattice's 3D textures). */
    static void countRequest(int group) {
        requests[group]++;
    }

    /** The group of the mirror info texture's name that asks for this many planes (2 or more). */
    static int mirrorPlanesGroup(int planes) {
        return MIRROR_PLANES_2 + planes - 2;
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
        this.writeRows(data, texels, 1);
    }

    /** As {@link #writeRow} for the first {@code rows} rows, row after row. */
    void writeRows(FloatBuffer data, int texels, int rows) {
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        Gl.bindTexture(this.glId());
        int[] pixelBuffers = preparePixelTransfer();
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, texels, rows, GL11C.GL_RGBA, GL11C.GL_FLOAT, data);
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
        if (this.compare) {
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL30C.GL_COMPARE_REF_TO_TEXTURE);
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_FUNC, GL11C.GL_LEQUAL);
        }
        Gl.bindTextureIfAlive(previous);
        this.width = width;
        this.height = height;
    }

    /**
     * Another name for one of the mod's textures: binds the same GL texture and counts its own group's requests, so the
     * mod can tell which name a pack binds. Never owns the texture.
     */
    static final class Alias extends AbstractTexture {
        private final CompanionTexture target;
        private final int group;

        Alias(CompanionTexture target, int group) {
            this.target = target;
            this.group = group;
        }

        @Override
        public void load(ResourceManager resourceManager) {
        }

        @Override
        public int getId() {
            requests[this.group]++;
            return this.target.glId();
        }

        @Override
        public void releaseId() {
        }
    }
}
