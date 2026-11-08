package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GlQuadIndexBuffer;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import java.util.ArrayList;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * Distant Horizons 3.3 far terrain (LODs) for the cube. Distant Horizons keeps every loaded LOD section's mesh in its
 * own vertex and index buffers; its renderer walks its quad tree's enabled sections and draws them with its program.
 * This draws the same buffers with the cube's LOD program instead, holding the buffers' read locks while drawing, as
 * Distant Horizons' own renderer does. Nothing in Distant Horizons is changed. Only loaded when Distant Horizons is
 * installed ({@link Lods}).
 *
 * <p>Vertices are 16 bytes: position ushort4 (x, y, z in blocks from the section's corner, w = light: low byte sky
 * light high nibble and block light low nibble; high byte the sub-block offset bits), colour ubyte4, and data ubyte4
 * (x = Distant Horizons' block material, y = face: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east).
 */
final class DistantHorizonsLods {
    static final int STRIDE = 16;

    private static final ArrayList<LodRenderSection> SECTIONS = new ArrayList<>();
    private static final ArrayList<LodBufferContainer> CONTAINERS = new ArrayList<>();

    static int containersDrawn;
    /** Why the last collect found nothing, for the status line. */
    static String reason = "";

    private DistantHorizonsLods() {
    }

    /** Distant Horizons' LOD range in blocks. */
    static int range() {
        Integer chunks = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get();
        return chunks == null ? 0 : chunks * 16;
    }

    /** Collects the uploaded LOD buffers of the client level that wraps {@code mcLevel}; false when there are none. */
    static boolean collect(Object mcLevel) {
        CONTAINERS.clear();
        if (mcLevel == null || SharedApi.getAbstractDhWorld() == null) {
            reason = "no world loaded";
            return false;
        }
        // The same lookup Distant Horizons' renderer makes: the client level's wrapper knows its DH level.
        IMinecraftClientWrapper client = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
        IClientLevelWrapper wrapper = client == null ? null : client.getWrappedClientLevel();
        IDhLevel level = wrapper == null ? null : wrapper.getDhLevel();
        RenderBufferHandler handler = level == null ? null : level.getRenderBufferHandler();
        if (handler == null || handler.lodQuadTree == null) {
            reason = wrapper == null ? "no client level" : level == null ? "level not loaded yet" : "renderer not started";
            return false;
        }
        handler.lodQuadTree.populateListWithEnabledRenderSections(SECTIONS);
        int enabled = SECTIONS.size();
        for (LodRenderSection section : SECTIONS) {
            LodBufferContainer container = section == null ? null : section.renderBufferContainer;
            if (container != null && container.buffersUploaded && section.getRenderingEnabled()) {
                CONTAINERS.add(container);
            }
        }
        SECTIONS.clear();
        reason = CONTAINERS.isEmpty() ? "no LODs built yet (" + enabled + " sections)" : "";
        return !CONTAINERS.isEmpty();
    }

    /**
     * Draws the collected LOD buffers that a view can see ({@code seen}, boxes relative to the camera), opaque or
     * transparent ones, with the LOD program bound and its vertex array active. The model offset is relative to the
     * view's origin. Returns the draw calls made.
     */
    static int draw(BoxTest seen, boolean opaque, double cameraX, double cameraY, double cameraZ, double originX, double originY, double originZ,
                    double maxDistanceSq, double clipDistance, int worldHeight, int uModelOffset) {
        double clipDistanceSq = clipDistance * clipDistance;
        int draws = 0;
        for (LodBufferContainer container : CONTAINERS) {
            IVertexBufferWrapper[] buffers = opaque ? container.vboOpaqueWrappers : container.vboTransparentWrappers;
            if (buffers == null || buffers.length == 0) {
                continue;
            }
            double minX = container.minCornerBlockPos.getX() - cameraX;
            double minY = container.minCornerBlockPos.getY() - cameraY;
            double minZ = container.minCornerBlockPos.getZ() - cameraZ;
            double width = DhSectionPos.getBlockWidth(container.pos);
            // Sections entirely inside the chunk terrain's range would be discarded by the LOD program anyway
            double farX = Math.max(Math.abs(minX), Math.abs(minX + width));
            double farZ = Math.max(Math.abs(minZ), Math.abs(minZ + width));
            if (farX * farX + farZ * farZ < clipDistanceSq
                    || CubeView.nearestDistanceSq(minX, minY, minZ, minX + width, minY + worldHeight, minZ + width) > maxDistanceSq
                    || !seen.visible(minX, minY, minZ, minX + width, minY + worldHeight, minZ + width)) {
                continue;
            }
            boolean offsetSet = false;
            for (IVertexBufferWrapper wrapper : buffers) {
                if (!(wrapper instanceof GLVertexBuffer vbo)) {
                    continue;
                }
                GlQuadIndexBuffer ibo = vbo.getQuadIBO();
                if (ibo == null) {
                    continue;
                }
                long vboStamp = vbo.renderStampLock.readLock();
                long iboStamp = ibo.renderStampLock.readLock();
                try {
                    int vertices = vbo.getVertexCount();
                    if (vertices == 0 || vbo.getId() == 0 || ibo.getId() == 0) {
                        continue;
                    }
                    if (!offsetSet) {
                        GL20C.glUniform3f(uModelOffset, (float) (container.minCornerBlockPos.getX() - originX),
                                (float) (container.minCornerBlockPos.getY() - originY), (float) (container.minCornerBlockPos.getZ() - originZ));
                        offsetSet = true;
                    }
                    GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo.getId());
                    GL30C.glVertexAttribIPointer(0, 4, GL11C.GL_UNSIGNED_SHORT, STRIDE, 0L);
                    GL20C.glVertexAttribPointer(1, 4, GL11C.GL_UNSIGNED_BYTE, true, STRIDE, 8L);
                    GL30C.glVertexAttribIPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, STRIDE, 12L);
                    GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibo.getId());
                    GL11C.glDrawElements(GL11C.GL_TRIANGLES, vertices / 4 * 6, ibo.getGlType(), 0L);
                    draws++;
                } finally {
                    ibo.renderStampLock.unlockRead(iboStamp);
                    vbo.renderStampLock.unlockRead(vboStamp);
                }
            }
            if (offsetSet) {
                containersDrawn++;
            }
        }
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, 0);
        return draws;
    }

    static void clear() {
        CONTAINERS.clear();
    }
}
