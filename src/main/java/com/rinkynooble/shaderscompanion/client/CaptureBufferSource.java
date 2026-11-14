package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * What Minecraft's main buffer source ({@code renderBuffers().bufferSource()}) hands out while the mod draws an entity
 * or block entity into its views: some renderers ignore the buffer source they are given and draw into the main one
 * (TaCZ's gun models do), which would put their geometry into the main view instead of the reflection. Every buffer
 * asked for comes from the capture, and flushing does nothing. Set by {@link #redirect} around each capture.
 * <p>
 * There is one instance for the session: a BufferBuilder's memory is native and Minecraft never frees it, so a new
 * instance per capture would leak. The builder is never written to. A reference kept past the capture (a renderer that
 * cached the main buffer source) acts as the main buffer source again.
 */
public final class CaptureBufferSource extends MultiBufferSource.BufferSource {
    private static CaptureBufferSource instance;
    /** The instance while a capture runs, else null. */
    private static CaptureBufferSource current;
    private MultiBufferSource target;

    private CaptureBufferSource() {
        super(new BufferBuilder(256), Map.of());
    }

    /** The stand-in while a capture runs, else null (read by RenderBuffersMixin). */
    public static MultiBufferSource.BufferSource current() {
        return current;
    }

    /** Sends the main buffer source's buffers to {@code target} until the returned redirect is closed. */
    static Redirect redirect(MultiBufferSource target) {
        if (instance == null) {
            instance = new CaptureBufferSource();
        }
        CaptureBufferSource capture = instance;
        MultiBufferSource previousTarget = capture.target;
        CaptureBufferSource previousCurrent = current;
        capture.target = target;
        current = capture;
        return () -> {
            capture.target = previousTarget;
            current = previousCurrent;
        };
    }

    /** The main buffer source outside a capture (the mixin hands out the real one while none runs). */
    private static MultiBufferSource.BufferSource mainBuffers() {
        return Minecraft.getInstance().renderBuffers().bufferSource();
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        MultiBufferSource capture = this.target;
        return capture != null ? capture.getBuffer(type) : mainBuffers().getBuffer(type);
    }

    @Override
    public void endLastBatch() {
        if (this.target == null) {
            mainBuffers().endLastBatch();
        }
    }

    @Override
    public void endBatch() {
        if (this.target == null) {
            mainBuffers().endBatch();
        }
    }

    @Override
    public void endBatch(RenderType type) {
        if (this.target == null) {
            mainBuffers().endBatch(type);
        }
    }

    /** Ends a redirect. */
    interface Redirect extends AutoCloseable {
        @Override
        void close();
    }
}
