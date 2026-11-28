package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps the far stores within {@code farStoreDiskMb}, every world and server together, and deletes the stores no world
 * can open again (worker thread, once a store has opened: {@link FarStore}). Every store is a cache: a singleplayer
 * world's fills again from the world's saved chunks (the auto import), a server's from what the player loads there and
 * what the server shares. In this order:
 * <ol>
 * <li>Leftovers: folders a delete renamed aside and couldn't finish ({@code <name>.discarded-<time>}, {@link FarFolders#discard}).</li>
 * <li>Singleplayer stores whose world is gone (no folder in {@code saves} has its name): only a new world of that name
 * could open one, and {@link FarWorldId} would delete it then. Skipped when the saves folder can't be listed.</li>
 * <li>Past the cap: whole stores (a world key's folder, every dimension in it), the one used longest ago first, never the
 * running world's. The running world's store alone past the cap is left as it is.</li>
 * </ol>
 * A store's last use is the newest time among its files and folders; the running world's folder is touched first, so a
 * world joined but not changed still counts as used now.
 */
final class FarCleanup {
    private static final Pattern DISCARDED = Pattern.compile(".+\\.discarded-\\d+");

    private FarCleanup() {
    }

    /** A world key's folder: its name, bytes on disk and last use. */
    private record Store(Path folder, String name, long bytes, long lastUsedMillis) {
    }

    /**
     * Runs the three steps; {@code runningKey} is the world key of the store that is open, {@code savesDir} the game's
     * saves folder (null: step 2 skipped). Never throws: a failure is logged and the stores stay as they are.
     */
    static void run(Path lodRoot, String runningKey, Path savesDir, long capBytes) {
        try {
            if (!Files.isDirectory(lodRoot, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Path running = FarFolders.child(lodRoot, runningKey);
            if (Files.isDirectory(running, LinkOption.NOFOLLOW_LINKS)) {
                Files.setLastModifiedTime(running, FileTime.fromMillis(System.currentTimeMillis()));
            }
            Set<String> saves = savesDir == null ? null : singleplayerKeys(savesDir);
            List<Store> kept = new ArrayList<>();
            long total = 0;
            int deleted = 0;
            long deletedBytes = 0;
            // Listed first: a delete renames its folder aside in this same folder
            List<Path> worlds = new ArrayList<>();
            try (DirectoryStream<Path> listing = Files.newDirectoryStream(lodRoot)) {
                listing.forEach(worlds::add);
            }
            for (Path world : worlds) {
                String name = world.getFileName().toString();
                if (!Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String reason = null;
                if (DISCARDED.matcher(name).matches()) {
                    reason = "an earlier delete left it";
                } else if (name.startsWith("sp-") && saves != null && !saves.contains(name) && !name.equals(runningKey)) {
                    reason = "its singleplayer world is gone";
                }
                if (reason != null) {
                    long bytes = discard(lodRoot, world, reason);
                    if (bytes >= 0) {
                        deletedBytes += bytes;
                        deleted++;
                        continue;
                    }
                }
                // A dimension's leftover inside a world's folder (far reset deletes one dimension's store)
                List<Path> dimensions = new ArrayList<>();
                try (DirectoryStream<Path> listing = Files.newDirectoryStream(world)) {
                    listing.forEach(dimensions::add);
                }
                for (Path dimension : dimensions) {
                    if (Files.isDirectory(dimension, LinkOption.NOFOLLOW_LINKS) && DISCARDED.matcher(dimension.getFileName().toString()).matches()) {
                        long bytes = discard(lodRoot, dimension, "an earlier delete left it");
                        if (bytes >= 0) {
                            deletedBytes += bytes;
                            deleted++;
                        }
                    }
                }
                Store store = measure(world, name);
                total += store.bytes();
                if (!name.equals(runningKey)) {
                    kept.add(store);
                }
            }
            long totalBefore = total;
            kept.sort(Comparator.comparingLong(Store::lastUsedMillis));
            for (Store store : kept) {
                if (total <= capBytes) {
                    break;
                }
                long bytes = discard(lodRoot, store.folder(), String.format(
                        "the far stores took %d MB, past farStoreDiskMb (%d MB), and it was used longest ago", total >> 20, capBytes >> 20));
                if (bytes >= 0) {
                    total -= store.bytes();
                    deletedBytes += bytes;
                    deleted++;
                }
            }
            if (deleted > 0 || total > capBytes) {
                ShadersCompanion.LOGGER.info("Shaders Companion far store: the far stores take {} MB of farStoreDiskMb's {} MB ({} deleted, {} MB){}", total >> 20,
                        capBytes >> 20, deleted, deletedBytes >> 20, total > capBytes ? "; the store of the world you are in is kept although it alone is past it" : "");
            } else {
                ShadersCompanion.LOGGER.debug("Shaders Companion far store: the far stores take {} MB of farStoreDiskMb's {} MB", totalBefore >> 20, capBytes >> 20);
            }
        } catch (IOException | RuntimeException e) {
            ShadersCompanion.LOGGER.warn("Shaders Companion far store: couldn't check the far stores' disk use in {}", lodRoot, e);
        }
    }

    /** {@link FarFolders#discard}, the bytes deleted, or -1 when it failed (logged: the next folder is still tried). */
    private static long discard(Path lodRoot, Path folder, String reason) {
        try {
            return FarFolders.discard(lodRoot, folder, reason);
        } catch (IOException | RuntimeException e) {
            ShadersCompanion.LOGGER.warn("Shaders Companion far store: couldn't delete {} ({})", folder, reason, e);
            return -1;
        }
    }

    /** The world keys of the worlds in the saves folder ({@link FarFolders#singleplayerKey}); null when it can't be listed. */
    private static Set<String> singleplayerKeys(Path savesDir) {
        if (!Files.isDirectory(savesDir)) {
            return null;
        }
        Set<String> keys = new HashSet<>();
        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(savesDir)) {
            for (Path world : worlds) {
                if (Files.isDirectory(world)) {
                    keys.add(FarFolders.singleplayerKey(world));
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return keys;
    }

    /** A world key folder's bytes and its newest file or folder time (links not followed). */
    private static Store measure(Path folder, String name) {
        long[] bytes = {0};
        long[] newest = {0};
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.forEach(p -> {
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (a.isRegularFile()) {
                        bytes[0] += a.size();
                    }
                    newest[0] = Math.max(newest[0], a.lastModifiedTime().toMillis());
                } catch (IOException e) {
                    // a file the store is replacing: counted next time
                }
            });
        } catch (IOException | java.io.UncheckedIOException e) {
            // counted as far as it got
        }
        return new Store(folder, name, bytes[0], newest[0]);
    }
}
