package com.rinkynooble.shaderscompanion.verify;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLBuffer;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Dev-only (the far scene, -PdevDh): Distant Horizons 3.3.2's LOD sections as its quad tree holds them, for the M0
 * baseline. Touches Distant Horizons' classes, so only called when it is loaded (DevRun.dhLoaded guards the callers).
 * <ul>
 * <li>"enabled": the quad tree's enabled render sections (what its renderer walks every frame, before culling);
 * "uploaded": those whose buffers are on the GPU (what the main view and the mod's views can draw);</li>
 * <li>"geometry": the bytes of those sections' vertex buffers (GLBuffer sizes, opaque and transparent; the quad index
 * buffer is shared and left out); "all nodes": the same over every node of the tree that holds buffers, enabled or
 * not (parents and children kept for detail changes);</li>
 * <li>"with geometry": uploaded sections whose buffers hold anything (Distant Horizons keeps empty ones for terrain it
 * has not generated yet);</li>
 * <li>"ahead": along a direction from the camera, how far uploaded sections with geometry cover the line without a gap
 * (in steps of 8 blocks), and the farthest point on the line any of them covers.</li>
 * </ul>
 */
final class DhProbe {
    private static final ArrayList<LodRenderSection> SECTIONS = new ArrayList<>();

    private DhProbe() {
    }

    /** Distant Horizons' own rendering on or off, through its API (not saved; Oculus reloads the pack when it changes). */
    static void setRendering(boolean on) {
        DhApi.Delayed.configs.graphics().renderingEnabled().setValue(on);
    }

    static boolean rendering() {
        return DhApi.Delayed.configs.graphics().renderingEnabled().getValue();
    }

    private static RenderBufferHandler handler() {
        IMinecraftClientWrapper client = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
        IClientLevelWrapper wrapper = client == null ? null : client.getWrappedClientLevel();
        IDhLevel level = wrapper == null ? null : wrapper.getDhLevel();
        return level == null ? null : level.getRenderBufferHandler();
    }

    /** Distant Horizons' generation of unvisited terrain on or off, through its API (not saved). */
    static void setGeneration(boolean on) {
        DhApi.Delayed.configs.worldGenerator().enableDistantWorldGeneration().setValue(on);
    }

    /** Back to Distant Horizons' own setting for its generation. */
    static void clearGeneration() {
        DhApi.Delayed.configs.worldGenerator().enableDistantWorldGeneration().clearValue();
    }

    /** Back to Distant Horizons' own setting for its rendering. */
    static void clearRendering() {
        DhApi.Delayed.configs.graphics().renderingEnabled().clearValue();
    }

    /** Distant Horizons' LOD range in blocks. */
    static int range() {
        Integer chunks = com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get();
        return chunks == null ? 0 : chunks * 16;
    }

    /** The uploaded enabled sections' count and their vertex buffers' bytes (cheap enough for once a second). */
    static long[] counts() {
        RenderBufferHandler handler = handler();
        if (handler == null || handler.lodQuadTree == null) {
            return new long[]{0, 0};
        }
        handler.lodQuadTree.populateListWithEnabledRenderSections(SECTIONS);
        long uploaded = 0;
        long bytes = 0;
        for (LodRenderSection section : SECTIONS) {
            LodBufferContainer container = section == null ? null : section.renderBufferContainer;
            if (container != null && container.buffersUploaded && section.getRenderingEnabled()) {
                uploaded++;
                bytes += bytes(container.vboOpaqueWrappers) + bytes(container.vboTransparentWrappers);
            }
        }
        SECTIONS.clear();
        return new long[]{uploaded, bytes};
    }

    private static long bytes(IVertexBufferWrapper[] buffers) {
        long sum = 0;
        if (buffers != null) {
            for (IVertexBufferWrapper buffer : buffers) {
                if (buffer instanceof GLBuffer gl) {
                    sum += gl.getSize();
                }
            }
        }
        return sum;
    }

