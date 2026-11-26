package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Which singleplayer world a far store belongs to. A store's folder is named after the save's folder, and Minecraft
 * gives a new world the folder name of a deleted one (the user's dev7 test: a new "TEST (1)" opened the far terrain of
 * an earlier, deleted "TEST (1)"). So the save holds an id ({@link #SAVE_FILE} in its data folder, deleted with the
 * world) and the store the same id ({@link #STORE_FILE}); a store with another id is another world's, and is deleted
 * before the store opens. A store from before 3.0.0-dev8 has no id: it is kept, and given the save's, unless its folder
 * is older than the save's folder (made for a world before this one).
 */
final class FarWorldId {
    /** In the save's {@code data} folder: the world's id, made on its first join with the far store on. */
    static final String SAVE_FILE = "shaderscompanion-far-store-id.txt";
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
    static void claim(Path storeRoot, Path saveRoot) {
        try {
            Path saveFile = saveRoot.resolve("data").resolve(SAVE_FILE);
            String worldId = read(saveFile);
            boolean newId = worldId == null;
            if (newId) {
                worldId = UUID.randomUUID().toString();
            }
            if (Files.isDirectory(storeRoot)) {
                String storeId = read(storeRoot.resolve(STORE_FILE));
                String reason = null;
                if (storeId != null && !storeId.equals(worldId)) {
                    reason = "it belongs to another world of the same name";
                } else if (storeId == null && olderThan(storeRoot, saveRoot)) {
                    reason = "it was made before this world (for a deleted world of the same name)";
                }
                if (reason != null) {
                    discard(storeRoot, reason);
                }
            }
            if (newId) {
                Files.createDirectories(saveFile.getParent());
                write(saveFile, worldId, "Shaders Companion: this world's id, so its far terrain store (shaderscompanion/lod) is never another world's of the same name.");
            }
            Files.createDirectories(storeRoot);
            write(storeRoot.resolve(STORE_FILE), worldId, "Shaders Companion: the id of the world this far terrain store belongs to (in the save: data/" + SAVE_FILE + ").");
        } catch (IOException | RuntimeException e) {
            ShadersCompanion.LOGGER.warn("Shaders Companion far store: couldn't check which world {} belongs to", storeRoot, e);
        }
    }

    /** The id in a file (its last non-empty line that isn't a comment), or null when there is none. */
    private static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                return line;
            }
        }
        return null;
    }

    private static void write(Path file, String id, String comment) throws IOException {
        Files.writeString(file, "# " + comment + "\n" + id + "\n", StandardCharsets.UTF_8);
    }

    private static boolean olderThan(Path storeRoot, Path saveRoot) throws IOException {
        long store = Files.readAttributes(storeRoot, BasicFileAttributes.class).creationTime().toMillis();
        long save = Files.readAttributes(saveRoot, BasicFileAttributes.class).creationTime().toMillis();
        return store < save - OLDER_MILLIS;
    }

    /** Deletes another world's store: renamed aside first (so a partial delete never opens), then deleted. */
    private static void discard(Path storeRoot, String reason) throws IOException {
        Path aside = storeRoot.resolveSibling(storeRoot.getFileName() + ".discarded-" + System.currentTimeMillis());
        Files.move(storeRoot, aside);
        long bytes = 0;
        int failed = 0;
        try (Stream<Path> walk = Files.walk(aside)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    if (Files.isRegularFile(p)) {
                        bytes += Files.size(p);
                    }
                    Files.delete(p);
                } catch (IOException e) {
                    failed++;
                }
            }
        }
        ShadersCompanion.LOGGER.info("Shaders Companion far store: deleted the store {} ({} MB) because {}; starting a new one{}", storeRoot.getFileName(),
                bytes >> 20, reason, failed > 0 ? " (" + failed + " files couldn't be deleted, left in " + aside.getFileName() + ")" : "");
    }
}
