package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.far.FarViews;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttribute;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexFormat;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.LocalSectionIndex;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.compat.sodium.impl.vertex_format.IrisChunkMeshAttributes;
import net.irisshaders.iris.compat.sodium.impl.vertex_format.terrain_xhfp.XHFPModelVertexType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws one view (a cube face or the mirror view) into the bound target as surface data (see API.md): Embeddium's built
 * chunk meshes and the collected entities with the mod's own programs, and the mod's far terrain ({@link FarViews}).
 * {@link CubeView} runs the frame and says what to draw; this class owns the programs, buffers and vertex arrays they
 * share.
 */
final class ViewRenderer {
    /** The cut plane of a view without one, and the camera offset of a view whose origin is the camera. */
    static final float[] NO_CLIP = {0.0F, 0.0F, 0.0F, 1.0F};
    static final float[] NO_OFFSET = {0.0F, 0.0F, 0.0F};

    private static final String[] ATTRIBUTE_NAMES = {"a_PosId", "a_Color", "a_TexCoord", "a_LightCoord", "a_Normal", "a_BlockId", "a_MidBlock"};
    private static final int[] ATTRIBUTE_LOCATIONS = {1, 2, 3, 4, 10, 11, 14};
    private static final String[] ENTITY_ATTRIBUTES = {"a_Position", "a_Color", "a_TexCoord", "a_Light", "a_Normal", "a_Meta"};
    private static final int MAX_DRAWS = 256 * ModelQuadFacing.COUNT;

    // Counts for the status line: this frame's views together
    static int sectionsDrawn;
    static int drawCalls;
    static long quadsDrawn;

    /** The view being drawn: its projection, cut plane and the camera relative to its origin (none and zero for the cube). */
    private static FloatBuffer matrixBuffer;
    private static float[] viewClipPlane = NO_CLIP;
    private static float[] viewCamera = NO_OFFSET;
    /** 1 / log2(far / near) for this frame's views. */
    private static float logDepth;
    /** The mobs and block entities (a frame old) and the local player in first person (this frame's, mirror view only). */
    private static List<EntityCapture.Batch> entityBatches = List.of();
    private static List<EntityCapture.Batch> playerBatches = List.of();
    /** The offset that takes the collected entities' vertices to this frame's camera. */
    private static final float[] entityOffset = {0.0F, 0.0F, 0.0F};

    // GL objects, created on first use.
    private static ViewProgram program;
    private static int uRegionOffset;
    private static int uTranslucent;
    private static int vertexArray;
    private static int indexBuffer;
    private static int indexCapacityQuads;
    private static ViewProgram entityProgram;
    private static int uEntityTranslucent;
    private static int entityVertexArray;
    private static int entityBuffer;
    private static int playerVertexArray;
    private static int playerBuffer;
    private static ByteBuffer entityStaging;
    private static IntBuffer drawCounts;
    private static IntBuffer drawBaseVertices;
    private static PointerBuffer drawOffsets;

    /** The terrain vertex format last checked, its attributes (null when it can't be drawn) and why not. */
    private static ChunkVertexType checkedVertexType;
    private static GlVertexAttribute[] vertexAttributes;
    private static String formatProblem;

    private ViewRenderer() {
    }

    /** Why Embeddium's terrain can't be drawn with this vertex format, or null when it can. */
    static String checkVertexType(ChunkVertexType type) {
        if (type == checkedVertexType) {
            return formatProblem;
        }
        checkedVertexType = type;
        vertexAttributes = null;
        formatProblem = null;
        if (!(type instanceof XHFPModelVertexType)) {
            formatProblem = "terrain isn't in Oculus's shader vertex format";
            return formatProblem;
        }
        GlVertexFormat<ChunkMeshAttribute> format = type.getVertexFormat();
        ChunkMeshAttribute[] keys = {
                ChunkMeshAttribute.POSITION_MATERIAL_MESH, ChunkMeshAttribute.COLOR_SHADE, ChunkMeshAttribute.BLOCK_TEXTURE,
                ChunkMeshAttribute.LIGHT_TEXTURE, IrisChunkMeshAttributes.NORMAL, IrisChunkMeshAttributes.BLOCK_ID, IrisChunkMeshAttributes.MID_BLOCK
        };
        GlVertexAttribute[] attributes = new GlVertexAttribute[keys.length];
        for (int i = 0; i < keys.length; i++) {
            try {
                attributes[i] = keys[i] == null ? null : format.getAttribute(keys[i]);
            } catch (RuntimeException e) {
                attributes[i] = null;
            }
            if (attributes[i] == null) {
                formatProblem = "terrain vertex format lacks " + ATTRIBUTE_NAMES[i];
                return formatProblem;
            }
        }
        vertexAttributes = attributes;
        ShadersCompanion.LOGGER.info("Shaders Companion: terrain vertex format accepted (stride {} bytes)", format.getStride());
        return null;
    }