    /**
     * One status line: section counts by detail level, the geometry's bytes, the coverage along (dirX, dirZ) from the
     * camera at (camX, camZ), and Distant Horizons' own debug lines (its F3 text: world generation queue, the main
     * view's drawn/total buffer count).
     */
    static String status(double camX, double camZ, double dirX, double dirZ, boolean withF3) {
        RenderBufferHandler handler = handler();
        if (handler == null || handler.lodQuadTree == null) {
            return "DH probe: no render buffer handler yet";
        }
        handler.lodQuadTree.populateListWithEnabledRenderSections(SECTIONS);
        int enabled = SECTIONS.size();
        int uploaded = 0;
        int withGeometry = 0;
        long opaqueBytes = 0;
        long transparentBytes = 0;
        int[] byDetail = new int[32];
        List<int[]> squares = new ArrayList<>();
        for (LodRenderSection section : SECTIONS) {
            LodBufferContainer container = section == null ? null : section.renderBufferContainer;
            if (container == null || !container.buffersUploaded || !section.getRenderingEnabled()) {
                continue;
            }
            uploaded++;
            byDetail[Math.min(31, Math.max(0, DhSectionPos.getDetailLevel(section.pos)))]++;
            long opaque = bytes(container.vboOpaqueWrappers);
            long transparent = bytes(container.vboTransparentWrappers);
            opaqueBytes += opaque;
            transparentBytes += transparent;
            if (opaque + transparent > 0) {
                // Sections without geometry (terrain not generated yet) don't count as covering
                withGeometry++;
                squares.add(new int[]{DhSectionPos.getMinCornerBlockX(section.pos), DhSectionPos.getMinCornerBlockZ(section.pos), DhSectionPos.getBlockWidth(section.pos)});
            }
        }
        SECTIONS.clear();
        // Every node holding buffers, enabled or not
        long allBytes = 0;
        int allNodes = 0;
        try {
            Iterator<QuadNode<LodRenderSection>> nodes = handler.lodQuadTree.nodeIterator();
            while (nodes.hasNext()) {
                QuadNode<LodRenderSection> node = nodes.next();
                LodRenderSection section = node == null ? null : node.value;
                LodBufferContainer container = section == null ? null : section.renderBufferContainer;
                if (container != null && container.buffersUploaded) {
                    allNodes++;
                    allBytes += bytes(container.vboOpaqueWrappers) + bytes(container.vboTransparentWrappers);
                }
            }
        } catch (RuntimeException e) {
            allNodes = -1;
        }
        // Coverage along the direction: contiguous from the camera, and the farthest covered point
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        double ux = length > 0 ? dirX / length : 1.0;
        double uz = length > 0 ? dirZ / length : 0.0;
        int contiguous = -8;
        int farthest = -8;
        boolean gap = false;
        int reach = range() + 1024;
        for (int t = 0; t <= reach; t += 8) {
            double x = camX + ux * t;
            double z = camZ + uz * t;
            boolean covered = false;
            for (int[] s : squares) {
                if (x >= s[0] && x < s[0] + s[2] && z >= s[1] && z < s[1] + s[2]) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                farthest = t;
                if (!gap) {
                    contiguous = t;
                }
            } else {
                gap = true;
            }
        }
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < byDetail.length; i++) {
            if (byDetail[i] > 0) {
                detail.append(detail.length() == 0 ? "" : " ").append("d").append(i).append(":").append(byDetail[i]);
            }
        }
        StringBuilder line = new StringBuilder(String.format(
                "DH probe: rendering %s, %d enabled sections, %d uploaded (%s), %d with geometry, geometry %.1f MiB (opaque %.1f, transparent %.1f); all nodes with buffers %d, %.1f MiB; ahead: contiguous %d blocks, farthest %d; main view %s",
                rendering() ? "on" : "off", enabled, uploaded, detail, withGeometry, (opaqueBytes + transparentBytes) / 1048576.0, opaqueBytes / 1048576.0,
                transparentBytes / 1048576.0, allNodes, allBytes / 1048576.0, contiguous + 8, farthest + 8, handler.getVboRenderDebugMenuString()));
        if (withF3) {
            List<String> f3 = new ArrayList<>();
            try {
                F3Screen.addStringToDisplay(f3);
            } catch (RuntimeException e) {
                f3.add("F3 failed: " + e);
            }
            line.append(" | DH F3:");
            for (String s : f3) {
                String plain = s.replaceAll("\u00a7.", "").trim();
                if (!plain.isEmpty()) {
                    line.append(" [").append(plain).append("]");
                }
            }
        }
        return line.toString();
    }
}
