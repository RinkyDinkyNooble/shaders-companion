package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.far.WorldIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Which singleplayer world a far store belongs to. A store's folder is named after the save's folder, and Minecraft
 * gives a new world the folder name of a deleted one (the user's dev7 test: a new "TEST (1)" opened the far terrain of
 * an earlier, deleted "TEST (1)"). So the save holds an id ({@link WorldIds}, in its data folder, deleted with the
 * world) and the store the same id ({@link #STORE_FILE}); a store with another id is another world's, and is deleted
 * before the store opens. A store from before 3.0.0-dev8 has no id: it is kept, and given the save's, unless its folder
 * is older than the save's folder (made for a world before this one).
 */
final class FarWorldId {
    /** In the store's folder ({@code shaderscompanion/lod/sp-<save>}): the id of the world it belongs to. */
    static final String STORE_FILE = "world-id.txt";
    /** A store folder at least this much older than the save's folder was made for another world (file times are coarse). */
    private static final long OLDER_MILLIS = 5_000;

    private FarWorldId() {
    }

    /**
     * Makes the store's folder this world's (worker thread, before the store opens): deletes a store that belongs to
     * another world, and writes the world's id on both sides. Never throws: a failure is logged and the store opens as it is.
     */
    static void claim(Path lodRoot, Path storeRoot, Path saveRoot) {
        try {
            String worldId = WorldIds.readOrCreate(saveRoot);
            if (Files.isDirectory(storeRoot)) {
                String storeId = WorldIds.readId(storeRoot.resolve(STORE_FILE));
                String reason = null;
                if (storeId != null && !storeId.equals(worldId)) {
                    reason = "it belongs to another world of the same name";
                } else if (storeId == null && olderThan(storeRoot, saveRoot)) {
                    reason = "it was made before this world (for a deleted world of the same name)";
                }
                if (reason != null) {
                    FarFolders.discard(lodRoot, storeRoot, reason + "; starting a new one");
                }
            }
            Files.createDirectories(storeRoot);
            WorldIds.writeId(storeRoot.resolve(STORE_FILE), worldId, "Shaders Companion: the id of the world this far terrain store belongs to (in the save: data/"
                    + WorldIds.SAVE_FILE + ").");
        } catch (IOException | RuntimeException e) {
            ShadersCompanion.LOGGER.warn("Shaders Companion far store: couldn't check which world {} belongs to", storeRoot, e);
        }
    }

    private static boolean olderThan(Path storeRoot, Path saveRoot) throws IOException {
        long store = Files.readAttributes(storeRoot, BasicFileAttributes.class).creationTime().toMillis();
        long save = Files.readAttributes(saveRoot, BasicFileAttributes.class).creationTime().toMillis();
        return store < save - OLDER_MILLIS;
    }
}
