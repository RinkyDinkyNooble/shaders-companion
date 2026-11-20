package com.rinkynooble.shaderscompanion.client;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.function.IntSupplier;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.system.MemoryUtil;

/**
 * The lattice (API.md, "Lattice"): a copy of the blocks around the camera for shader packs, built from the client's
 * world rather than from what is drawn. Per block: its shape in 4 x 4 x 4 cells (from the block's outline shape, so
 * modded blocks keep their own shape), the light engine's block and sky light, the block's own light emission and a
 * few flags. Two 3D textures cover a window of {@link #size} x {@link #HEIGHT} x {@link #size} blocks around the camera;
 * a block's texel is its position modulo the window, so the window follows the camera without moving data, and each
 * texel carries the parity of its window tile per axis, so a pack can tell a block from the one a window away that last
 * used the texel. Work is done per 16-block section, nearest the camera first, within a time budget per frame: when a
 * section enters the window, when a block in it changes, when its light changes or its chunk loads or unloads; one
 * section is read again each frame in turn and uploaded only if it changed, in case a change went unnoticed. Shapes,
 * emission and flags are worked out once per block state.
 */
public final class Lattice {
    static final int PROTOCOL = 1;
    static final int MAGIC = 4242;
    /** Window height in blocks; the width is the config's {@code latticeSize}. */
    static final int HEIGHT = 128;
    static final String CELLS = "lattice_cells";
    static final String SHAPE = "lattice_shape";

    // Cell bits
    private static final int WATER = 1 << 12;
    private static final int LAVA = 1 << 13;
    private static final int SEE_THROUGH = 1 << 14;
    private static final int NO_COLLISION = 1 << 15;
    private static final int STATE_BITS = 0x0FFFFF00;
    private static final int VALID = 1 << 31;
    /** Smallest overlap of a shape box with a cell, per axis, that fills the cell (so panes and carpets keep a cell). */
    private static final double CELL_OVERLAP = 1.0 / 64.0;
    private static final long NONE = Long.MIN_VALUE;

    private static final TextureDemand demand = new TextureDemand(CompanionTexture.LATTICE);
    private static final TextureDemand fieldDemand = new TextureDemand(CompanionTexture.LATTICE_FIELD);
    private static int cellsTexture;
    private static int shapeTexture;
    private static int size;
    private static int log2Size;
    private static int sectionsXZ;
    private static final int SECTIONS_Y = HEIGHT / 16;
    private static long[] slotSection;
    private static boolean[] slotDirty;
    private static long[] slotHash;
    /** Per slot: a hash of the shapes and the flags that matter to the openness field. */
    private static long[] slotShapeHash;
    /** Section offsets from the camera's section covering the window, nearest first (x, y, z per entry). */
    private static int[] offsets;
    private static int refreshCursor;
    private static ClientLevel level;
    private static boolean idle;
    private static boolean failed;
    private static int infoX = Integer.MIN_VALUE;
    private static int infoY;
    private static int infoZ;
    private static boolean infoField;
    private static int fieldCentreX;
    private static int fieldCentreY;
    private static int fieldCentreZ;

    // Per block state: shape cells, cell bits (emission and flags), whether worked out
    private static long[] stateShape = new long[0];
    private static int[] stateBits = new int[0];
    private static boolean[] stateKnown = new boolean[0];

    private static IntBuffer cellsUpload;
    private static IntBuffer shapeUpload;
    private static final int[] columnSky = new int[256];
    private static final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    // For the status line
    static String status = "not started";
    private static int builtLastFrame;
    private static int uploadedLastFrame;
    private static long nanosLastFrame;
    private static double nanosPerSection;
    private static int waiting;
    private static int filled;
    private static long statesKnown;
    // The fill after a start: wall time from the start, frames and processor time until every section is read
    private static long fillStart;
    private static int fillFrames;
    private static long fillNanos;

    private Lattice() {
    }

