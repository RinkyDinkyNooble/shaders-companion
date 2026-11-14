package com.rinkynooble.shaderscompanion.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * What Minecraft's main buffer source ({@code renderBuffers().bufferSource()}) hands out while the mod draws an entity
 * or block entity into its views: some renderers ignore the buffer source they are given and draw into the main one
 * (TaCZ's gun models do), which would put their geometry into the main view instead of the reflection. Every buffer
 * asked for comes from the capture, and flushing does nothing. Set by {@link #redirect} around each capture.
 */
public final class CaptureBufferSource extends MultiBufferSource.BufferSource {
    private static CaptureBufferSource current;
    private final MultiBufferSource target;

    private CaptureBufferSource(MultiBufferSource target) {
        super(new BufferBuilder(256), Map.of());
        this.target = target;
    }

    /** The stand-in while a capture runs, else null (read by RenderBuffersMixin). */
    public static MultiBufferSource.BufferSource current() {
        return current;
    }

    /** Sends the main buffer source's buffers to {@code target} until the returned redirect is closed. */
    static Redirect redirect(MultiBufferSource target) {
        CaptureBufferSource previous = current;
        current = new CaptureBufferSource(target);
        return () -> current = previous;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return this.target.getBuffer(type);
    }

    @Override
    public void endLastBatch() {
    }

    @Override
    public void endBatch() {
    }

    @Override
    public void endBatch(RenderType type) {
    }

    /** Ends a redirect. */
    interface Redirect extends AutoCloseable {
        @Override
        void close();
    }
}
