package com.rinkynooble.shaderscompanion.client;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.nio.FloatBuffer;
import java.util.Arrays;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

/**
 * Where rain falls to, for shader packs (API.md, "Rain cover"): Minecraft's {@code MOTION_BLOCKING} heightmap, the one
 * the game stops rain and its splashes at (glass and other see-through blocks included), for a {@link #SIZE} x
 * {@link #SIZE} block window around the camera. A column's texel is its block coordinates modulo SIZE, so the window
 * follows the camera without moving data; each texel carries a tag of its column's SIZE-block tile, so a pack can tell
 * a column from the one SIZE blocks away that last used the texel. Chunks entering the window are read at once, and
 * every chunk again in turn (the whole window every {@link #CHUNKS} frames) for blocks placed or broken; a chunk is
 * uploaded only when it changed.
 */
public final class RainCover {
    /** Window size in blocks. */
    static final int SIZE = 512;
    static final int PROTOCOL = 1;
    /** A texel whose column isn't loaded. */
    static final float UNKNOWN = -1.0F;
    /** Added to heights so they are positive: heights from -2048 to 2047 fit the 12 bits. */
    private static final int HEIGHT_OFFSET = 2048;
    private static final int CHUNKS = SIZE / 16;
    private static final int SLOTS = CHUNKS * CHUNKS;
    /** Chunks re-read each frame for changes. */
    private static final int REFRESH_PER_FRAME = CHUNKS;
    /** Chunks newly in the window read each frame, at most (a teleport refills the window over a few frames). */
    private static final int ENTERING_PER_FRAME = 128;
    private static final long NO_CHUNK = Long.MIN_VALUE;

    private static final TextureDemand demand = new TextureDemand(CompanionTexture.RAIN);
    private static final float[] texels = new float[SIZE * SIZE];
    /** The chunk each 16 x 16 slot of the texture holds. */
    private static final long[] slotChunk = new long[SLOTS];
    private static FloatBuffer upload;
    private static ClientLevel level;
    private static int cursor;
    private static boolean failed;
    /** Chunks uploaded in the last frame (for the status line). */
    static int uploaded;
    static String status = "not started";

    private RainCover() {
    }

    /** Called from the frame hook before the world is rendered. */
    public static void update(Camera camera) {
        if (failed) {
            return;
        }
        try {
            updateUnguarded(Minecraft.getInstance(), camera);
        } catch (Throwable t) {
            failed = true;
            status = "stopped after an error: " + t;
            ShadersCompanion.LOGGER.error("Shaders Companion stopped writing the rain cover after an error", t);
        }
    }

    /** Lets the world go (it is unloading); the next frame starts the window over. */
    public static void forgetWorld() {
        level = null;
    }

