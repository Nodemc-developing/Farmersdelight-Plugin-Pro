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

/** Verifies the load-time host contract before any content factories are registered. */
public final class CraftEngineBaseline {
    public static final String VERSION = "26.10-20260929.192451-4";
    public static final String SHA256 = "46ebe45f31f3e3f0965179a85cb1f308d8729f53281d6c64f4ef2af5c23d99f6";

    private CraftEngineBaseline() { }

    public static void verify(JavaPlugin plugin) {
        Plugin host = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if (host == null) throw new IllegalStateException("CraftEngine " + VERSION + " must load before this plugin.");
        try {
            Path artifact = Path.of(host.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (artifact.getParent() != null && artifact.getParent().getFileName().toString().equals(".paper-remapped")) {
                artifact = artifact.getParent().getParent().resolve(artifact.getFileName());
            }
            String actual = digest(artifact);
            if (!SHA256.equals(actual)) {
                throw new IllegalStateException("Unsupported CraftEngine build: expected " + VERSION
                        + " (SHA-256 " + SHA256 + "), received " + actual
                        + ". Verify the updated host API before changing the supported baseline.");
            }
        } catch (IOException | URISyntaxException failure) {
            throw new IllegalStateException("Cannot verify the required CraftEngine 26.10 artifact.", failure);
        }
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