    /**
     * The texture a shader pack binds for {@code shaderscompanion:<location>} as a 3D texture (Oculus binds a mod's
     * textures as 2D; {@code LatticeTextureMixin} asks here first), or null for the mod's other textures. Each bind
     * counts as a request, so the lattice is kept only while the pack reads it.
     */
    public static IntSupplier textureForPack(String location) {
        if (CELLS.equals(location)) {
            return () -> {
                CompanionTexture.countRequest(CompanionTexture.LATTICE);
                return cellsTexture;
            };
        }
        if (SHAPE.equals(location)) {
            return () -> {
                CompanionTexture.countRequest(CompanionTexture.LATTICE);
                return shapeTexture;
            };
        }
        if (LatticeField.OPEN_A.equals(location)) {
            return () -> {
                CompanionTexture.countRequest(CompanionTexture.LATTICE_FIELD);
                CompanionTexture.countRequest(CompanionTexture.LATTICE);
                return LatticeField.openA();
            };
        }
        if (LatticeField.OPEN_B.equals(location)) {
            return () -> {
                CompanionTexture.countRequest(CompanionTexture.LATTICE_FIELD);
                CompanionTexture.countRequest(CompanionTexture.LATTICE);
                return LatticeField.openB();
            };
        }
        return null;
    }

    static int cellsTexture() {
        return cellsTexture;
    }

    static int shapeTexture() {
        return shapeTexture;
    }

    static int width() {
        return size;
    }

    static int windowMinX() {
        return infoX;
    }

    static int windowMinY() {
        return infoY;
    }

    static int windowMinZ() {
        return infoZ;
    }

