package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.system.MemoryUtil;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;

/**
 * Collects the entities near the camera as surface geometry for the cube and the mirror view. Each entity is drawn through
 * Minecraft's own {@link EntityRenderDispatcher} into this buffer source, which keeps each render type's texture and
 * stores the vertices in the cube's entity layout instead of drawing them. Render types without a texture or with
 * another vertex layout (name tags, leads, enchantment glint, shadows) are dropped. Oculus only wraps its own buffer
 * sources and Embeddium falls back to the plain vertex calls for consumers it doesn't know, so neither changes this.
 */
final class EntityCapture implements MultiBufferSource {
    /** Bytes per vertex: position float3, colour ubyte4, texture float2, light ushort2, normal byte4, id ushort2. */
    static final int STRIDE = 36;
    static final int KIND_OPAQUE = 0;
    static final int KIND_TRANSLUCENT = 1;
    static final int KIND_EMISSIVE = 2;
    /** The data texture's id field for entities: this bit plus entity.properties id + 1 (0 = not listed). */
    static final int ENTITY_ID_BIT = 0x8000;

    private static final Class<?> COMPOSITE_TYPE = compositeType();
    private static final Field STATE = field(COMPOSITE_TYPE, "f_110511_");
    private static final Field TEXTURE_STATE = field(RenderType.CompositeState.class, "f_110576_");
    private static final Field NAME = field(RenderStateShard.class, "f_110133_");
    private static final Field SHOULD_RENDER_SHADOW = field(EntityRenderDispatcher.class, "f_114368_");
    private static final Method CUTOUT_TEXTURE = method(RenderStateShard.EmptyTextureStateShard.class, "m_142706_");
    private static final TypeInfo SKIP = new TypeInfo(null, -1);
    private static final String[] SKIPPED_NAMES = {"glint", "shadow", "crumbling", "outline", "lightning", "energy_swirl", "leash", "water_mask"};

    record TypeInfo(ResourceLocation texture, int kind) {
    }

    /**
     * A run of vertices sharing a texture and kind, at {@code firstVertex} in the uploaded buffer. Mirror-only batches
     * hold the local player in first person, which only the mirror view draws.
     */
    record Batch(ResourceLocation texture, int kind, int firstVertex, int vertexCount, boolean mirrorOnly) {
    }

    private final Map<RenderType, TypeInfo> types = new IdentityHashMap<>();
    private final Map<RenderType, Sink> sinks = new IdentityHashMap<>();
    private final List<Sink> sinkList = new ArrayList<>();
    private final Map<Object, Integer> failures = new HashMap<>();
    private final List<Entity> candidates = new ArrayList<>();
    private final List<BlockEntity> blockCandidates = new ArrayList<>();
    private final VertexConsumer discard = new Sink(SKIP);
    private int currentId;
    private int currentLight;

    int entitiesDrawn;
    int blockEntitiesDrawn;
    boolean mirrorPlayerDrawn;

    /**
     * Draws the local player in first person into the sinks, for the mirror view only ({@code mirrorSeen}, boxes
     * relative to the camera): the cube's camera sits inside its head. The game doesn't draw that player itself, so
     * this can run before the world is drawn and show this frame's pose.
     */
    void collectLocalPlayer(Minecraft mc, Camera camera, float partialTick, BoxTest mirrorSeen) {
        this.mirrorPlayerDrawn = false;
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        Entity entity = camera.getEntity();
        if (mc.level == null || dispatcher.camera == null || entity == null || camera.isDetached() || entity.isInvisible()
                || this.failures.getOrDefault(entity.getType(), 0) >= 3) {
            return;
        }
        Vec3 origin = camera.getPosition();
        AABB box = entity.getBoundingBoxForCulling().move(-origin.x, -origin.y, -origin.z).inflate(0.5);
        if (!mirrorSeen.visible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
            return;
        }
        this.candidates.clear();
        this.candidates.add(entity);
        this.drawCandidates(dispatcher, origin, partialTick, 1, Long.MAX_VALUE);
        this.mirrorPlayerDrawn = this.entitiesDrawn > 0;
        this.entitiesDrawn = 0;
        this.markMirrorOnly();
    }

