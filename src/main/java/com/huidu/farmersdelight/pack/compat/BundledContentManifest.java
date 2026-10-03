package com.huidu.farmersdelight.pack.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.zip.ZipFile;

/** Ownership is verified by content, never by a directory name alone. */
public final class BundledContentManifest {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path file;
    private final Map<String, String> hashes;

    BundledContentManifest(Path file) throws IOException {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try {
                Map<String, String> read = JSON.fromJson(Files.readString(file),
                        new TypeToken<Map<String, String>>() { }.getType());
                hashes = read == null ? new LinkedHashMap<>() : new LinkedHashMap<>(read);
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid bundled content ownership manifest: " + file, invalid);
            }
        } else hashes = new LinkedHashMap<>();
    }

    static BundledContentManifest load(JavaPlugin plugin) throws IOException {
        return new BundledContentManifest(plugin.getDataFolder().toPath().resolve("content-cache/bundled-sources.json"));
    }

    /** Existing matching releases can be adopted; modified files retain operator ownership. */
    void adoptMatching(JavaPlugin plugin) throws Exception {
        Path jar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(jar)) return;
        Path root = plugin.getDataFolder().toPath().getParent().resolve("CraftEngine/resources").toAbsolutePath().normalize();
        Map<String, List<String>> history;
        try (InputStream input = plugin.getResource("content/bundled-history.json")) {
            Map<String, List<String>> loaded = input == null ? null : JSON.fromJson(new String(input.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8), new TypeToken<Map<String, List<String>>>() { }.getType());
            history = loaded == null ? Map.of() : loaded;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("craftengine/")) continue;
                Path target = root.resolve(entry.getName().substring("craftengine/".length())).normalize();
                if (!target.startsWith(root) || !Files.isRegularFile(target)) continue;
                String packaged;
                try (InputStream input = zip.getInputStream(entry)) { packaged = digest(input.readAllBytes()); }
                adoptIfKnown(target, packaged, history.getOrDefault(entry.getName(), List.of()));
            }
        }
        save();
    }

    synchronized void adoptIfKnown(Path target, String currentHash, List<String> historicalHashes) throws IOException {
        String actual;
        try (InputStream input = Files.newInputStream(target)) { actual = digest(input); }
        if (actual.equals(currentHash) || historicalHashes.contains(actual)) hashes.put(key(target), actual);
    }

    public synchronized void record(Path path, byte[] content) throws IOException {
        hashes.put(key(path), digest(content));
        save();
    }

    public synchronized boolean isBundled(Path path, byte[] content) {
        return isBundledHash(path, digest(content));
    }

    synchronized boolean isBundledHash(Path path, String digest) { return digest.equals(hashes.get(key(path))); }

    synchronized boolean isBundled(Path path) throws IOException {
        String expected = hashes.get(key(path));
        if (expected == null || !Files.isRegularFile(path)) return false;
        try (InputStream input = Files.newInputStream(path)) { return expected.equals(digest(input)); }
    }

    private synchronized void save() throws IOException {
        atomicWrite(file, JSON.toJson(hashes).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    synchronized void persist() throws IOException { save(); }

    static String key(Path path) { return path.toAbsolutePath().normalize().toString(); }

    static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static String digest(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static void atomicWrite(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".fd-content-", ".tmp");
        try {
            Files.write(temporary, data);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
