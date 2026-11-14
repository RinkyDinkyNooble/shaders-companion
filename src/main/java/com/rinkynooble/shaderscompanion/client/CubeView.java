package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttribute;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexFormat;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.LocalSectionIndex;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.compat.sodium.impl.vertex_format.IrisChunkMeshAttributes;
import net.irisshaders.iris.compat.sodium.impl.vertex_format.terrain_xhfp.XHFPModelVertexType;
import net.minecraft.client.Camera;
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
 * Draws Embeddium's built chunk meshes into six cube faces around the camera, and into the mirror view
 * ({@link MirrorView}) when the screen shows a reflecting surface, as surface data (see API.md). Runs once per frame
 * before the world is rendered, outside Oculus's world pass, with its own programs, and restores every GL state it
 * touches.
 */
public final class CubeView {
    // Face directions: forward and up; right = forward x up. Face i sits at column i % 3, row i / 3 of the atlas.
    static final int[][] FORWARD = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    static final int[][] UP = {{0, 1, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, 1, 0}};
    static final int[][] RIGHT = new int[6][];
    static final String[] FACE_NAMES = {"+X", "-X", "+Y", "-Y", "+Z", "-Z"};

    static final float NEAR = 0.05F;

    private static final String[] ATTRIBUTE_NAMES = {"a_PosId", "a_Color", "a_TexCoord", "a_LightCoord", "a_Normal", "a_BlockId", "a_MidBlock"};
    private static final int[] ATTRIBUTE_LOCATIONS = {1, 2, 3, 4, 10, 11, 14};
    private static final String[] ENTITY_ATTRIBUTES = {"a_Position", "a_Color", "a_TexCoord", "a_Light", "a_Normal", "a_Meta"};
    private static final String[] LOD_ATTRIBUTES = {"a_Position", "a_Color", "a_Data"};
    /** Frames without the active pack binding the cube textures before the mod stops drawing. */
    private static final int IDLE_FRAMES = 30;
    /** CPU time per frame for drawing entities and block entities into the cube, nearest first. */
    private static final long ENTITY_BUDGET_NANOS = 1_000_000L;
    private static final long BLOCK_ENTITY_BUDGET_NANOS = 500_000L;
    private static final int MAX_DRAWS = 256 * ModelQuadFacing.COUNT;
    private static final int DREW_CUBE = 1;
    private static final int DREW_MIRROR = 2;
    private static final float[] NO_CLIP = {0.0F, 0.0F, 0.0F, 1.0F};
    private static final float[] NO_OFFSET = {0.0F, 0.0F, 0.0F};

