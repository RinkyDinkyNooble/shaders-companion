package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * What the player chose per server about the far terrain servers share ({@code /shaderscompanion far server allow|deny}),
 * kept in {@code shaderscompanion/lod/servers.txt} as {@code <server key>=allow|deny} lines (the key is
 * {@link FarFolders#serverBase}: the address, not the world). A server not listed is allowed, and listed as allowed the
 * first time it shares (after the chat line that says how to turn it down). Main thread.
 */
final class FarChoices {
    static final String ALLOW = "allow";
    static final String DENY = "deny";
    private static final String FILE = "servers.txt";
    private static final Pattern LINE = Pattern.compile("(mp-[A-Za-z0-9._~-]{1,120})=(allow|deny)");
    private static Map<String, String> choices;
    private static Path loadedFrom;

    private FarChoices() {
    }

    /** The choice for a server, or null when it has none. */
    static String get(Path lodRoot, String server) {
        return load(lodRoot).get(server);
    }

    static void set(Path lodRoot, String server, String choice) {
        Map<String, String> map = load(lodRoot);
        map.put(server, choice);
        StringBuilder out = new StringBuilder("# Shaders Companion: far terrain from servers, per server allow or deny (/shaderscompanion far server allow|deny)\n");
        for (Map.Entry<String, String> e : map.entrySet()) {
            out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        try {
            Files.createDirectories(lodRoot);
            Path temporary = lodRoot.resolve(FILE + ".tmp");
            Files.writeString(temporary, out.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, lodRoot.resolve(FILE), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            ShadersCompanion.LOGGER.warn("Shaders Companion far terrain: saving {} failed: {}", FILE, e.toString());
        }
    }

    private static Map<String, String> load(Path lodRoot) {
        if (choices != null && lodRoot.equals(loadedFrom)) {
            return choices;
        }
        choices = new TreeMap<>();
        loadedFrom = lodRoot;
        Path file = lodRoot.resolve(FILE);
        try {
            if (Files.isRegularFile(file) && Files.size(file) < 1 << 20) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String line : lines) {
                    var m = LINE.matcher(line.trim());
                    if (m.matches()) {
                        choices.put(m.group(1), m.group(2));
                    }
                }
            }
        } catch (IOException e) {
            ShadersCompanion.LOGGER.warn("Shaders Companion far terrain: reading {} failed: {}", file, e.toString());
        }
        return choices;
    }
}
