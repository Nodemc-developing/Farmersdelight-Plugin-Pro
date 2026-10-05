package com.huidu.farmersdelight.util.compat;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Verifies the load-time host contract before any content factories are registered. */
public final class CraftEngineBaseline {
    public static final String VERSION = "26.10-20260929.192451-4";
    public static final String SHA256 = "46ebe45f31f3e3f0965179a85cb1f308d8729f53281d6c64f4ef2af5c23d99f6";
    public static final String STABLE_VERSION = "26.9.2";
    public static final String STABLE_SHA256 = "1f9e0935a11e7d6c7a979f2d521ec24efb715375c876a57ef3cc11e9fb9895aa";
    public static final String STABLE_ARCHIVE_SHA256 = "19535f1987e8a27ebe8c6d811e7deae9a1f05dbd3ef3359435aff5a3d819e0f9";
    private static final Map<String, String> SUPPORTED_BUILDS = Map.of(
            STABLE_SHA256, STABLE_VERSION, STABLE_ARCHIVE_SHA256, STABLE_VERSION, SHA256, VERSION);

    private CraftEngineBaseline() { }

    public static void verify(JavaPlugin plugin) {
        Plugin host = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if (host == null) throw new IllegalStateException("CraftEngine must load before this plugin.");
        verifyHostArtifact(host.getClass());
    }

    /** Both loading bridges use the same verified original artifact, including Paper's remapped location. */
    public static String verifyHostArtifact(Class<?> hostType) {
        try {
            Path artifact = originalArtifact(Path.of(hostType.getProtectionDomain().getCodeSource().getLocation().toURI()));
            return versionForHash(digest(artifact));
        } catch (IOException | URISyntaxException failure) {
            throw new IllegalStateException("Cannot verify the required CraftEngine artifact (26.9.2 or the supported 26.10 snapshot).", failure);
        }
    }

    static Path originalArtifact(Path location) {
        Path artifact = location.toAbsolutePath().normalize();
        Path parent = artifact.getParent();
        if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals(".paper-remapped")) {
            return parent.getParent().resolve(artifact.getFileName());
        }
        return artifact;
    }

    static String versionForHash(String actual) {
        String version = SUPPORTED_BUILDS.get(actual);
        if (version == null) throw new IllegalStateException("Unsupported CraftEngine build: received SHA-256 " + actual
                + "; supported builds are " + STABLE_VERSION + " (" + STABLE_SHA256 + ", " + STABLE_ARCHIVE_SHA256
                + ") and " + VERSION + " (" + SHA256 + "). "
                + "Verify the updated host API before changing the supported baselines.");
        return version;
    }

    static String digest(Path artifact) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(artifact)) {
                byte[] buffer = new byte[32 * 1024];
                for (int count; (count = stream.read(buffer)) >= 0;) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