    /** Creates the programs, buffers and vertex arrays on first use. */
    static void ensureResources() {
        if (program != null) {
            return;
        }
        program = new ViewProgram("cube_terrain", ATTRIBUTE_NAMES, ATTRIBUTE_LOCATIONS);
        uRegionOffset = program.uniform("u_RegionOffset");
        uTranslucent = program.uniform("u_Translucent");
        vertexArray = GL30C.glGenVertexArrays();
        indexBuffer = GL15C.glGenBuffers();
        GlStateManager._glBindVertexArray(vertexArray);
        for (int location : ATTRIBUTE_LOCATIONS) {
            GL20C.glEnableVertexAttribArray(location);
        }
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);

        entityProgram = new ViewProgram("cube_entity", ENTITY_ATTRIBUTES, new int[]{0, 1, 2, 3, 4, 5});
        uEntityTranslucent = entityProgram.uniform("u_Translucent");
        entityBuffer = GL15C.glGenBuffers();
        entityVertexArray = createEntityVertexArray(entityBuffer);
        playerBuffer = GL15C.glGenBuffers();
        playerVertexArray = createEntityVertexArray(playerBuffer);
        GlStateManager._glBindVertexArray(0);
        Gl.useProgram(program.id);
        GL20C.glUniform1i(program.uniform("u_BlockAtlas"), 0);
        Gl.useProgram(entityProgram.id);
        GL20C.glUniform1i(entityProgram.uniform("u_Texture"), 0);

