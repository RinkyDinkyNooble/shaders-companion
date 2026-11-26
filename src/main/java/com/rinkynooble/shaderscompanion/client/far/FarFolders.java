package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceLocation;

/**
 * Where far stores live, and the one place that names and deletes their folders: {@code <game
 * dir>/shaderscompanion/lod/<world key>/<dimension>/}. Nothing a server sends is ever used as a file name as it is: a
 * server's world id is hashed, a dimension's id has every character outside {@code [A-Za-z0-9._-]} replaced (and a hash
 * added when that changed it, so two ids never share a folder), and every folder is checked to lie inside the lod folder
 * before it is opened or deleted. Deleting renames the folder aside first (so a half-deleted store never opens) and
 * never follows a link.
 */
final class FarFolders {
    /** What a world key or dimension folder may be made of. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._~-]{1,120}");
    /** The default port, left out of a server's key (the same server with and without it is one store). */
    private static final String DEFAULT_PORT = ":25565";

    private FarFolders() {
    }

    /** The folder all far stores are in. */
    static Path lodRoot(Path gameDirectory) {
        return gameDirectory.resolve("shaderscompanion").resolve("lod").toAbsolutePath().normalize();
    }

    /** A singleplayer world's key: "sp-" and its save folder's name. */
    static String singleplayerKey(Path saveRoot) {
        Path name = saveRoot.getFileName();
        return "sp-" + safe(name == null ? "world" : name.toString());
    }

    /**
     * A server's key. Without a world id (the server doesn't have the mod): "mp-" and its address as typed, lower case,
     * without the default port. With one: the address and "-w" with the id's hash, so a server's worlds (a reset map,
     * worlds behind one proxy) have their own stores, and no server can write into another address's store by claiming
     * its id; on a LAN game, whose port changes every time it opens, "mp-lan-w" and the hash alone.
     */
    static String serverKey(ServerData server, String worldId) {
        String base = serverBase(server);
        if (worldId == null) {
            return base;
        }
        return (server != null && server.isLan() ? "mp-lan" : base) + "-w" + hash(worldId, 12);
    }

    /** The part of a server's key that names its address ("mp-..."): what its stores share (the disk cap for server data). */
    static String serverBase(ServerData server) {
        if (server == null) {
            return "mp-unknown";
        }
        if (server.isLan()) {
            return "mp-lan";
        }
        String address = server.ip.trim().toLowerCase(Locale.ROOT);
        if (address.endsWith(DEFAULT_PORT)) {
            address = address.substring(0, address.length() - DEFAULT_PORT.length());
        }
        return "mp-" + safe(address.isEmpty() ? "unknown" : address);
    }

    /**
     * A dimension's folder: its namespace and path joined by "_" ("minecraft_overworld"); any other character replaced,
     * and then a hash of the id added, so a datapack's dimension named with "/" or ".." stays a folder of its own here.
     */
    static String dimensionFolder(ResourceLocation dimension) {
        String plain = dimension.getNamespace() + "_" + dimension.getPath();
        String folder = safe(plain);
        if (!folder.equals(plain) || folder.length() > 80) {
            folder = folder.substring(0, Math.min(folder.length(), 60)) + "-" + hash(dimension.toString(), 8);
        }
        return folder;
    }

    /** Every character outside {@code [A-Za-z0-9._-]} replaced by "_"; never "." or "..", at most 100 characters. */
    static String safe(String name) {
        String out = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (out.length() > 100) {
            out = out.substring(0, 88) + "-" + hash(name, 11);
        }
        if (out.isEmpty() || out.chars().allMatch(c -> c == '.')) {
            out = "_" + out;
        }
        return out;
    }

    /** The first {@code hexChars} hex digits of a string's SHA-256. */
    static String hash(String text, int hexChars) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, hexChars);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code root/name}, after checking the name is a safe folder name and the result lies inside {@code root}. */
    static Path child(Path root, String name) {
        if (!SAFE.matcher(name).matches() || name.chars().allMatch(c -> c == '.')) {
            throw new IllegalArgumentException("not a far store folder name: " + name);
        }
        Path out = root.resolve(name).toAbsolutePath().normalize();
        if (!out.startsWith(root.toAbsolutePath().normalize()) || out.equals(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("far store folder outside its root: " + out);
        }
        return out;
    }

    /**
     * Deletes a far store's folder (a world's, or one dimension's) inside the lod folder: renamed aside first (so a
     * partial delete never opens), then deleted without following links. Returns the bytes deleted.
     */
    static long discard(Path lodRoot, Path folder, String reason) throws IOException {
        Path root = lodRoot.toAbsolutePath().normalize();
        Path target = folder.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root) || Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a far store folder: " + target);
        }
        Path aside = target.resolveSibling(target.getFileName() + ".discarded-" + System.currentTimeMillis());
        Files.move(target, aside);
        long[] bytes = {0};
        int failed = deleteTree(aside, bytes);
        ShadersCompanion.LOGGER.info("Shaders Companion far store: deleted the store {} ({} MB) because {}{}", root.relativize(target), bytes[0] >> 20, reason,
                failed > 0 ? " (" + failed + " files couldn't be deleted, left in " + aside.getFileName() + ")" : "");
        return bytes[0];
    }

    /** Deletes a folder and everything in it (links themselves, never what they point to); returns the files that failed. */
    private static int deleteTree(Path folder, long[] bytes) throws IOException {
        int failed = 0;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        bytes[0] += Files.size(p);
                    }
                    Files.delete(p);
                } catch (IOException e) {
                    failed++;
                }
            }
        }
        return failed;
    }

    /** The bytes of the regular files under a folder (links not followed); 0 when it doesn't exist. */
    static long size(Path folder) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        long[] bytes = {0};
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.forEach(p -> {
                try {
                    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        bytes[0] += Files.size(p);
                    }
                } catch (IOException e) {
                    // a file the store is replacing: counted next time
                }
            });
        } catch (IOException | java.io.UncheckedIOException e) {
            // the same
        }
        return bytes[0];
    }
}
