package com.rinkynooble.shaderscompanion.client;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * Installs the shader pack this jar carries (ABZA Shader) as {@code shaderpacks/ABZA Shader.zip}. The name has no
 * version, so Oculus's selection ({@code config/oculus.properties}) and the player's settings file
 * ({@code shaderpacks/ABZA Shader.zip.txt}) carry over from one version to the next.
 * <p>
 * Runs once, from the mixin config plugin as the game launches: Oculus reads its pack when the renderer starts (or later
 * with Distant Horizons installed), before mods are constructed and their configs loaded, so the client config's
 * {@code installBundledPack} is read straight from its file here. Only classes outside Minecraft are used. The file is
 * written only when it is missing or is still the copy this mod wrote last time (its SHA-256 is kept in
 * {@code config/shaderscompanion/bundled-pack.properties}) and the jar carries a different one. A file the player put
 * there is left alone. Nothing else in {@code shaderpacks} is touched.
 */
public final class BundledPack {
    /** The file name in shaderpacks: fixed, without a version. */
    public static final String FILE_NAME = "ABZA Shader.zip";
    private static final String PACK_RESOURCE = "/bundled/abza-shader.zip";
    private static final String INFO_RESOURCE = "/bundled/pack.properties";
    private static final String TEMP_SUFFIX = ".shaderscompanion-tmp";
    private static final Pattern INSTALL_SETTING = Pattern.compile("(?m)^\\s*installBundledPack\\s*=\\s*(true|false)\\b");
    private static final Logger LOGGER = LogUtils.getLogger();

    /** What happened at startup, for the status line. */
    static String status = "not checked yet";
    private static boolean done;

    private BundledPack() {
    }

    public static void installEarly() {
        if (done) {
            return;
        }
        done = true;
        try {
            if (!FMLLoader.getDist().isClient()) {
                status = "not on a server";
                return;
            }
            Path configFile = FMLPaths.CONFIGDIR.get().resolve("shaderscompanion-client.toml");
            if (Files.isRegularFile(configFile)) {
                Matcher setting = INSTALL_SETTING.matcher(Files.readString(configFile, StandardCharsets.UTF_8));
                if (setting.find() && setting.group(1).equals("false")) {
                    status = "not installed (installBundledPack is off)";
                    LOGGER.info("Shaders Companion: installBundledPack is off, shaderpacks/{} isn't checked", FILE_NAME);
                    return;
                }
            }
            byte[] bundled;
            try (InputStream in = BundledPack.class.getResourceAsStream(PACK_RESOURCE)) {
                if (in == null) {
                    status = "none in this jar";
                    return;
                }
                bundled = in.readAllBytes();
            }
            Properties info = new Properties();
            try (InputStream in = BundledPack.class.getResourceAsStream(INFO_RESOURCE)) {
                if (in != null) {
                    info.load(in);
                }
            }
            String name = info.getProperty("name", "ABZA Shader") + " " + info.getProperty("version", "?");
            install(bundled, sha256(bundled), name, FMLPaths.GAMEDIR.get().resolve("shaderpacks"),
                    FMLPaths.CONFIGDIR.get().resolve("shaderscompanion").resolve("bundled-pack.properties"));
        } catch (IOException | RuntimeException e) {
            status = "install failed: " + e;
            LOGGER.error("Shaders Companion: couldn't install the bundled shader pack as shaderpacks/{}", FILE_NAME, e);
        }
    }

    private static void install(byte[] bundled, String bundledHash, String name, Path shaderpacks, Path recordFile) throws IOException {
        Path target = shaderpacks.resolve(FILE_NAME);
        Properties record = new Properties();
        if (Files.isRegularFile(recordFile)) {
            try (Reader reader = Files.newBufferedReader(recordFile, StandardCharsets.UTF_8)) {
                record.load(reader);
            }
        }
        String recordedHash = record.getProperty("sha256", "");
        String previous = record.getProperty("pack", "");
        if (Files.exists(target)) {
            String current = sha256(Files.readAllBytes(target));
            if (current.equals(bundledHash)) {
                if (!bundledHash.equals(recordedHash)) {
                    saveRecord(recordFile, name, bundledHash);
                }
                status = name + " installed";
                LOGGER.info("Shaders Companion: {} is installed as shaderpacks/{}", name, FILE_NAME);
                return;
            }
            if (!current.equals(recordedHash)) {
                status = "shaderpacks/" + FILE_NAME + " isn't this mod's copy, left alone";
                LOGGER.warn("Shaders Companion: shaderpacks/{} isn't the copy this mod installed, so it is left alone. Delete it to get {} from the mod.",
                        FILE_NAME, name);
                return;
            }
        }
        Files.createDirectories(shaderpacks);
        Path temp = shaderpacks.resolve(FILE_NAME + TEMP_SUFFIX);
        Files.write(temp, bundled);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        saveRecord(recordFile, name, bundledHash);
        status = previous.isEmpty() || previous.equals(name) ? name + " installed" : name + " installed, replacing " + previous;
        LOGGER.info("Shaders Companion: installed {} as shaderpacks/{}{}", name, FILE_NAME,
                previous.isEmpty() || previous.equals(name) ? "" : " (was " + previous + ")");
    }

    private static void saveRecord(Path recordFile, String name, String hash) throws IOException {
        Properties record = new Properties();
        record.setProperty("pack", name);
        record.setProperty("sha256", hash);
        Files.createDirectories(recordFile.getParent());
        try (Writer writer = Files.newBufferedWriter(recordFile, StandardCharsets.UTF_8)) {
            record.store(writer, "The copy of shaderpacks/" + FILE_NAME + " Shaders Companion installed. It replaces the file only while it still matches this hash.");
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