        drawCounts = MemoryUtil.memAllocInt(MAX_DRAWS);
        drawBaseVertices = MemoryUtil.memAllocInt(MAX_DRAWS);
        drawOffsets = MemoryUtil.memCallocPointer(MAX_DRAWS);
        matrixBuffer = MemoryUtil.memAllocFloat(16);
    }

    /** A vertex array over an entity buffer, in {@link EntityCapture}'s vertex layout, with the shared quad indices. */
    private static int createEntityVertexArray(int buffer) {
        int array = GL30C.glGenVertexArrays();
        GlStateManager._glBindVertexArray(array);
        GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        int stride = EntityCapture.STRIDE;
        GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, stride, 0L);
        GL20C.glVertexAttribPointer(1, 4, GL11C.GL_UNSIGNED_BYTE, true, stride, 12L);
        GL20C.glVertexAttribPointer(2, 2, GL11C.GL_FLOAT, false, stride, 16L);
        GL30C.glVertexAttribIPointer(3, 2, GL11C.GL_UNSIGNED_SHORT, stride, 24L);
        GL20C.glVertexAttribPointer(4, 3, GL11C.GL_BYTE, true, stride, 28L);
        GL30C.glVertexAttribIPointer(5, 2, GL11C.GL_UNSIGNED_SHORT, stride, 32L);
        for (int location = 0; location < ENTITY_ATTRIBUTES.length; location++) {
            GL20C.glEnableVertexAttribArray(location);
        }
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        return array;
    }

    /**
     * Starts a frame's drawing: zeroes the counts, sets the depth scale for the views' far plane and sets the GL state
     * every view draws with (the caller has saved it, and binds each view's target).
     */
    static void beginFrame(float near, float far) {
        sectionsDrawn = 0;
        drawCalls = 0;
        quadsDrawn = 0;
        logDepth = (float) (1.0 / (Math.log(far / near) / Math.log(2.0)));
        GlStateManager._disableBlend();
        GlStateManager._disableCull();
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_LESS);
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._stencilMask(0xFF);
        GlStateManager._enableScissorTest();
        Gl.activeTexture(GL13C.GL_TEXTURE0);
    }

    /** Takes the local player's vertices for this frame's mirror view (none when the capture is empty). */
    static void setPlayer(EntityCapture capture) {
        playerBatches = upload(capture, playerVertexArray, playerBuffer);
    }

    /** Takes the mobs' and block entities' vertices for the next frame's views. */
    static void setEntities(EntityCapture capture) {
        entityBatches = upload(capture, entityVertexArray, entityBuffer);
    }

    static void clearPlayer() {
        playerBatches = List.of();
    }

    static void clearEntities() {
        entityBatches = List.of();
    }

    /** The offset that takes the collected entities' vertices (relative to the camera they were collected at) to this frame's camera. */
    static void setEntityOffset(float x, float y, float z) {
        entityOffset[0] = x;
        entityOffset[1] = y;
        entityOffset[2] = z;
    }

    /** Uploads a collection's vertices to its buffer and returns its batches. */
    private static List<EntityCapture.Batch> upload(EntityCapture capture, int array, int buffer) {
        int vertices = capture.vertexCount();
        if (vertices == 0) {
            return List.of();
        }
        int bytes = vertices * EntityCapture.STRIDE;
        if (entityStaging == null || entityStaging.capacity() < bytes) {
            if (entityStaging != null) {
                MemoryUtil.memFree(entityStaging);
            }
            entityStaging = MemoryUtil.memAlloc(Math.max(bytes, 1 << 20));
        }
        List<EntityCapture.Batch> batches = capture.write(entityStaging);
        entityStaging.position(0).limit(bytes);
        GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, entityStaging, GL15C.GL_STREAM_DRAW);
        entityStaging.clear();
        int maxQuads = 0;
        for (EntityCapture.Batch batch : batches) {
            maxQuads = Math.max(maxQuads, batch.vertexCount() / 4);
        }
        GlStateManager._glBindVertexArray(array);
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        ensureIndexCapacity(maxQuads);
        return batches;
    }

    /**
     * How a view gets the far terrain ({@link FarViews}): the matrix its far terrain is culled by, for positions relative
     * to the camera (null: the view's own), its cut plane for positions relative to the camera (null for none), and
     * whether that plane is level (far water up to a voxel above it is the mirror's own water, cut with it).
     */
    record Far(Matrix4f cull, float[] cut, boolean levelPlane) {
    }

    /**
     * Draws a view into the bound target (viewport and scissor set): opaque surfaces first (blocks, entities, far
     * terrain), then translucent ones (water, glass) over them with the depth test, so each texel keeps the nearest
     * surface. The far terrain is drawn only where the chunk terrain and the entities drew nothing (the stencil).
     *
     * @param viewProjection the view's matrix for positions relative to its origin
     * @param seen           the chunk sections and entities the view can see (boxes relative to the camera)
     * @param far            how the view gets the far terrain, or null for none
     * @param origin         the view's origin: the camera for the cube, the mirrored camera for the mirror view
     * @param clipPlane      the view's cut plane ({@link #NO_CLIP} for none)
     * @param cameraOffset   the camera relative to the view's origin
     * @param mirror         whether this is the mirror view, which also draws the local player
     * @param afterOpaque    run between the opaque and the translucent surfaces (null for nothing)
     */
    static void drawView(Matrix4f viewProjection, BoxTest seen, Far far, Vec3 camera, Vec3 origin, float[] clipPlane, float[] cameraOffset,
                         RenderRegionManager regions, boolean mirror, Runnable afterOpaque) {
        viewProjection.get(matrixBuffer);
        viewClipPlane = clipPlane;
        viewCamera = cameraOffset;
        // The far terrain's selection for this view, before anything is drawn: positions relative to the camera
        int farSlice = -1;
        if (far != null) {
            Matrix4f farMatrix = new Matrix4f(viewProjection).translate((float) (camera.x - origin.x), (float) (camera.y - origin.y), (float) (camera.z - origin.z));
            farSlice = FarViews.select(far.cull() != null ? far.cull() : farMatrix, farMatrix, (float) (origin.x - camera.x), (float) (origin.y - camera.y),
                    (float) (origin.z - camera.z), far.cut(), far.levelPlane(), mirror);
        }
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        try {
            markTexels(true);
            drawTerrain(seen, camera, origin, regions, false);
            drawEntities(false, mirror);
            markTexels(false);
            drawFar(farSlice, false);
            if (afterOpaque != null) {
                afterOpaque.run();
            }
            markTexels(true);
            drawTerrain(seen, camera, origin, regions, true);
            drawEntities(true, mirror);
            markTexels(false);
            drawFar(farSlice, true);
        } finally {
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            viewClipPlane = NO_CLIP;
            viewCamera = NO_OFFSET;
        }
    }

    /**
     * The stencil for a view's passes: chunk terrain and entities mark the texels they draw (mark true), and the far
     * terrain is drawn only where nothing is marked (false). So the chunk terrain stays in front wherever it reaches, as
     * in the main view, and the far terrain doesn't show through it where both describe the same surface near the
     * render distance's edge.
     */
    private static void markTexels(boolean mark) {
        if (mark) {
            GlStateManager._stencilFunc(GL11C.GL_ALWAYS, 1, 0xFF);
            GlStateManager._stencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_REPLACE);
        } else {
            GlStateManager._stencilFunc(GL11C.GL_EQUAL, 0, 0xFF);
            GlStateManager._stencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
        }
    }

    /** Binds a program with the current view's projection, depth scale, cut plane and camera offset. */
    private static void use(ViewProgram viewProgram) {
        viewProgram.use(matrixBuffer, logDepth, CubeView.NEAR, viewClipPlane, viewCamera);
    }

    /**
     * Draws the collected entities: opaque and glowing layers, or translucent ones. The mobs and block entities were
     * collected after the last frame, relative to its camera; the mirror view also draws the local player.
     */
    private static void drawEntities(boolean translucent, boolean mirror) {
        if (entityBatches.isEmpty() && (!mirror || playerBatches.isEmpty())) {
            return;
        }
        use(entityProgram);
        GL20C.glUniform1i(uEntityTranslucent, translucent ? 1 : 0);
        // Overlay layers (eyes, armour, clothing) sit exactly on the model's surface
        GlStateManager._depthFunc(GL11C.GL_LEQUAL);
        if (mirror && !playerBatches.isEmpty()) {
            GlStateManager._glBindVertexArray(playerVertexArray);
            drawEntityBatches(playerBatches, translucent);
        }
        if (!entityBatches.isEmpty()) {
            entityProgram.setCamera(viewCamera[0] + entityOffset[0], viewCamera[1] + entityOffset[1], viewCamera[2] + entityOffset[2]);
            GlStateManager._glBindVertexArray(entityVertexArray);
            drawEntityBatches(entityBatches, translucent);
        }
        GlStateManager._depthFunc(GL11C.GL_LESS);
    }

    private static void drawEntityBatches(List<EntityCapture.Batch> batches, boolean translucent) {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        ResourceLocation bound = null;
        boolean skip = false;
        for (EntityCapture.Batch batch : batches) {
            if ((batch.kind() == EntityCapture.KIND_TRANSLUCENT) != translucent) {
                continue;
            }
            if (!batch.texture().equals(bound)) {
                skip = !bindEntityTexture(textures, batch.texture());
                bound = batch.texture();
            }
            if (skip) {
                continue;
            }
            GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, batch.vertexCount() / 4 * 6, GL11C.GL_UNSIGNED_INT, 0L, batch.firstVertex());
            drawCalls++;
            quadsDrawn += batch.vertexCount() / 4;
        }
    }

    /**
     * Binds an entity texture by name; false when there is none to draw with. The batches are a frame old, and a
     * texture a mod made in memory (a health bar, a name plate) may have been released since: asking the texture
     * manager for it the usual way would try to load it as a file, fail, and register the missing texture under its
     * name. Only a texture that is registered, or that is a file the game hasn't used yet, is bound.
     */
    private static boolean bindEntityTexture(TextureManager textures, ResourceLocation location) {
        AbstractTexture texture = textures.getTexture(location, null);
        if (texture == null) {
            if (Minecraft.getInstance().getResourceManager().getResource(location).isEmpty()) {
                return false;
            }
            texture = textures.getTexture(location);
        }
        Gl.bindTexture(texture.getId());
        return true;
    }

    /** Draws a view's far terrain, its opaque or translucent part, where the stencil leaves room (its slice from FarViews.select). */
    private static void drawFar(int slice, boolean translucent) {
        if (slice < 0) {
            return;
        }
        FarViews.draw(slice, translucent, logDepth, CubeView.NEAR);
        drawCalls++;
    }

    /**
     * Draws the chunk meshes a view can see ({@code seen}, boxes relative to the camera), positioned relative to the
     * view's origin: the camera for the cube, the mirrored camera for the mirror view.
     */
    private static void drawTerrain(BoxTest seen, Vec3 camera, Vec3 origin, RenderRegionManager regions, boolean translucent) {
        use(program);
        GlStateManager._depthFunc(GL11C.GL_LESS);
        Gl.activeTexture(GL13C.GL_TEXTURE0);
        Gl.bindTexture(Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());
        GlStateManager._glBindVertexArray(vertexArray);
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);

        int originBlockX = (int) Math.floor(origin.x);
        int originBlockY = (int) Math.floor(origin.y);
        int originBlockZ = (int) Math.floor(origin.z);
        // Embeddium sorts translucent geometry by default: then each section's quads sit in the UNASSIGNED facing
        // with their own indices in the region's index buffer, and the other passes share a quad index pattern.
        TerrainRenderPass[] passes = translucent
                ? new TerrainRenderPass[]{DefaultTerrainRenderPasses.TRANSLUCENT}
                : new TerrainRenderPass[]{DefaultTerrainRenderPasses.SOLID, DefaultTerrainRenderPasses.CUTOUT};
        int boundBuffer = -1;
        int boundElements = indexBuffer;
        for (TerrainRenderPass pass : passes) {
            boolean sorted = pass.isSorted();
            GL20C.glUniform1i(uTranslucent, pass == DefaultTerrainRenderPasses.TRANSLUCENT ? 1 : 0);
            for (RenderRegion region : regions.getLoadedRegions()) {
                SectionRenderDataStorage storage = region.getStorage(pass);
                RenderRegion.DeviceResources resources = region.getResources();
                if (storage == null || resources == null) {
                    continue;
                }
                double cx = region.getOriginX() - camera.x;
                double cy = region.getOriginY() - camera.y;
                double cz = region.getOriginZ() - camera.z;
                if (!seen.visible(cx, cy, cz, cx + 128.0, cy + 64.0, cz + 128.0)) {
                    continue;
                }

                int draws = 0;
                int maxElements = 0;
                for (int index = 0; index < RenderRegion.REGION_SIZE; index++) {
                    long mesh = storage.getDataPointer(index);
                    int slices = SectionRenderDataUnsafe.getSliceMask(mesh);
                    if (slices == 0) {
                        continue;
                    }
                    int chunkX = region.getChunkX() + LocalSectionIndex.unpackX(index);
                    int chunkY = region.getChunkY() + LocalSectionIndex.unpackY(index);
                    int chunkZ = region.getChunkZ() + LocalSectionIndex.unpackZ(index);
                    double sx = (chunkX << 4) - camera.x;
                    double sy = (chunkY << 4) - camera.y;
                    double sz = (chunkZ << 4) - camera.z;
                    if (!seen.visible(sx, sy, sz, sx + 16.0, sy + 16.0, sz + 16.0)) {
                        continue;
                    }
                    if (!sorted) {
                        slices &= visibleFacings(originBlockX, originBlockY, originBlockZ, chunkX, chunkY, chunkZ);
                        if (slices == 0) {
                            continue;
                        }
                    }
                    sectionsDrawn++;
                    for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
                        if ((slices >> facing & 1) == 0) {
                            continue;
                        }
                        int elements = SectionRenderDataUnsafe.getElementCount(mesh, facing);
                        if (elements == 0) {
                            continue;
                        }
                        drawCounts.put(draws, elements);
                        drawBaseVertices.put(draws, SectionRenderDataUnsafe.getVertexOffset(mesh, facing));
                        drawOffsets.put(draws, sorted ? Integer.toUnsignedLong(SectionRenderDataUnsafe.getIndexOffset(mesh, facing)) : 0L);
                        draws++;
                        maxElements = Math.max(maxElements, elements);
                        quadsDrawn += elements / 6;
                    }
                }
                if (draws == 0) {
                    continue;
                }

                int vertexBuffer = resources.getVertexBuffer().handle();
                if (vertexBuffer != boundBuffer) {
                    bindVertexBuffer(vertexBuffer);
                    boundBuffer = vertexBuffer;
                }
                int elementBuffer = sorted ? resources.getIndexBuffer().handle() : indexBuffer;
                if (elementBuffer != boundElements) {
                    GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
                    boundElements = elementBuffer;
                }
                if (!sorted) {
                    ensureIndexCapacity(maxElements / 6);
                }
                GL20C.glUniform3f(uRegionOffset, (float) (region.getOriginX() - origin.x), (float) (region.getOriginY() - origin.y),
                        (float) (region.getOriginZ() - origin.z));
                drawCounts.position(0).limit(draws);
                drawBaseVertices.position(0).limit(draws);
                drawOffsets.position(0).limit(draws);
                GL32C.glMultiDrawElementsBaseVertex(GL11C.GL_TRIANGLES, drawCounts, GL11C.GL_UNSIGNED_INT, drawOffsets, drawBaseVertices);
                drawCounts.clear();
                drawBaseVertices.clear();
                drawOffsets.clear();
                drawCalls++;
            }
        }
        if (boundElements != indexBuffer) {
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        }
    }

    private static void bindVertexBuffer(int handle) {
        GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, handle);
        for (int i = 0; i < ATTRIBUTE_LOCATIONS.length; i++) {
            GlVertexAttribute attribute = vertexAttributes[i];
            if (attribute.isIntType()) {
                GL30C.glVertexAttribIPointer(ATTRIBUTE_LOCATIONS[i], attribute.getCount(), attribute.getFormat(), attribute.getStride(), attribute.getPointer());
            } else {
                GL20C.glVertexAttribPointer(ATTRIBUTE_LOCATIONS[i], attribute.getCount(), attribute.getFormat(), attribute.isNormalized(), attribute.getStride(), attribute.getPointer());
            }
        }
    }

    /** Makes the bound element buffer (the shared quad indices) hold at least this many quads. */
    private static void ensureIndexCapacity(int quads) {
        if (quads <= indexCapacityQuads) {
            return;
        }
        int capacity = Math.max(quads, Math.max(indexCapacityQuads * 2, 16384));
        IntBuffer indices = MemoryUtil.memAllocInt(capacity * 6);
        try {
            for (int quad = 0; quad < capacity; quad++) {
                int v = quad * 4;
                indices.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
            }
            indices.flip();
            GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, indices, GL15C.GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(indices);
        }
        indexCapacityQuads = capacity;
    }

    /** The quad facings of a section that can face the view's origin (the same rule Embeddium uses for the main view). */
    private static int visibleFacings(int originX, int originY, int originZ, int chunkX, int chunkY, int chunkZ) {
        int minX = chunkX << 4;
        int minY = chunkY << 4;
        int minZ = chunkZ << 4;
        int planes = 1 << ModelQuadFacing.UNASSIGNED.ordinal();
        if (originX > minX - 3) planes |= 1 << ModelQuadFacing.POS_X.ordinal();
        if (originY > minY - 3) planes |= 1 << ModelQuadFacing.POS_Y.ordinal();
        if (originZ > minZ - 3) planes |= 1 << ModelQuadFacing.POS_Z.ordinal();
        if (originX < minX + 16 + 3) planes |= 1 << ModelQuadFacing.NEG_X.ordinal();
        if (originY < minY + 16 + 3) planes |= 1 << ModelQuadFacing.NEG_Y.ordinal();
        if (originZ < minZ + 16 + 3) planes |= 1 << ModelQuadFacing.NEG_Z.ordinal();
        return planes;
    }
}