    /**
     * A stamp of the shapes in a section and its 26 neighbours (the openness field bakes the section again when it
     * changes), or {@link Long#MIN_VALUE} while any of them inside the window isn't read yet. Neighbours outside the
     * window count as unchanging.
     */
    static long neighbourhoodStamp(int x, int y, int z, int cameraX, int cameraY, int cameraZ) {
        long stamp = 0x9E3779B97F4A7C15L;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    int nz = z + dz;
                    long hash = 0;
                    if (Math.abs(2 * (nx - cameraX) + 1) <= sectionsXZ && Math.abs(2 * (ny - cameraY) + 1) <= SECTIONS_Y
                            && Math.abs(2 * (nz - cameraZ) + 1) <= sectionsXZ) {
                        int slot = slot(nx, ny, nz);
                        if (slotSection[slot] != SectionPos.asLong(nx, ny, nz) || slotDirty[slot]) {
                            return Long.MIN_VALUE;
                        }
                        hash = slotShapeHash[slot];
                    }
                    stamp = (stamp ^ hash) * 0x100000001B3L + 0x632BE59BD9B4E019L;
                }
            }
        }
        return stamp == Long.MIN_VALUE ? stamp + 1 : stamp;
    }

    /** A block changed (render thread). */
    public static void markBlock(BlockPos blockPos) {
        markSection(blockPos.getX() >> 4, blockPos.getY() >> 4, blockPos.getZ() >> 4);
    }

    /** A section's blocks or light changed (render thread). */
    public static void markSection(int x, int y, int z) {
        if (slotDirty != null) {
            slotDirty[slot(x, y, z)] = true;
        }
    }

    /** A chunk loaded or unloaded: every section of its column (render thread). */
    public static void markChunk(int x, int z) {
        if (slotDirty == null) {
            return;
        }
        for (int y = 0; y < SECTIONS_Y; y++) {
            slotDirty[slot(x, y, z)] = true;
        }
    }

    /** Lets the world go (it is unloading); the next frame starts the window over. */
    public static void forgetWorld() {
        level = null;
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
            ShadersCompanion.LOGGER.error("Shaders Companion stopped building the lattice after an error", t);
        }
    }

    private static void updateUnguarded(Minecraft mc, Camera camera) {
        builtLastFrame = 0;
        uploadedLastFrame = 0;
        nanosLastFrame = 0;
        if (mc.level == null || !Config.ENABLED.get() || !Config.LATTICE.get() || !IrisApi.getInstance().isShaderPackInUse()) {
            demand.reset();
            unlink();
            status = mc.level == null ? "no world loaded" : !Config.ENABLED.get() || !Config.LATTICE.get() ? "turned off" : "no shader pack in use";
            return;
        }
        if (!demand.update()) {
            idle = true;
            status = "the shader pack doesn't read it";
            return;
        }
        long start = System.nanoTime();
        int blockX = Mth.floor(camera.getPosition().x);
        int blockY = Mth.floor(camera.getPosition().y);
        int blockZ = Mth.floor(camera.getPosition().z);
        int cameraX = blockX >> 4;
        int cameraY = blockY >> 4;
        int cameraZ = blockZ >> 4;
        int previous3d = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        int[] pixelBuffers = CompanionTexture.preparePixelTransfer();
        GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
        try {
            int wantedSize = Config.LATTICE_SIZE.get();
            if (level != mc.level || wantedSize != size) {
                start(mc.level, wantedSize);
            } else if (idle) {
                // Not kept while unread: read everything again
                Arrays.fill(slotDirty, true);
            }
            idle = false;

            long budget = Config.LATTICE_BUDGET.get() * 1000L;
            waiting = 0;
            filled = 0;
            for (int i = 0; i < offsets.length; i += 3) {
                int x = cameraX + offsets[i];
                int y = cameraY + offsets[i + 1];
                int z = cameraZ + offsets[i + 2];
                int slot = slot(x, y, z);
                if (slotSection[slot] == SectionPos.asLong(x, y, z) && !slotDirty[slot]) {
                    filled++;
                    continue;
                }
                if (System.nanoTime() - start > budget) {
                    waiting++;
                    continue;
                }
                build(slot, x, y, z, false);
                filled++;
            }
            // One section read again in turn, uploaded only if it changed
            if (System.nanoTime() - start <= budget) {
                refreshCursor = (refreshCursor + 3) % offsets.length;
                int x = cameraX + offsets[refreshCursor];
                int y = cameraY + offsets[refreshCursor + 1];
                int z = cameraZ + offsets[refreshCursor + 2];
                build(slot(x, y, z), x, y, z, true);
            }
            int minX = (cameraX - sectionsXZ / 2) * 16;
            int minY = (cameraY - SECTIONS_Y / 2) * 16;
            int minZ = (cameraZ - sectionsXZ / 2) * 16;
            boolean field = fieldDemand.update() && LatticeField.available();
            int centreX = LatticeField.centre(blockX);
            int centreY = LatticeField.centre(blockY);
            int centreZ = LatticeField.centre(blockZ);
            if (minX != infoX || minY != infoY || minZ != infoZ || field != infoField
                    || centreX != fieldCentreX || centreY != fieldCentreY || centreZ != fieldCentreZ) {
                infoField = field;
                fieldCentreX = centreX;
                fieldCentreY = centreY;
                fieldCentreZ = centreZ;
                writeInfo(MAGIC, minX, minY, minZ);
            }
            if (field) {
                LatticeField.update(centreX, centreY, centreZ, cameraX, cameraY, cameraZ);
            }
        } finally {
            CompanionTexture.restorePixelBuffers(pixelBuffers);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previous3d);
        }
        nanosLastFrame = System.nanoTime() - start;
        int sections = offsets.length / 3;
        if (fillStart != 0) {
            fillFrames++;
            fillNanos += nanosLastFrame;
            if (filled == sections) {
                ShadersCompanion.LOGGER.info("Shaders Companion: the lattice read its {} sections in {} ms over {} frames ({} ms of processor time, {} block states)",
                        sections, (System.nanoTime() - fillStart) / 1000000, fillFrames, String.format("%.1f", fillNanos / 1.0e6), statesKnown);
                fillStart = 0;
            }
        }
        status = String.format("%d x %d x %d blocks, %d of %d sections%s; built %d last frame (%.2f ms, %.0f us per section), %d uploaded; %d block states; %d MB",
                size, HEIGHT, size, filled, sections, waiting > 0 ? " (" + waiting + " waiting)" : "", builtLastFrame, nanosLastFrame / 1.0e6,
                nanosPerSection / 1.0e3, uploadedLastFrame, statesKnown, (long) size * HEIGHT * size * 12 / (1024 * 1024))
                + (infoField ? "; " + LatticeField.status() : "");
    }

    private static int slot(int x, int y, int z) {
        return (Math.floorMod(y, SECTIONS_Y) * sectionsXZ + Math.floorMod(z, sectionsXZ)) * sectionsXZ + Math.floorMod(x, sectionsXZ);
    }

    /** A new world or window size: textures allocated and cleared (every texel unknown), every slot empty. */
    private static void start(ClientLevel world, int newSize) {
        level = world;
        if (newSize != size) {
            size = newSize;
            log2Size = Integer.numberOfTrailingZeros(size);
            sectionsXZ = size / 16;
            int slots = sectionsXZ * sectionsXZ * SECTIONS_Y;
            slotSection = new long[slots];
            slotDirty = new boolean[slots];
            slotHash = new long[slots];
            slotShapeHash = new long[slots];
            offsets = sectionOffsets();
            refreshCursor = 0;
            if (cellsTexture == 0) {
                cellsTexture = GL11C.glGenTextures();
                shapeTexture = GL11C.glGenTextures();
            }
            allocate(cellsTexture, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER);
            allocate(shapeTexture, GL30C.GL_RG32UI, GL30C.GL_RG_INTEGER);
            if (cellsUpload == null) {
                cellsUpload = MemoryUtil.memAllocInt(4096);
                shapeUpload = MemoryUtil.memAllocInt(8192);
            }
        }
        clear(cellsTexture, GL30C.GL_RED_INTEGER, 1);
        clear(shapeTexture, GL30C.GL_RG_INTEGER, 2);
        Arrays.fill(slotSection, NONE);
        Arrays.fill(slotDirty, false);
        Arrays.fill(slotHash, 0L);
        Arrays.fill(slotShapeHash, 0L);
        infoX = Integer.MIN_VALUE;
        LatticeField.reset();
        fillStart = System.nanoTime();
        fillFrames = 0;
        fillNanos = 0;
    }

    /** Offsets of the window's sections from the camera's section, nearest first. */
    private static int[] sectionOffsets() {
        int half = sectionsXZ / 2;
        Integer[] order = new Integer[sectionsXZ * sectionsXZ * SECTIONS_Y];
        int[] all = new int[order.length * 3];
        int n = 0;
        for (int y = -SECTIONS_Y / 2; y < SECTIONS_Y / 2; y++) {
            for (int z = -half; z < half; z++) {
                for (int x = -half; x < half; x++) {
                    all[n * 3] = x;
                    all[n * 3 + 1] = y;
                    all[n * 3 + 2] = z;
                    order[n] = n;
                    n++;
                }
            }
        }
        Arrays.sort(order, (a, b) -> Integer.compare(distance(all, a), distance(all, b)));
        int[] sorted = new int[all.length];
        for (int i = 0; i < order.length; i++) {
            System.arraycopy(all, order[i] * 3, sorted, i * 3, 3);
        }
        return sorted;
    }

    private static int distance(int[] all, int i) {
        // Section centres from the camera's section centre, doubled to stay whole
        int x = all[i * 3] * 2 + 1;
        int y = all[i * 3 + 1] * 2 + 1;
        int z = all[i * 3 + 2] * 2 + 1;
        return x * x + y * y + z * z;
    }

    private static void allocate(int texture, int internalFormat, int format) {
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, internalFormat, size, HEIGHT, size, 0, format, GL11C.GL_UNSIGNED_INT, (IntBuffer) null);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
    }

    /** Zeroes a whole texture: every texel unknown (the caller restores the 3D binding). */
    private static void clear(int texture, int format, int components) {
        if (GL.getCapabilities().OpenGL44 || GL.getCapabilities().GL_ARB_clear_texture) {
            GL44C.glClearTexImage(texture, 0, format, GL11C.GL_UNSIGNED_INT, (IntBuffer) null);
            return;
        }
        int previous = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        int[] pixelBuffers = CompanionTexture.preparePixelTransfer();
        IntBuffer zeros = MemoryUtil.memCallocInt(size * HEIGHT * components);
        try {
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
            for (int z = 0; z < size; z++) {
                GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, z, size, HEIGHT, 1, format, GL11C.GL_UNSIGNED_INT, zeros);
            }
        } finally {
            MemoryUtil.memFree(zeros);
            CompanionTexture.restorePixelBuffers(pixelBuffers);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previous);
        }
    }

    /** Marks the lattice unusable (the pack falls back), so a later start fills it again. */
    private static void unlink() {
        if (level == null) {
            return;
        }
        level = null;
        writeInfo(0, 0, 0, 0);
    }

    /**
     * The info texture: magic, protocol and window size, then the window's lowest corner in blocks; then the openness
     * field's magic (0 while it isn't baked), protocol, width and reach in quarter blocks, then its lowest corner.
     */
    private static void writeInfo(int magic, int minX, int minY, int minZ) {
        infoX = minX;
        infoY = minY;
        infoZ = minZ;
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        Gl.bindTexture(CubeTextures.LATTICE_INFO.glId());
        int[] pixelBuffers = CompanionTexture.preparePixelTransfer();
        IntBuffer info = MemoryUtil.memAllocInt(16);
        try {
            info.put(magic).put(PROTOCOL).put(size).put(HEIGHT).put(minX).put(minY).put(minZ).put(0)
                    .put(magic != 0 && infoField ? LatticeField.MAGIC : 0).put(LatticeField.PROTOCOL).put(LatticeField.WIDTH).put((int) (LatticeField.REACH * 4))
                    .put(LatticeField.windowMin(fieldCentreX)).put(LatticeField.windowMin(fieldCentreY)).put(LatticeField.windowMin(fieldCentreZ)).put(0).flip();
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 4, 1, GL30C.GL_RGBA_INTEGER, GL11C.GL_INT, info);
        } finally {
            MemoryUtil.memFree(info);
            CompanionTexture.restorePixelBuffers(pixelBuffers);
            Gl.bindTextureIfAlive(previous);
        }
    }

    /**
     * Reads a section into its slot and uploads it. A refresh uploads only when the slot already holds this section
     * and its contents changed.
     */
    private static void build(int slot, int sectionX, int sectionY, int sectionZ, boolean refresh) {
        long start = System.nanoTime();
        long key = SectionPos.asLong(sectionX, sectionY, sectionZ);
        boolean sameSection = slotSection[slot] == key;
        slotSection[slot] = key;
        slotDirty[slot] = false;
        int parity = ((sectionX * 16 >> log2Size) & 1) | ((sectionY * 16 >> 7) & 1) << 1 | ((sectionZ * 16 >> log2Size) & 1) << 2;
        int tag = VALID | parity << 28;
        fill(sectionX, sectionY, sectionZ, tag);
        long hash = hash();
        slotShapeHash[slot] = shapeHash();
        if (!(refresh || sameSection) || hash != slotHash[slot]) {
            slotHash[slot] = hash;
            int x0 = Math.floorMod(sectionX, sectionsXZ) * 16;
            int y0 = Math.floorMod(sectionY, SECTIONS_Y) * 16;
            int z0 = Math.floorMod(sectionZ, sectionsXZ) * 16;
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, cellsTexture);
            GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, x0, y0, z0, 16, 16, 16, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, cellsUpload);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, shapeTexture);
            GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, x0, y0, z0, 16, 16, 16, GL30C.GL_RG_INTEGER, GL11C.GL_UNSIGNED_INT, shapeUpload);
            uploadedLastFrame++;
        }
        builtLastFrame++;
        long nanos = System.nanoTime() - start;
        nanosPerSection = nanosPerSection == 0 ? nanos : nanosPerSection * 0.95 + nanos * 0.05;
    }

    /** Fills the upload buffers with a section, x fastest, then y, then z (the texture's layout). */
    private static void fill(int sectionX, int sectionY, int sectionZ, int tag) {
        cellsUpload.clear();
        shapeUpload.clear();
        LevelChunk chunk = level.getChunkSource().getChunk(sectionX, sectionZ, false);
        int index = level.getSectionIndexFromSectionY(sectionY);
        if (chunk == null || index < 0) {
            // Not loaded, or below the world: unknown
            for (int i = 0; i < 4096; i++) {
                cellsUpload.put(i, 0);
                shapeUpload.put(2 * i, 0);
                shapeUpload.put(2 * i + 1, 0);
            }
            return;
        }
        if (index >= level.getSectionsCount()) {
            // Above the world: open air
            int air = tag | (level.dimensionType().hasSkyLight() ? 15 << 4 : 0);
            for (int i = 0; i < 4096; i++) {
                cellsUpload.put(i, air);
                shapeUpload.put(2 * i, 0);
                shapeUpload.put(2 * i + 1, 0);
            }
            return;
        }
        LevelChunkSection section = chunk.getSection(index);
        SectionPos sectionPos = SectionPos.of(sectionX, sectionY, sectionZ);
        LayerLightEventListener blockListener = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        LayerLightEventListener skyListener = level.getLightEngine().getLayerListener(LightLayer.SKY);
        DataLayer blockLight = blockListener.getDataLayerData(sectionPos);
        DataLayer skyLight = skyListener.getDataLayerData(sectionPos);
        if (skyLight == null) {
            // No sky light data stored here: every column holds the light it gets from above
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    columnSky[x + 16 * z] = skyListener.getLightValue(pos.set(sectionX * 16 + x, sectionY * 16 + 15, sectionZ * 16 + z));
                }
            }
        }
        boolean onlyAir = section.hasOnlyAir();
        BlockState lastState = null;
        int lastId = -1;
        int i = 0;
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++, i++) {
                    long shape = 0;
                    int bits = 0;
                    if (!onlyAir) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state != lastState) {
                            lastState = state;
                            lastId = stateId(state, sectionX * 16 + x, sectionY * 16 + y, sectionZ * 16 + z);
                        }
                        shape = stateShape[lastId];
                        bits = stateBits[lastId];
                    }
                    int light = (blockLight == null ? 0 : blockLight.get(x, y, z)) | (skyLight == null ? columnSky[x + 16 * z] : skyLight.get(x, y, z)) << 4;
                    cellsUpload.put(i, tag | light | bits);
                    shapeUpload.put(2 * i, (int) shape);
                    shapeUpload.put(2 * i + 1, (int) (shape >>> 32));
                }
            }
        }
    }

    private static long hash() {
        long h = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 4096; i++) {
            h = (h ^ cellsUpload.get(i)) * 0x100000001B3L;
        }
        for (int i = 0; i < 8192; i++) {
            h = (h ^ shapeUpload.get(i)) * 0x100000001B3L;
        }
        return h;
    }

    /** Shapes and the plant and see-through flags: what the openness field reads. */
    private static long shapeHash() {
        long h = 0xC2B2AE3D27D4EB4FL;
        for (int i = 0; i < 4096; i++) {
            h = (h ^ (cellsUpload.get(i) & (SEE_THROUGH | NO_COLLISION))) * 0x100000001B3L;
            h = (h ^ shapeUpload.get(2 * i)) * 0x100000001B3L;
            h = (h ^ shapeUpload.get(2 * i + 1)) * 0x100000001B3L;
        }
        return h;
    }

    /** The block state's id, its shape and bits worked out the first time it is seen. */
    private static int stateId(BlockState state, int x, int y, int z) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0) {
            id = 0;
        }
        if (id >= stateKnown.length) {
            int length = Math.max(id + 1, Math.max(Block.BLOCK_STATE_REGISTRY.size(), stateKnown.length * 2));
            stateShape = Arrays.copyOf(stateShape, length);
            stateBits = Arrays.copyOf(stateBits, length);
            stateKnown = Arrays.copyOf(stateKnown, length);
        }
        if (!stateKnown[id]) {
            stateKnown[id] = true;
            statesKnown++;
            describe(id, state, pos.set(x, y, z));
        }
        return id;
    }

    private static void describe(int id, BlockState state, BlockPos blockPos) {
        long shape;
        boolean collides;
        try {
            shape = cells(state.getShape(level, blockPos, CollisionContext.empty()));
            collides = !state.getCollisionShape(level, blockPos, CollisionContext.empty()).isEmpty();
        } catch (RuntimeException e) {
            // A modded block that needs more of the world than is at hand: a full block if it hides its neighbours
            shape = state.canOcclude() ? -1L : 0L;
            collides = shape != 0;
        }
        int emission;
        try {
            emission = Mth.clamp(state.getLightEmission(level, blockPos), 0, 15);
        } catch (RuntimeException e) {
            emission = Mth.clamp(state.getLightEmission(), 0, 15);
        }
        FluidState fluid = state.getFluidState();
        int bits = emission << 8;
        if (fluid.is(FluidTags.WATER)) {
            bits |= WATER;
        } else if (fluid.is(FluidTags.LAVA)) {
            bits |= LAVA;
        }
        if (shape != 0 && !state.canOcclude()) {
            bits |= SEE_THROUGH;
        }
        if (shape != 0 && !collides) {
            bits |= NO_COLLISION;
        }
        stateShape[id] = shape;
        stateBits[id] = bits & STATE_BITS;
    }

    /** A shape as 4 x 4 x 4 cells: bit x + 4 z + 16 y for the cell at (x, y, z), each cell a quarter block. */
    static long cells(VoxelShape shape) {
        if (shape.isEmpty()) {
            return 0L;
        }
        long[] cells = {0L};
        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            for (int y = 0; y < 4; y++) {
                if (!overlaps(minY, maxY, y)) {
                    continue;
                }
                for (int z = 0; z < 4; z++) {
                    if (!overlaps(minZ, maxZ, z)) {
                        continue;
                    }
                    for (int x = 0; x < 4; x++) {
                        if (overlaps(minX, maxX, x)) {
                            cells[0] |= 1L << (x + 4 * z + 16 * y);
                        }
                    }
                }
            }
        });
        return cells[0];
    }

    private static boolean overlaps(double min, double max, int cell) {
        return Math.min(max, (cell + 1) * 0.25) - Math.max(min, cell * 0.25) > CELL_OVERLAP;
    }
}
