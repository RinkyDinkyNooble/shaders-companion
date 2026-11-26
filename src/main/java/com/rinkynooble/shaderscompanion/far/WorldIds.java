package com.rinkynooble.shaderscompanion.far;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * A world's far terrain id: a random UUID kept in the save's {@code data} folder ({@link #SAVE_FILE}, deleted with the
 * world), made the first time anything asks for it. The client's singleplayer far store keeps the same id beside it, so
 * a new world given a deleted one's folder name never opens the old store; a server sends it to its players, so their
 * far stores tell its worlds apart (a reset map, several worlds behind one address). Common code.
 */
public final class WorldIds {
    /** In the save's {@code data} folder: the world's id. */
    public static final String SAVE_FILE = "shaderscompanion-far-store-id.txt";

    private WorldIds() {
    }

    /** The save's id, or null when it has none yet. */
    public static String read(Path saveRoot) throws IOException {
        return readId(saveRoot.resolve("data").resolve(SAVE_FILE));
    }

    /** The save's id, made and written now when it has none. */
    public static String readOrCreate(Path saveRoot) throws IOException {
        String id = read(saveRoot);
        if (id == null) {
            id = UUID.randomUUID().toString();
            Path file = saveRoot.resolve("data").resolve(SAVE_FILE);
            Files.createDirectories(file.getParent());
            writeId(file, id, "Shaders Companion: this world's id, so its far terrain store (shaderscompanion/lod) is never another world's of the same name.");
        }
        return id;
    }

    /** The id in a file (its last non-empty line that isn't a comment), or null when there is none. */
    public static String readId(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > 4096) {
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

    /** Writes an id with a comment line above it. */
    public static void writeId(Path file, String id, String comment) throws IOException {
        Files.writeString(file, "# " + comment + "\n" + id + "\n", StandardCharsets.UTF_8);
    }
}