    static {
        for (int i = 0; i < 6; i++) {
            int[] f = FORWARD[i];
            int[] u = UP[i];
            RIGHT[i] = new int[]{f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0]};
        }
    }

    // Status for the debug overlay and the status command.
    static String status = "waiting for the first frame";
    static int faceSize;
    static int frameCounter;
    static long cpuNanos;
    static long entityNanos;
    static long mirrorNanos;
    static int sectionsDrawn;
    static int drawCalls;
    static long quadsDrawn;
    static int sectionsQueued;
    static int entitiesDrawn;
    static int blockEntitiesDrawn;
    static int lodContainers;
    static int lodRange;
    static boolean mirrorPlayerDrawn;
    static boolean hookCalled;

    private static boolean failed;
    private static boolean linked;
    private static int nextFace;
    private static final double[][] faceOrigin = new double[6][3];
    private static final int[] faceFrame = {-1, -1, -1, -1, -1, -1};
    private static float lastFar;
    private static float lastMaxDistance;
    private static long lastRequests = -1;
    private static int framesWithoutRequests;
    private static long lastMirrorRequests = -1;
    private static int framesWithoutMirrorRequests;
    /** The view being drawn: its cut plane and the camera relative to its origin (none and zero for the cube). */
    private static float[] viewClipPlane = NO_CLIP;
    private static float[] viewCamera = NO_OFFSET;
    /** Mobs and block entities, collected after the world is drawn for the next frame's views. */
    private static final EntityCapture entities = new EntityCapture();
    private static List<EntityCapture.Batch> entityBatches = List.of();
    /** The camera those vertices are relative to, and the frame hook's count when they were collected. */
    private static Vec3 entityCamera = Vec3.ZERO;
    private static long entityHookFrame = -2;
    private static Object entityLevel;
    /** The local player in first person, collected before the world is drawn for this frame's mirror view. */
    private static final EntityCapture playerEntities = new EntityCapture();
    private static List<EntityCapture.Batch> playerBatches = List.of();
    private static long hookFrame;
    /** Set while this frame drew views, so entities are collected after it for the next one. */
    private static boolean collectAfterFrame;
    private static boolean cubeWanted;
    /** The offset that takes the collected entities' vertices to this frame's camera. */
    private static final float[] entityOffset = {0.0F, 0.0F, 0.0F};
    private static boolean lodsCollected;

    // GL objects, created on first use.
    private static ViewProgram program;
    private static int uRegionOffset;
    private static int uTranslucent;
    private static final ViewTarget cubeTarget = new ViewTarget("cube", CubeTextures.ALBEDO, CubeTextures.DATA, CubeTextures.DISTANCE, CubeTextures.PREVIEW);
    private static final ViewTarget mirrorTarget = new ViewTarget("mirror", CubeTextures.MIRROR_ALBEDO, CubeTextures.MIRROR_DATA, CubeTextures.MIRROR_DISTANCE);
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
    private static ViewProgram lodProgram;
    private static int uLodModelOffset;
    private static int uLodTranslucent;
    private static int uLodClipDistance;
    private static int lodVertexArray;
    private static float logDepth;

    private static IntBuffer drawCounts;
    private static IntBuffer drawBaseVertices;
    private static PointerBuffer drawOffsets;
    private static FloatBuffer matrixBuffer;
    private static FloatBuffer infoBuffer;
    private static ChunkVertexType checkedVertexType;
    private static GlVertexAttribute[] vertexAttributes;

    private CubeView() {
    }

    /**
     * Called from the frame hook, on the render thread, before the world is rendered.
     *
     * @param view the main view's rotation, the pose the world is about to be drawn with
     */
    public static void renderFrame(Camera camera, float partialTick, Matrix4f view) {
        hookCalled = true;
        hookFrame++;
        collectAfterFrame = false;
        if (failed) {
            return;
        }
        int drew = 0;
        try {
            drew = renderFrameUnguarded(camera, partialTick, view);
        } catch (Throwable t) {
            failed = true;
            status = "stopped after an error: " + t;
            ShadersCompanion.LOGGER.error("Shaders Companion stopped drawing after an error", t);
        }
        boolean cubeGone = (drew & DREW_CUBE) == 0;
        boolean mirrorGone = (drew & DREW_MIRROR) == 0;
        if (cubeGone && linked || mirrorGone && MirrorView.linked()) {
            // Marking a view as gone writes its info texture: with the GL state saved, like the drawing
            GlState saved = GlState.capture();
            try {
                if (cubeGone) {
                    unlink();
                }
                if (mirrorGone) {
                    MirrorView.unlink();
                }
            } finally {
                saved.restore();
            }
        } else if (mirrorGone) {
            MirrorView.unlink();
        }
        collectAfterFrame = drew != 0 && !failed;
        cubeWanted = (drew & DREW_CUBE) != 0;
    }

    /**
     * Called from the frame hook after the world is rendered: collects the mobs and block entities for the next
     * frame's views. Drawing them here, after the game's own draw, leaves entity renderers that keep per-frame
     * animation state as they are without this mod.
     */
    public static void collectEntitiesAfterFrame(Camera camera, float partialTick) {
        if (!collectAfterFrame || failed) {
            return;
        }
        collectAfterFrame = false;
        Minecraft mc = Minecraft.getInstance();
        GlState saved = null;
        long start = System.nanoTime();
        try {
            SodiumWorldRenderer worldRenderer = SodiumWorldRenderer.instanceNullable();
            RenderSectionManager sections = worldRenderer == null ? null : EmbeddiumAccess.sectionManager(worldRenderer);
            if (mc.level == null || sections == null) {
                return;
            }
            saved = GlState.capture();
            // The faces the next frame draws, and this frame's mirror view (the next one's is close to it)
            int faces = cubeWanted ? Config.FACES_PER_FRAME.get() : 0;
            BoxTest seen = MirrorView.active() ? MirrorView.seen() : BoxTest.NONE;
            for (int i = 0; i < faces; i++) {
                int face = (nextFace + i) % 6;
                seen = seen.or((minX, minY, minZ, maxX, maxY, maxZ) -> boxInFace(face, minX, minY, minZ, maxX, maxY, maxZ));
            }
            collectEntities(mc, camera, partialTick, seen, EmbeddiumAccess.regions(sections));
            entityCamera = camera.getPosition();
            entityHookFrame = hookFrame;
            entityLevel = mc.level;
        } catch (Throwable t) {
            failed = true;
            status = "stopped after an error: " + t;
            ShadersCompanion.LOGGER.error("Shaders Companion stopped drawing after an error", t);
        } finally {
            if (saved != null) {
                saved.restore();
            }
            entityNanos += System.nanoTime() - start;
        }
    }

    /**
     * Draws this frame's faces and mirror view. Returns DREW_CUBE when the cube's info was written (otherwise the pack
     * must not use the cube) and DREW_MIRROR when the mirror view's was.
     */
    private static int renderFrameUnguarded(Camera camera, float partialTick, Matrix4f view) throws IllegalAccessException {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !Config.ENABLED.get()) {
            status = mc.level == null ? "no world loaded" : "turned off (/shaderscompanion on, or enabled in the config)";
            return 0;
        }
        if (!IrisApi.getInstance().isShaderPackInUse()) {
            status = "no shader pack in use";
            lastRequests = -1;
            lastMirrorRequests = -1;
            return 0;
        }
        // Oculus asks for a pack's custom textures each time it binds them. A pack that doesn't read the cube (or the
        // mirror view) never asks, so after a short wait the mod stops drawing it (the textures are checked again every frame).
        long requests = CompanionTexture.requests;
        if (requests != lastRequests) {
            lastRequests = requests;
            framesWithoutRequests = 0;
        } else {
            framesWithoutRequests++;
        }
        long mirrorRequests = CompanionTexture.mirrorRequests;
        if (mirrorRequests != lastMirrorRequests) {
            lastMirrorRequests = mirrorRequests;
            framesWithoutMirrorRequests = 0;
        } else {
            framesWithoutMirrorRequests++;
        }
        boolean wantCube = framesWithoutRequests <= IDLE_FRAMES;
        boolean wantMirror = framesWithoutMirrorRequests <= IDLE_FRAMES && Config.MIRROR.get();
        MirrorView.status = !Config.MIRROR.get() ? "turned off in the config"
                : framesWithoutMirrorRequests > IDLE_FRAMES ? "the shader pack doesn't read the mirror view" : MirrorView.status;
        if (!wantCube && !wantMirror) {
            status = "the shader pack doesn't read the cube";
            return 0;
        }
        SodiumWorldRenderer worldRenderer = SodiumWorldRenderer.instanceNullable();
        RenderSectionManager sections = worldRenderer == null ? null : EmbeddiumAccess.sectionManager(worldRenderer);
        if (sections == null) {
            status = "Embeddium has no world renderer yet";
            return 0;
        }
        ChunkRenderer chunkRenderer = EmbeddiumAccess.chunkRenderer(sections);
        if (!checkVertexType(chunkRenderer.getVertexType())) {
            return 0;
        }

        GlState saved = null;
        try {
            Vec3 cameraPos = camera.getPosition();
            int size = Config.FACE_SIZE.get();
            saved = GlState.capture();
            long start = System.nanoTime();
            ensureResources(size);
            GpuTimer.begin();
            sectionsDrawn = 0;
            drawCalls = 0;
            quadsDrawn = 0;

            float maxDistance = Config.MAX_DISTANCE.get() > 0 ? Config.MAX_DISTANCE.get() : mc.options.getEffectiveRenderDistance() * 16.0F;
            RenderRegionManager regions = EmbeddiumAccess.regions(sections);
            requestBuilds(sections, regions, cameraPos, maxDistance);
            int faces = wantCube ? Config.FACES_PER_FRAME.get() : 0;

            float lodDistance = collectLods(mc);
            lodRange = (int) lodDistance;
            // Far enough for the range's corners and for the full build height straight up or down.
            float far = Math.max(maxDistance, lodDistance) * 1.75F + 512.0F;
            lastFar = far;
            lastMaxDistance = maxDistance;
            logDepth = (float) (1.0 / (Math.log(far / NEAR) / Math.log(2.0)));
            boolean mirror = wantMirror && MirrorView.update(mc, camera, view, new Matrix4f(RenderSystem.getProjectionMatrix()), NEAR, far, maxDistance);
            long entityStart = System.nanoTime();
            collectLocalPlayer(mc, camera, partialTick, mirror ? MirrorView.seen() : BoxTest.NONE);
            // The mobs and block entities collected after the last frame, when that was the frame before this one
            boolean current = entityHookFrame == hookFrame - 1 && entityLevel == mc.level;
            if (!current) {
                entityBatches = List.of();
                entitiesDrawn = 0;
                blockEntitiesDrawn = 0;
            }
            entityOffset[0] = (float) (entityCamera.x - cameraPos.x);
            entityOffset[1] = (float) (entityCamera.y - cameraPos.y);
            entityOffset[2] = (float) (entityCamera.z - cameraPos.z);
            entityNanos = System.nanoTime() - entityStart;

            setupDrawState(size);
            for (int i = 0; i < faces; i++) {
                int face = nextFace;
                nextFace = (nextFace + 1) % 6;
                drawFace(face, size, cameraPos, maxDistance, lodDistance, far, regions);
                faceOrigin[face][0] = cameraPos.x;
                faceOrigin[face][1] = cameraPos.y;
                faceOrigin[face][2] = cameraPos.z;
                faceFrame[face] = frameCounter;
            }
            long mirrorStart = System.nanoTime();
            if (mirror) {
                drawMirror(cameraPos, maxDistance, lodDistance, regions);
            }
            mirrorNanos = System.nanoTime() - mirrorStart;
            Lods.clear();
            GpuTimer.end();
            cpuNanos = System.nanoTime() - start;
            frameCounter++;
            int drew = 0;
            if (wantCube) {
                writeInfo(cameraPos);
                linked = true;
                drew |= DREW_CUBE;
            }
            if (wantMirror) {
                MirrorView.writeInfo(frameCounter, NEAR, far, maxDistance, lodsCollected ? (float) lodRange : 0.0F);
                drew |= DREW_MIRROR;
            }
            status = wantCube ? "drawing" : "drawing the mirror view only";
            return drew;
        } catch (Throwable t) {
            GpuTimer.end();
            throw t;
        } finally {
            if (saved != null) {
                saved.restore();
            }
        }
    }

    static float lastMaxDistance() {
        return lastMaxDistance;
    }

    /** The current face size in pixels (0 before the first frame). */
    public static int faceSizeForChecks() {
        return faceSize;
    }

    /** Distant Horizons' range in the last frame, 0 without LODs. */
    public static int lodRangeForChecks() {
        return lodsCollected ? lodRange : 0;
    }

    /** Frames drawn since the game started. */
    public static int framesDrawn() {
        return frameCounter;
    }

    /**
     * Marks the cube unusable after a frame without drawing (turned off, no world, an error): the info texture's face
     * size goes to 0, which packs read as "not linked", and every face counts as never drawn until it is redrawn.
     */
    private static void unlink() {
        if (!linked) {
            return;
        }
        linked = false;
        for (int i = 0; i < 6; i++) {
            faceFrame[i] = -1;
        }
        infoBuffer.clear();
        infoBuffer.put(CubeTextures.MAGIC).put(CubeTextures.PROTOCOL).put(0.0F).put(0.0F);
        for (int i = 1; i < CubeTextures.INFO_WIDTH; i++) {
            infoBuffer.put(0.0F).put(0.0F).put(0.0F).put(-1.0F);
        }
        infoBuffer.flip();
        CubeTextures.INFO.writeRow(infoBuffer, CubeTextures.INFO_WIDTH);
    }

    private static boolean checkVertexType(ChunkVertexType type) {
        if (type == checkedVertexType) {
            return vertexAttributes != null;
        }
        checkedVertexType = type;
        vertexAttributes = null;
        if (!(type instanceof XHFPModelVertexType)) {
            status = "terrain isn't in Oculus's shader vertex format";
            return false;
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
                status = "terrain vertex format lacks " + ATTRIBUTE_NAMES[i];
                return false;
            }
        }
        vertexAttributes = attributes;
        ShadersCompanion.LOGGER.info("Shaders Companion: terrain vertex format accepted (stride {} bytes)", format.getStride());
        return true;
    }

    private static void ensureResources(int size) {
        if (program == null) {
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

            lodProgram = new ViewProgram("cube_lod", LOD_ATTRIBUTES, new int[]{0, 1, 2});
            uLodModelOffset = lodProgram.uniform("u_ModelOffset");
            uLodTranslucent = lodProgram.uniform("u_Translucent");
            uLodClipDistance = lodProgram.uniform("u_ClipDistance");
            lodVertexArray = GL30C.glGenVertexArrays();
            GlStateManager._glBindVertexArray(lodVertexArray);
            for (int location = 0; location < 3; location++) {
                GL20C.glEnableVertexAttribArray(location);
            }
            GlStateManager._glBindVertexArray(0);
            Gl.useProgram(program.id);
            GL20C.glUniform1i(program.uniform("u_BlockAtlas"), 0);
            Gl.useProgram(entityProgram.id);
            GL20C.glUniform1i(entityProgram.uniform("u_Texture"), 0);

            drawCounts = MemoryUtil.memAllocInt(MAX_DRAWS);
            drawBaseVertices = MemoryUtil.memAllocInt(MAX_DRAWS);
            drawOffsets = MemoryUtil.memCallocPointer(MAX_DRAWS);
            matrixBuffer = MemoryUtil.memAllocFloat(16);
            infoBuffer = MemoryUtil.memAllocFloat(CubeTextures.INFO_WIDTH * 4);
        }
        if (cubeTarget.ensureSize(size * 3, size * 2)) {
            CubeTextures.INFO.allocate(CubeTextures.INFO_WIDTH, 1);
            faceSize = size;
            for (int i = 0; i < 6; i++) {
                faceFrame[i] = -1;
            }
            ShadersCompanion.LOGGER.info("Shaders Companion: cube faces of {} pixels ({} x {} atlas)", size, size * 3, size * 2);
        }
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

    private static void setupDrawState(int size) {
        cubeTarget.bind();
        GlStateManager._disableBlend();
        GlStateManager._disableCull();
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_LESS);
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._enableScissorTest();
        Gl.activeTexture(GL13C.GL_TEXTURE0);
    }

    /** Collects the local player in first person for this frame's mirror view (boxes relative to the camera) and uploads it. */
    private static void collectLocalPlayer(Minecraft mc, Camera camera, float partialTick, BoxTest mirrorSeen) {
        playerBatches = List.of();
        mirrorPlayerDrawn = false;
        playerEntities.begin();
        if (mirrorSeen == BoxTest.NONE || !Config.ENTITIES.get()) {
            return;
        }
        playerEntities.collectLocalPlayer(mc, camera, partialTick, mirrorSeen);
        mirrorPlayerDrawn = playerEntities.mirrorPlayerDrawn;
        playerBatches = upload(playerEntities, playerVertexArray, playerBuffer);
    }

    /**
     * Collects the mobs and block entities the given views can see (boxes relative to the camera) and uploads them
     * once; the next frame's views then draw them.
     */
    private static void collectEntities(Minecraft mc, Camera camera, float partialTick, BoxTest seen, RenderRegionManager regions) {
        entityBatches = List.of();
        entitiesDrawn = 0;
        blockEntitiesDrawn = 0;
        entities.begin();
        if (seen == BoxTest.NONE) {
            return;
        }
        if (Config.ENTITIES.get()) {
            entities.collect(mc, camera, partialTick, seen, Config.ENTITY_DISTANCE.get(), Config.MAX_ENTITIES.get(), ENTITY_BUDGET_NANOS);
        }
        if (Config.BLOCK_ENTITIES.get()) {
            entities.collectBlockEntities(mc, camera, partialTick, seen, regions.getLoadedRegions(),
                    Config.BLOCK_ENTITY_DISTANCE.get(), Config.MAX_BLOCK_ENTITIES.get(), BLOCK_ENTITY_BUDGET_NANOS);
        }
        entitiesDrawn = entities.entitiesDrawn;
        blockEntitiesDrawn = entities.blockEntitiesDrawn;
        entityBatches = upload(entities, entityVertexArray, entityBuffer);
    }

    /** Uploads a collection's vertices to its buffer and returns its batches. */
    private static List<EntityCapture.Batch> upload(EntityCapture capture, int vertexArray, int buffer) {
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
        GlStateManager._glBindVertexArray(vertexArray);
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        ensureIndexCapacity(maxQuads);
        return batches;
    }

    /** Collects Distant Horizons' LOD buffers for this frame; returns the LOD range in blocks, 0 when there are none. */
    private static float collectLods(Minecraft mc) {
        lodsCollected = false;
        lodContainers = 0;
        if (!Config.DISTANT_HORIZONS.get() || !Lods.available()) {
            return 0.0F;
        }
        try {
            lodsCollected = Lods.collect(mc.level);
            if (!lodsCollected) {
                return 0.0F;
            }
            int range = Config.LOD_DISTANCE.get() > 0 ? Config.LOD_DISTANCE.get() : Lods.range();
            return Math.max(range, 0);
        } catch (Throwable t) {
            Lods.fail(t);
            lodsCollected = false;
            return 0.0F;
        }
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

    /** Draws Distant Horizons' LODs beyond the chunk terrain's range that a view can see (boxes relative to the camera). */
    private static void drawLods(BoxTest seen, Vec3 camera, Vec3 origin, float maxDistance, float lodDistance, boolean opaque) {
        if (!lodsCollected) {
            return;
        }
        use(lodProgram);
        GL20C.glUniform1i(uLodTranslucent, opaque ? 0 : 1);
        // The chunk terrain covers the render distance; LODs fill in from a little inside its edge.
        float clipDistance = Math.max(0.0F, maxDistance - 16.0F);
        GL20C.glUniform1f(uLodClipDistance, clipDistance);
        GlStateManager._glBindVertexArray(lodVertexArray);
        try {
            int worldHeight = Minecraft.getInstance().level == null ? 384 : Minecraft.getInstance().level.getHeight();
            drawCalls += Lods.draw(seen, opaque, camera.x, camera.y, camera.z, origin.x, origin.y, origin.z, (double) lodDistance * lodDistance,
                    clipDistance, worldHeight, uLodModelOffset);
            lodContainers = Lods.containersDrawn();
        } catch (Throwable t) {
            Lods.fail(t);
            lodsCollected = false;
        }
    }

    private static void drawFace(int face, int size, Vec3 camera, float maxDistance, float lodDistance, float far, RenderRegionManager regions) {
        int x = (face % 3) * size;
        int y = (face / 3) * size;
        GlStateManager._viewport(x, y, size, size);
        GlStateManager._scissorBox(x, y, size, size);
        cubeTarget.clear(0.0F);

        int[] f = FORWARD[face];
        int[] u = UP[face];
        Matrix4f viewProjection = new Matrix4f()
                .setPerspective((float) Math.toRadians(90.0), 1.0F, NEAR, far)
                .lookAt(0.0F, 0.0F, 0.0F, f[0], f[1], f[2], u[0], u[1], u[2]);
        viewProjection.get(matrixBuffer);
        double maxDistanceSq = (double) maxDistance * maxDistance;
        BoxTest seen = (minX, minY, minZ, maxX, maxY, maxZ) -> boxVisible(face, minX, minY, minZ, maxX, maxY, maxZ, maxDistanceSq);
        BoxTest lodSeen = (minX, minY, minZ, maxX, maxY, maxZ) -> boxInFace(face, minX, minY, minZ, maxX, maxY, maxZ);
        drawView(seen, lodSeen, camera, camera, NO_CLIP, NO_OFFSET, maxDistance, lodDistance, regions, false);
    }

    /**
     * Draws the mirror view: the same surfaces as a cube face, from the camera mirrored in this frame's plane, with
     * everything on the far side of the plane cut away (see {@link MirrorView}), in the part of the view the plane covers.
     */
    private static void drawMirror(Vec3 camera, float maxDistance, float lodDistance, RenderRegionManager regions) {
        int width = MirrorView.width;
        int height = MirrorView.height;
        if (mirrorTarget.ensureSize(width, height)) {
            ShadersCompanion.LOGGER.info("Shaders Companion: mirror view of {} x {} pixels", width, height);
        }
        mirrorTarget.bind();
        GlStateManager._viewport(0, 0, width, height);
        GlStateManager._scissorBox(0, 0, width, height);
        // Outside the part of the screen the mirror covers, the distance says "not drawn" (-1); inside it, 0 is open sky
        mirrorTarget.clear(-1.0F);
        int[] drawn = MirrorView.drawnPixels();
        GlStateManager._scissorBox(drawn[0], drawn[1], drawn[2], drawn[3]);
        GL30C.glClearBufferfv(GL11C.GL_COLOR, ViewProgram.DISTANCE_OUTPUT, new float[]{0.0F, 0.0F, 0.0F, 0.0F});

        MirrorView.drawMatrix().get(matrixBuffer);
        Vec3 origin = MirrorView.originWorld();
        float[] cameraOffset = {(float) (camera.x - origin.x), (float) (camera.y - origin.y), (float) (camera.z - origin.z)};
        drawView(MirrorView.seen(), MirrorView.frustumSeen(), camera, origin, MirrorView.clipPlane(), cameraOffset, maxDistance, lodDistance, regions, true);
    }

    /**
     * Draws a view whose projection is in matrixBuffer: opaque surfaces first (blocks, entities, far terrain), then
     * translucent ones (water, glass) over them with the depth test, so each texel keeps the nearest surface.
     *
     * @param seen         the chunk sections and entities the view can see (boxes relative to the camera)
     * @param lodSeen      the same for Distant Horizons' far terrain, without the terrain range
     * @param origin       the view's origin: the camera for the cube, the mirrored camera for the mirror view
     * @param clipPlane    the view's cut plane ({@link #NO_CLIP} for none)
     * @param cameraOffset the camera relative to the view's origin
     * @param mirror       whether this is the mirror view, which also draws the local player
     */
    private static void drawView(BoxTest seen, BoxTest lodSeen, Vec3 camera, Vec3 origin, float[] clipPlane, float[] cameraOffset,
                                 float maxDistance, float lodDistance, RenderRegionManager regions, boolean mirror) {
        viewClipPlane = clipPlane;
        viewCamera = cameraOffset;
        try {
            drawTerrain(seen, camera, origin, regions, false);
            drawEntities(false, mirror);
            drawLods(lodSeen, camera, origin, maxDistance, lodDistance, true);
            drawTerrain(seen, camera, origin, regions, true);
            drawEntities(true, mirror);
            drawLods(lodSeen, camera, origin, maxDistance, lodDistance, false);
        } finally {
            viewClipPlane = NO_CLIP;
            viewCamera = NO_OFFSET;
        }
    }

    /** Binds a program with the current view's projection, depth scale, cut plane and camera offset. */
    private static void use(ViewProgram viewProgram) {
        viewProgram.use(matrixBuffer, logDepth, NEAR, viewClipPlane, viewCamera);
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

    /**
     * Embeddium builds only the sections its main view or Oculus's shadow pass has seen, so terrain behind the player
     * may have no mesh. Queues the unbuilt sections within the cube's range for building, the way Embeddium's own
     * visibility pass does (same queues, same size limits); this frame's chunk update then submits them.
     */
    private static void requestBuilds(RenderSectionManager sections, RenderRegionManager regions, Vec3 camera, float maxDistance)
            throws IllegalAccessException {
        Map<ChunkUpdateType, ArrayDeque<RenderSection>> queues = EmbeddiumAccess.rebuildLists(sections);
        if (queues == null) {
            return;
        }
        double maxDistanceSq = (double) maxDistance * maxDistance;
        int queued = 0;
        for (RenderRegion region : regions.getLoadedRegions()) {
            double rx = region.getOriginX() - camera.x;
            double ry = region.getOriginY() - camera.y;
            double rz = region.getOriginZ() - camera.z;
            if (nearestDistanceSq(rx, ry, rz, rx + 128.0, ry + 64.0, rz + 128.0) > maxDistanceSq) {
                continue;
            }
            for (int index = 0; index < RenderRegion.REGION_SIZE; index++) {
                RenderSection section = region.getSection(index);
                if (section == null || section.isDisposed() || section.getBuildCancellationToken() != null) {
                    continue;
                }
                ChunkUpdateType type = section.getPendingUpdate();
                if (type != ChunkUpdateType.INITIAL_BUILD && type != ChunkUpdateType.REBUILD) {
                    continue;
                }
                double sx = (section.getChunkX() << 4) - camera.x;
                double sy = (section.getChunkY() << 4) - camera.y;
                double sz = (section.getChunkZ() << 4) - camera.z;
                if (nearestDistanceSq(sx, sy, sz, sx + 16.0, sy + 16.0, sz + 16.0) > maxDistanceSq) {
                    continue;
                }
                ArrayDeque<RenderSection> queue = queues.get(type);
                if (queue != null && queue.size() < type.getMaximumQueueSize() && !queue.contains(section)) {
                    queue.add(section);
                    queued++;
                }
            }
        }
        sectionsQueued = queued;
    }

    /** Squared horizontal distance from the origin to a box: the range is a column, like Embeddium's render distance. */
    static double nearestDistanceSq(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.max(minX, -maxX), 0.0);
        double dz = Math.max(Math.max(minZ, -maxZ), 0.0);
        return dx * dx + dz * dz;
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

    /** Whether a box (relative to the capture origin) can be seen by a cube face: inside its four side planes and within range. */
    static boolean boxVisible(int face, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double maxDistanceSq) {
        return nearestDistanceSq(minX, minY, minZ, maxX, maxY, maxZ) <= maxDistanceSq && boxInFace(face, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Whether a box (relative to the capture origin) is inside a cube face's four side planes. */
    static boolean boxInFace(int face, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int[] f = FORWARD[face];
        int[] r = RIGHT[face];
        int[] u = UP[face];
        return inside(f[0] - r[0], f[1] - r[1], f[2] - r[2], minX, minY, minZ, maxX, maxY, maxZ)
                && inside(f[0] + r[0], f[1] + r[1], f[2] + r[2], minX, minY, minZ, maxX, maxY, maxZ)
                && inside(f[0] - u[0], f[1] - u[1], f[2] - u[2], minX, minY, minZ, maxX, maxY, maxZ)
                && inside(f[0] + u[0], f[1] + u[1], f[2] + u[2], minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean inside(int nx, int ny, int nz, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double px = nx >= 0 ? maxX : minX;
        double py = ny >= 0 ? maxY : minY;
        double pz = nz >= 0 ? maxZ : minZ;
        return nx * px + ny * py + nz * pz >= 0.0;
    }

    /** The quad facings of a section that can face the capture origin (the same rule Embeddium uses for the main view). */
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

    /** Writes the info texture: header, each face's capture origin relative to this frame's camera, and the projection. */
    private static void writeInfo(Vec3 camera) {
        infoBuffer.clear();
        infoBuffer.put(CubeTextures.MAGIC).put(CubeTextures.PROTOCOL).put(faceSize).put(frameCounter % 4096);
        for (int face = 0; face < 6; face++) {
            if (faceFrame[face] < 0) {
                infoBuffer.put(0.0F).put(0.0F).put(0.0F).put(-1.0F);
            } else {
                infoBuffer.put((float) (faceOrigin[face][0] - camera.x))
                        .put((float) (faceOrigin[face][1] - camera.y))
                        .put((float) (faceOrigin[face][2] - camera.z))
                        .put(frameCounter - 1 - faceFrame[face]);
            }
        }
        infoBuffer.put(NEAR).put(lastFar).put(lastMaxDistance).put(lodsCollected ? (float) lodRange : 0.0F);
        infoBuffer.flip();
        CubeTextures.INFO.writeRow(infoBuffer, CubeTextures.INFO_WIDTH);
    }
}