    private static void updateUnguarded(Minecraft mc, Camera camera) {
        uploaded = 0;
        if (mc.level == null || !Config.ENABLED.get() || !IrisApi.getInstance().isShaderPackInUse()) {
            demand.reset();
            unlink();
            status = mc.level == null ? "no world loaded" : !Config.ENABLED.get() ? "turned off" : "no shader pack in use";
            return;
        }
        if (!demand.update()) {
            status = "the shader pack doesn't read it";
            return;
        }
        int[] pixelBuffers = null;
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            Gl.bindTexture(CubeTextures.RAIN_COVER.glId());
            pixelBuffers = CompanionTexture.preparePixelTransfer();
            if (level != mc.level) {
                start(mc.level);
            }
            int cameraChunkX = Mth.floor(camera.getPosition().x) >> 4;
            int cameraChunkZ = Mth.floor(camera.getPosition().z) >> 4;
            int minX = cameraChunkX - CHUNKS / 2;
            int minZ = cameraChunkZ - CHUNKS / 2;
            int entering = 0;
            for (int slot = 0; slot < SLOTS && entering < ENTERING_PER_FRAME; slot++) {
                int chunkX = minX + Math.floorMod(slot % CHUNKS - minX, CHUNKS);
                int chunkZ = minZ + Math.floorMod(slot / CHUNKS - minZ, CHUNKS);
                if (slotChunk[slot] != ChunkPos.asLong(chunkX, chunkZ)) {
                    read(slot, chunkX, chunkZ);
                    entering++;
                }
            }
            for (int i = 0; i < REFRESH_PER_FRAME; i++) {
                int slot = cursor;
                cursor = (cursor + 1) % SLOTS;
                read(slot, minX + Math.floorMod(slot % CHUNKS - minX, CHUNKS), minZ + Math.floorMod(slot / CHUNKS - minZ, CHUNKS));
            }
            status = String.format("%d x %d blocks around the camera, %d chunks uploaded last frame", SIZE, SIZE, uploaded);
        } finally {
            if (pixelBuffers != null) {
                CompanionTexture.restorePixelBuffers(pixelBuffers);
            }
            Gl.bindTextureIfAlive(previous);
        }
    }

    /** A new world: every texel unknown until its chunk is read, then the header that marks the texture as usable. */
    private static void start(ClientLevel world) {
        level = world;
        Arrays.fill(texels, UNKNOWN);
        Arrays.fill(slotChunk, NO_CHUNK);
        // The texture is bound by the caller; allocate() binds and puts it back itself
        CubeTextures.RAIN_COVER.allocate(SIZE, SIZE + 1);
        Gl.bindTexture(CubeTextures.RAIN_COVER.glId());
        FloatBuffer all = MemoryUtil.memAllocFloat(SIZE * SIZE);
        try {
            all.put(texels).flip();
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL11C.GL_RED, GL11C.GL_FLOAT, all);
        } finally {
            MemoryUtil.memFree(all);
        }
        writeHeader(CubeTextures.MAGIC);
    }

    /** Marks the texture unusable (the pack falls back), so a later start fills it again. */
    private static void unlink() {
        if (level == null) {
            return;
        }
        level = null;
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        Gl.bindTexture(CubeTextures.RAIN_COVER.glId());
        int[] pixelBuffers = CompanionTexture.preparePixelTransfer();
        try {
            writeHeader(0.0F);
        } finally {
            CompanionTexture.restorePixelBuffers(pixelBuffers);
            Gl.bindTextureIfAlive(previous);
        }
    }

    /** The row above the window: magic, protocol and window size (the texture must be bound). */
    private static void writeHeader(float magic) {
        FloatBuffer header = MemoryUtil.memAllocFloat(3);
        try {
            header.put(magic).put(PROTOCOL).put(SIZE).flip();
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, SIZE, 3, 1, GL11C.GL_RED, GL11C.GL_FLOAT, header);
        } finally {
            MemoryUtil.memFree(header);
        }
    }

    /** Reads a chunk's rain heights into its slot and uploads the slot if anything changed (the texture is bound). */
    private static void read(int slot, int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        boolean changed = slotChunk[slot] != key;
        slotChunk[slot] = key;
        LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
        Heightmap heights = chunk == null ? null : chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        int tag = (chunkX >> 5 & 15) | (chunkZ >> 5 & 15) << 4;
        int x0 = (chunkX & (CHUNKS - 1)) * 16;
        int z0 = (chunkZ & (CHUNKS - 1)) * 16;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                float value = UNKNOWN;
                if (heights != null) {
                    // The lowest free height above the column's highest block that stops rain
                    int height = Mth.clamp(heights.getFirstAvailable(x, z) + HEIGHT_OFFSET, 0, 4095);
                    value = height | tag << 12;
                }
                int i = (z0 + z) * SIZE + x0 + x;
                if (texels[i] != value) {
                    texels[i] = value;
                    changed = true;
                }
            }
        }
        if (!changed) {
            return;
        }
        if (upload == null) {
            upload = MemoryUtil.memAllocFloat(256);
        }
        upload.clear();
        for (int z = 0; z < 16; z++) {
            upload.put(texels, (z0 + z) * SIZE + x0, 16);
        }
        upload.flip();
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, x0, z0, 16, 16, GL11C.GL_RED, GL11C.GL_FLOAT, upload);
        uploaded++;
    }
}