    /**
     * Draws the entities within range that a view can see ({@code seen}, boxes relative to the camera) into the sinks,
     * nearest first, leaving out the local player in first person. Meant to run after the world is drawn, for the
     * next frame's views: renderers that keep animation state per frame (GeckoLib's share one clock per model) then
     * see the game's own draw first, as without this mod.
     */
    void collect(Minecraft mc, Camera camera, float partialTick, BoxTest seen, double maxDistance, int maxCount, long budgetNanos) {
        this.entitiesDrawn = 0;
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        if (mc.level == null || maxCount <= 0 || dispatcher.camera == null) {
            return;
        }
        Vec3 origin = camera.getPosition();
        Entity cameraEntity = camera.getEntity();
        double maxDistanceSq = maxDistance * maxDistance;
        this.candidates.clear();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.isInvisible() || this.failures.getOrDefault(entity.getType(), 0) >= 3) {
                continue;
            }
            if (entity == cameraEntity && !camera.isDetached()) {
                continue;
            }
            double x = Mth.lerp(partialTick, entity.xOld, entity.getX()) - origin.x;
            double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) - origin.y;
            double z = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - origin.z;
            if (x * x + y * y + z * z > maxDistanceSq) {
                continue;
            }
            AABB box = entity.getBoundingBoxForCulling().move(-origin.x, -origin.y, -origin.z).inflate(0.5);
            if (seen.visible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
                this.candidates.add(entity);
            }
        }
        if (this.candidates.isEmpty()) {
            return;
        }
        this.candidates.sort((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)));
        this.drawCandidates(dispatcher, origin, partialTick, maxCount, budgetNanos);
    }

    /** Draws the first candidates into the sinks, relative to {@code origin}, until the count or the time budget runs out. */
    private void drawCandidates(EntityRenderDispatcher dispatcher, Vec3 origin, float partialTick, int maxCount, long budgetNanos) {
        Object2IntFunction<NamespacedId> entityIds = WorldRenderingSettings.INSTANCE.getEntityIds();
        boolean shadows = readShadowSetting(dispatcher);
        dispatcher.setRenderShadow(false);
        long start = System.nanoTime();
        try {
            PoseStack poseStack = new PoseStack();
            int count = Math.min(maxCount, this.candidates.size());
            for (int i = 0; i < count && System.nanoTime() - start < budgetNanos; i++) {
                Entity entity = this.candidates.get(i);
                double x = Mth.lerp(partialTick, entity.xOld, entity.getX()) - origin.x;
                double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) - origin.y;
                double z = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - origin.z;
                float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
                this.currentId = ENTITY_ID_BIT | idOf(entityIds, entity);
                int[] marks = this.markSinks();
                try {
                    this.currentLight = dispatcher.getPackedLightCoords(entity, partialTick);
                    dispatcher.render(entity, x, y, z, yaw, partialTick, poseStack, this, this.currentLight);
                    this.entitiesDrawn++;
                } catch (Throwable t) {
                    this.rollBack(marks);
                    int failed = this.failures.merge(entity.getType(), 1, Integer::sum);
                    if (failed == 1 || failed == 3) {
                        ShadersCompanion.LOGGER.warn("Shaders Companion: drawing {} into the cube failed{}", EntityType.getKey(entity.getType()),
                                failed == 3 ? "; leaving it out from now on" : "", t);
                    }
                    poseStack = new PoseStack();
                }
            }
        } finally {
            dispatcher.setRenderShadow(shadows);
        }
    }

    /** Marks every vertex collected so far (the local player's) as mirror-only. */
    private void markMirrorOnly() {
        for (Sink sink : this.sinkList) {
            sink.mirrorOnly = sink.vertices - sink.vertices % 4;
        }
    }

    /** Starts a collection: empties every sink. */
    void begin() {
        for (Sink sink : this.sinkList) {
            sink.vertices = 0;
            sink.mirrorOnly = 0;
        }
        this.entitiesDrawn = 0;
        this.blockEntitiesDrawn = 0;
        this.mirrorPlayerDrawn = false;
    }

    /**
     * Draws the block entities (chests, signs, modded machines) of Embeddium's sections within range that a view can
     * see ({@code seen}, boxes relative to the camera), nearest first, until the count or the time budget runs out.
     */
    void collectBlockEntities(Minecraft mc, Camera camera, float partialTick, BoxTest seen, Iterable<RenderRegion> regions,
                              double maxDistance, int maxCount, long budgetNanos) {
        BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
        if (mc.level == null || maxCount <= 0 || dispatcher.camera == null) {
            return;
        }
        Vec3 origin = camera.getPosition();
        double maxDistanceSq = maxDistance * maxDistance;
        this.blockCandidates.clear();
        for (RenderRegion region : regions) {
            double rx = region.getOriginX() - origin.x;
            double ry = region.getOriginY() - origin.y;
            double rz = region.getOriginZ() - origin.z;
            if (CubeView.nearestDistanceSq(rx, ry, rz, rx + 128.0, ry + 64.0, rz + 128.0) > maxDistanceSq
                    || !seen.visible(rx, ry, rz, rx + 128.0, ry + 64.0, rz + 128.0)) {
                continue;
            }
            for (int index = 0; index < RenderRegion.REGION_SIZE; index++) {
                RenderSection section = region.getSection(index);
                if (section == null) {
                    continue;
                }
                this.addBlockEntities(section.getCulledBlockEntities(), origin, seen, maxDistanceSq);
                this.addBlockEntities(section.getGlobalBlockEntities(), origin, seen, maxDistanceSq);
            }
        }
        if (this.blockCandidates.isEmpty()) {
            return;
        }
        this.blockCandidates.sort((a, b) -> Double.compare(a.getBlockPos().distToCenterSqr(origin), b.getBlockPos().distToCenterSqr(origin)));
        long start = System.nanoTime();
        PoseStack poseStack = new PoseStack();
        int count = Math.min(maxCount, this.blockCandidates.size());
        for (int i = 0; i < count && System.nanoTime() - start < budgetNanos; i++) {
            BlockEntity blockEntity = this.blockCandidates.get(i);
            this.currentId = ENTITY_ID_BIT;
            this.currentLight = 0xF000F0;
            int[] marks = this.markSinks();
            try {
                poseStack.pushPose();
                poseStack.translate(blockEntity.getBlockPos().getX() - origin.x, blockEntity.getBlockPos().getY() - origin.y,
                        blockEntity.getBlockPos().getZ() - origin.z);
                dispatcher.render(blockEntity, partialTick, poseStack, this);
                poseStack.popPose();
                this.blockEntitiesDrawn++;
            } catch (Throwable t) {
                this.rollBack(marks);
                BlockEntityType<?> type = blockEntity.getType();
                int failed = this.failures.merge(type, 1, Integer::sum);
                if (failed == 1 || failed == 3) {
                    ShadersCompanion.LOGGER.warn("Shaders Companion: drawing block entity {} into the cube failed{}", BlockEntityType.getKey(type),
                            failed == 3 ? "; leaving it out from now on" : "", t);
                }
                poseStack = new PoseStack();
            }
        }
    }

    private void addBlockEntities(BlockEntity[] blockEntities, Vec3 origin, BoxTest seen, double maxDistanceSq) {
        if (blockEntities == null) {
            return;
        }
        for (BlockEntity blockEntity : blockEntities) {
            if (blockEntity == null || blockEntity.isRemoved() || this.failures.getOrDefault(blockEntity.getType(), 0) >= 3) {
                continue;
            }
            double x = blockEntity.getBlockPos().getX() - origin.x;
            double y = blockEntity.getBlockPos().getY() - origin.y;
            double z = blockEntity.getBlockPos().getZ() - origin.z;
            if ((x + 0.5) * (x + 0.5) + (y + 0.5) * (y + 0.5) + (z + 0.5) * (z + 0.5) > maxDistanceSq) {
                continue;
            }
            if (seen.visible(x - 1.0, y - 1.0, z - 1.0, x + 2.0, y + 2.0, z + 2.0)) {
                this.blockCandidates.add(blockEntity);
            }
        }
    }

    /** Vertex counts of every sink, so a failed entity's partial geometry can be dropped. */
    private int[] markSinks() {
        int[] marks = new int[this.sinkList.size()];
        for (int i = 0; i < marks.length; i++) {
            marks[i] = this.sinkList.get(i).vertices;
        }
        return marks;
    }

    private void rollBack(int[] marks) {
        for (int i = 0; i < this.sinkList.size(); i++) {
            Sink sink = this.sinkList.get(i);
            // Sinks created during the failed entity come after the marked ones and start empty.
            sink.vertices = i < marks.length ? marks[i] : 0;
            sink.mirrorOnly = Math.min(sink.mirrorOnly, sink.vertices);
            sink.discardPartialVertex();
        }
    }

    /** Total vertices collected this frame. */
    int vertexCount() {
        int total = 0;
        for (Sink sink : this.sinkList) {
            total += sink.vertices - sink.vertices % 4;
        }
        return total;
    }

    /**
     * Copies the collected vertices to {@code target} (at least {@link #vertexCount()} * STRIDE bytes) and returns the
     * batches, opaque ones first; each sink's mirror-only vertices (its first ones) form a batch of their own.
     */
    List<Batch> write(ByteBuffer target) {
        List<Batch> batches = new ArrayList<>();
        int vertex = 0;
        for (int kind = 0; kind <= KIND_EMISSIVE; kind++) {
            for (Sink sink : this.sinkList) {
                int count = sink.vertices - sink.vertices % 4;
                if (count == 0 || sink.info.kind != kind) {
                    continue;
                }
                MemoryUtil.memCopy(MemoryUtil.memAddress(sink.data), MemoryUtil.memAddress(target) + (long) vertex * STRIDE, (long) count * STRIDE);
                int mirrorOnly = Math.min(sink.mirrorOnly, count);
                if (mirrorOnly > 0) {
                    batches.add(new Batch(sink.info.texture, kind, vertex, mirrorOnly, true));
                }
                if (count > mirrorOnly) {
                    batches.add(new Batch(sink.info.texture, kind, vertex + mirrorOnly, count - mirrorOnly, false));
                }
                vertex += count;
            }
        }
        return batches;
    }

    void free() {
        for (Sink sink : this.sinkList) {
            MemoryUtil.memFree(sink.data);
        }
        this.sinks.clear();
        this.sinkList.clear();
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        Sink sink = this.sinks.get(type);
        if (sink != null) {
            return sink;
        }
        TypeInfo info = this.types.computeIfAbsent(type, EntityCapture::resolve);
        if (info == SKIP) {
            return this.discard;
        }
        sink = new Sink(info);
        this.sinks.put(type, sink);
        this.sinkList.add(sink);
        return sink;
    }

    private static int idOf(Object2IntFunction<NamespacedId> entityIds, Entity entity) {
        if (entityIds == null) {
            return 0;
        }
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        int id = entityIds.applyAsInt(new NamespacedId(key.getNamespace(), key.getPath()));
        return id < 0 ? 0 : Math.min(id + 1, 0x3FFF);
    }

    /** What a render type draws: its texture and whether it is opaque, translucent or glowing; SKIP when the cube can't use it. */
    private static TypeInfo resolve(RenderType type) {
        try {
            if (type.format() != DefaultVertexFormat.NEW_ENTITY || type.mode() != VertexFormat.Mode.QUADS
                    || COMPOSITE_TYPE == null || !COMPOSITE_TYPE.isInstance(type)) {
                return SKIP;
            }
            String name = String.valueOf(NAME.get(type));
            for (String skipped : SKIPPED_NAMES) {
                if (name.contains(skipped)) {
                    return SKIP;
                }
            }
            Object textureState = TEXTURE_STATE.get(STATE.get(type));
            Object texture = textureState == null ? null : CUTOUT_TEXTURE.invoke(textureState);
            if (!(texture instanceof Optional<?> optional) || optional.isEmpty() || !(optional.get() instanceof ResourceLocation location)) {
                return SKIP;
            }
            int kind = name.contains("eyes") || name.contains("emissive") ? KIND_EMISSIVE
                    : name.contains("translucent") ? KIND_TRANSLUCENT : KIND_OPAQUE;
            return new TypeInfo(location, kind);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return SKIP;
        }
    }

    private static boolean readShadowSetting(EntityRenderDispatcher dispatcher) {
        try {
            return SHOULD_RENDER_SHADOW == null || SHOULD_RENDER_SHADOW.getBoolean(dispatcher);
        } catch (IllegalAccessException e) {
            return true;
        }
    }

    private static Class<?> compositeType() {
        try {
            return Class.forName("net.minecraft.client.renderer.RenderType$CompositeRenderType");
        } catch (ClassNotFoundException e) {
            ShadersCompanion.LOGGER.warn("Shaders Companion: RenderType$CompositeRenderType not found; entities won't be drawn");
            return null;
        }
    }

    private static Field field(Class<?> owner, String srgName) {
        if (owner == null) {
            return null;
        }
        try {
            return ObfuscationReflectionHelper.findField(owner, srgName);
        } catch (RuntimeException e) {
            ShadersCompanion.LOGGER.warn("Shaders Companion: field {} not found in {}", srgName, owner.getName());
            return null;
        }
    }

    private static Method method(Class<?> owner, String srgName) {
        try {
            return ObfuscationReflectionHelper.findMethod(owner, srgName);
        } catch (RuntimeException e) {
            ShadersCompanion.LOGGER.warn("Shaders Companion: method {} not found in {}", srgName, owner.getName());
            return null;
        }
    }

    /** Stores one render type's vertices in the cube's entity layout. */
    private final class Sink implements VertexConsumer {
        final TypeInfo info;
        ByteBuffer data;
        int vertices;
        /** Vertices at the start that only the mirror view draws. */
        int mirrorOnly;
        private float x;
        private float y;
        private float z;
        private float u;
        private float v;
        private int color = -1;
        private int light;
        private float nx;
        private float ny = 1.0F;
        private float nz;
        private boolean defaultColorSet;
        private int defaultColor = -1;

        Sink(TypeInfo info) {
            this.info = info;
            this.data = info == SKIP ? null : MemoryUtil.memAlloc(STRIDE * 1024);
        }

        void discardPartialVertex() {
            this.color = this.defaultColorSet ? this.defaultColor : -1;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.x = (float) x;
            this.y = (float) y;
            this.z = (float) z;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            this.color = (r & 255) | (g & 255) << 8 | (b & 255) << 16 | (a & 255) << 24;
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            this.light = (u & 0xFFFF) | (v & 0xFFFF) << 16;
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            this.nx = x;
            this.ny = y;
            this.nz = z;
            return this;
        }

        @Override
        public void endVertex() {
            if (this.data == null) {
                return;
            }
            if ((this.vertices + 1) * STRIDE > this.data.capacity()) {
                this.data = MemoryUtil.memRealloc(this.data, this.data.capacity() * 2);
            }
            long address = MemoryUtil.memAddress(this.data) + (long) this.vertices * STRIDE;
            MemoryUtil.memPutFloat(address, this.x);
            MemoryUtil.memPutFloat(address + 4, this.y);
            MemoryUtil.memPutFloat(address + 8, this.z);
            MemoryUtil.memPutInt(address + 12, this.color);
            MemoryUtil.memPutFloat(address + 16, this.u);
            MemoryUtil.memPutFloat(address + 20, this.v);
            int lightValue = this.info.kind == KIND_EMISSIVE ? (240 | 240 << 16) : this.light;
            MemoryUtil.memPutShort(address + 24, (short) (lightValue & 0xFFFF));
            MemoryUtil.memPutShort(address + 26, (short) (lightValue >>> 16));
            MemoryUtil.memPutByte(address + 28, normalByte(this.nx));
            MemoryUtil.memPutByte(address + 29, normalByte(this.ny));
            MemoryUtil.memPutByte(address + 30, normalByte(this.nz));
            MemoryUtil.memPutByte(address + 31, (byte) 0);
            MemoryUtil.memPutShort(address + 32, (short) EntityCapture.this.currentId);
            MemoryUtil.memPutShort(address + 34, (short) (this.info.kind == KIND_EMISSIVE ? 1 : 0));
            this.vertices++;
            this.color = this.defaultColorSet ? this.defaultColor : -1;
            this.light = EntityCapture.this.currentLight;
        }

        private static byte normalByte(float value) {
            return (byte) Math.round(Mth.clamp(value, -1.0F, 1.0F) * 127.0F);
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            this.defaultColorSet = true;
            this.defaultColor = (r & 255) | (g & 255) << 8 | (b & 255) << 16 | (a & 255) << 24;
            this.color = this.defaultColor;
        }

        @Override
        public void unsetDefaultColor() {
            this.defaultColorSet = false;
            this.defaultColor = -1;
        }
    }
}
