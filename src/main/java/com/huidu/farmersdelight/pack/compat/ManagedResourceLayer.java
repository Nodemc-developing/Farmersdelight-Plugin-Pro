package com.huidu.farmersdelight.pack.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Produces one file per resource path. The source trees are never changed. */
final class ManagedResourceLayer {
    record Source(Path path, boolean zip, String prefix, boolean currentDefault) {
        Source(Path path, boolean zip) { this(path, zip, "", false); }
    }
    @FunctionalInterface interface Reader { InputStream open() throws IOException; }
    record Asset(String path, String source, boolean bundled, boolean currentDefault, String hash, Reader reader) {
        byte[] bytes() throws IOException {
            byte[] content;
            try (InputStream input = reader.open()) { content = input.readAllBytes(); }
            if (!hash.equals(BundledContentManifest.digest(content)))
                throw new IOException("Resource source changed while generating the managed layer: " + source);
            return content;
        }
    }
    private final Path root;
    private final BundledContentManifest ownership;
    private final ContentConflicts report;

    ManagedResourceLayer(Path root, BundledContentManifest ownership, ContentConflicts report) {
        this.root = root.toAbsolutePath().normalize(); this.ownership = ownership; this.report = report;
    }

    synchronized Path build(List<Source> sources) throws IOException {
        Map<String, List<Asset>> inputs = new TreeMap<>();
        // Sorting makes diagnostics reproducible; it never acts as a tie breaker for differing externals.
        for (Source source : sources.stream().distinct().sorted(Comparator.comparing(s -> s.path() + "!/" + s.prefix())).toList()) {
            if (source.zip()) {
                if (!Files.isRegularFile(source.path())) continue;
                try (ZipFile zip = new ZipFile(source.path().toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (entry.isDirectory() || !entry.getName().startsWith(source.prefix())) continue;
                        String relative = safeRelative(entry.getName().substring(source.prefix().length()));
                        String hash;
                        try (InputStream stream = zip.getInputStream(entry)) { hash = BundledContentManifest.digest(stream); }
                        String entryName = entry.getName();
                        add(inputs, new Asset(relative, source.path() + "!/" + entry.getName(), source.currentDefault(), source.currentDefault(), hash,
                                () -> openZipEntry(source.path(), entryName)));
                    }
                }
            } else if (Files.isDirectory(source.path())) {
                try (var paths = Files.walk(source.path())) {
                    for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                        // Following external symlinks could import data outside the declared resource root.
                        if (Files.isSymbolicLink(file)) throw new IOException("Symbolic resource file requires an explicit source root: " + file);
                        String relative = safeRelative(source.path().relativize(file).toString().replace('\\', '/'));
                        String hash;
                        try (InputStream input = Files.newInputStream(file)) { hash = BundledContentManifest.digest(input); }
                        add(inputs, new Asset(relative, file.toString(), ownership.isBundledHash(file, hash), false, hash,
                                () -> Files.newInputStream(file)));
                    }
                }
            }
        }
        Map<String, Asset> outputs = new TreeMap<>();
        Map<String, Object> index = new LinkedHashMap<>();
        try {
            for (var entry : inputs.entrySet()) {
                Asset output;
                if (isLanguage(entry.getKey())) {
                    byte[] merged = mergeLanguage(entry.getKey(), entry.getValue());
                    output = new Asset(entry.getKey(), "merged language", false, false, BundledContentManifest.digest(merged),
                            () -> new ByteArrayInputStream(merged));
                } else output = choose(entry.getKey(), entry.getValue());
                outputs.put(entry.getKey(), output);
                Map<String, Object> sourceIndex = new LinkedHashMap<>();
                sourceIndex.put("sha256", output.hash());
                sourceIndex.put("sources", entry.getValue().stream().map(Asset::source).toList());
                index.put(entry.getKey(), sourceIndex);
            }
        } finally { report.save(); }
        String generation = BundledContentManifest.digest(new GsonBuilder().create().toJson(index).getBytes(StandardCharsets.UTF_8));
        Path destination = root.resolve(generation);
        Path complete = destination.resolveSibling(generation + ".complete");
        if (Files.isRegularFile(complete) && outputs.keySet().stream().allMatch(path -> Files.isRegularFile(destination.resolve(path))))
            return destination;
        Files.createDirectories(destination);
        // Failed candidates are never selected by the coordinator. Each file is replaced atomically.
        for (var entry : outputs.entrySet()) {
            Path target = destination.resolve(entry.getKey()).normalize();
            if (!target.startsWith(destination)) throw new IOException("Unsafe resource path " + entry.getKey());
            atomicCopy(target, entry.getValue());
        }
        BundledContentManifest.atomicWrite(complete, new GsonBuilder().setPrettyPrinting().create()
                .toJson(index).getBytes(StandardCharsets.UTF_8));
        return destination;
    }

    private static void add(Map<String, List<Asset>> inputs, Asset asset) {
        inputs.computeIfAbsent(asset.path(), ignored -> new ArrayList<>()).add(asset);
    }

    private Asset choose(String path, List<Asset> assets) throws IOException {
        Asset winner = null;
        for (Asset entry : assets) {
            if (winner == null) { winner = entry; continue; }
            if (!winner.bundled() && !entry.bundled()) {
                if (winner.hash().equals(entry.hash())) continue;
                report.add("asset", path, winner.source(), entry.source(), "external-external conflict; generation refused");
                throw new IOException("Conflicting external resource " + path + " in " + winner.source() + " and " + entry.source());
            }
            if (winner.bundled() && entry.bundled()) {
                if (!winner.hash().equals(entry.hash())) {
                    if (winner.currentDefault() == entry.currentDefault()) throw new IOException("Conflicting bundled resource " + path);
                    Asset next = winner.currentDefault() ? winner : entry;
                    Asset omitted = winner.currentDefault() ? entry : winner;
                    report.add("asset", path, next.source(), omitted.source(), "current bundled release replaces verified historical default in managed layer");
                    winner = next;
                }
                continue;
            }
            Asset next = winner.bundled() ? entry : winner;
            Asset omitted = winner.bundled() ? winner : entry;
            report.add("asset", path, next.source(), omitted.source(), "external resource takes precedence");
            winner = next;
        }
        return winner;
    }

    private byte[] mergeLanguage(String path, List<Asset> assets) throws IOException {
        Map<String, List<Asset>> keys = new TreeMap<>();
        for (Asset asset : assets) {
            JsonObject language;
            try { language = JsonParser.parseString(new String(asset.bytes(), StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException invalid) { throw new IOException("Invalid language JSON: " + asset.source(), invalid); }
            for (var entry : language.entrySet()) {
                byte[] value = entry.getValue().toString().getBytes(StandardCharsets.UTF_8);
                add(keys, new Asset(entry.getKey(), asset.source(), asset.bundled(), asset.currentDefault(), BundledContentManifest.digest(value),
                        () -> new ByteArrayInputStream(value)));
            }
        }
        JsonObject result = new JsonObject();
        for (var entry : keys.entrySet()) {
            Asset winner = choose(path + "#" + entry.getKey(), entry.getValue());
            result.add(entry.getKey(), JsonParser.parseString(new String(winner.bytes(), StandardCharsets.UTF_8)));
        }
        return new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(result).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isLanguage(String path) { return path.matches("assets/[^/]+/lang/[^/]+\\.json"); }

    private static InputStream openZipEntry(Path path, String entryName) throws IOException {
        ZipFile zip = new ZipFile(path.toFile());
        try {
            InputStream input = zip.getInputStream(zip.getEntry(entryName));
            return new FilterInputStream(input) {
                @Override public void close() throws IOException { try { super.close(); } finally { zip.close(); } }
            };
        } catch (Exception failure) { zip.close(); throw failure; }
    }

    private static void atomicCopy(Path target, Asset source) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".fd-asset-", ".tmp");
        try {
            try (InputStream input = source.reader().open()) { Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            try (InputStream input = Files.newInputStream(temporary)) {
                if (!source.hash().equals(BundledContentManifest.digest(input)))
                    throw new IOException("Resource source changed while generating the managed layer: " + source.source());
            }
            try { Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String safeRelative(String value) throws IOException {
        Path path = Path.of(value.replace('\\', '/')).normalize();
        if (path.isAbsolute() || path.startsWith("..") || value.contains(":")) throw new IOException("Unsafe resource entry: " + value);
        return path.toString().replace('\\', '/');
    }
}
