package com.rinkynooble.shaderscompanion.client;

/**
 * Whether the active shader pack reads a group of the mod's textures. Oculus asks the texture manager for a pack's
 * custom textures each time it binds them ({@link CompanionTexture#getId()} counts those requests per group), so a
 * group nobody asked for during {@link #IDLE_FRAMES} frames isn't read: its view needn't be drawn. Checked once per
 * frame; the textures are looked at again every frame, so a pack that starts reading a group gets it at once.
 */
final class TextureDemand {
    private static final int IDLE_FRAMES = 30;

    private final int group;
    private long lastRequests = -1;
    private int idleFrames;

    TextureDemand(int group) {
        this.group = group;
    }

    /** Counts this frame; true while the pack has asked for the group's textures within the last IDLE_FRAMES frames. */
    boolean update() {
        long requests = CompanionTexture.requests(this.group);
        if (requests != this.lastRequests) {
            this.lastRequests = requests;
            this.idleFrames = 0;
        } else {
            this.idleFrames++;
        }
        return this.wanted();
    }

    boolean wanted() {
        return this.idleFrames <= IDLE_FRAMES;
    }

    /** Starts over (no shader pack in use): the next request counts as new. */
    void reset() {
        this.lastRequests = -1;
    }
}
