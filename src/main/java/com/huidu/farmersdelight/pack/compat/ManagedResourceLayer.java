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
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

/** Produces one file per resource path. The source trees are never changed. */
final class ManagedResourceLayer {
    record Source(Path path, boolean zip, String prefix, boolean currentDefault) {
        Source(Path path, boolean zip) { this(path, zip, "", false); }
    }
    @FunctionalInterface interface Reader { InputStream open() throws IOException; }
    @FunctionalInterface interface PathReader { OpenPath open() throws IOException; }
    record OpenPath(Path path, FileSystem fileSystem) implements AutoCloseable {
        @Override public void close() throws IOException { if (fileSystem != null) fileSystem.close(); }
    }
    record Asset(String path, String source, boolean bundled, boolean currentDefault, String hash, Reader reader,
                 PathReader pathReader) {
        Asset(String path, String source, boolean bundled, boolean currentDefault, String hash, Reader reader) {
            this(path, source, bundled, currentDefault, hash, reader, null);
        }
        OpenPath openPath() throws IOException {
            if (pathReader == null) throw new IOException("No original resource path for " + source);
            return pathReader.open();
        }
        void verify(Path file) throws IOException {
            try (InputStream input = Files.newInputStream(file)) {
                if (!hash.equals(BundledContentManifest.digest(input)))
                    throw new IOException("Resource source changed while generating the managed layer: " + source);
            }
        }
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
    private final Supplier<HostResourcePolicy> hostPolicy;

    ManagedResourceLayer(Path root, BundledContentManifest ownership, ContentConflicts report) {
        this(root, ownership, report, () -> new HostResourcePolicy(List.of(), Set.of(), ignored -> { }));
    }

    ManagedResourceLayer(Path root, BundledContentManifest ownership, ContentConflicts report,
                         Supplier<HostResourcePolicy> hostPolicy) {
        this.root = root.toAbsolutePath().normalize(); this.ownership = ownership; this.report = report;
        this.hostPolicy = hostPolicy;
    }

    synchronized Path build(List<Source> sources) throws IOException {
        Map<String, List<Asset>> inputs = new TreeMap<>();
        HostResourcePolicy policy = hostPolicy.get();
        // External order is the host's order: pack folders, event folders, then event ZIPs.
        for (Source source : sources.stream().distinct().toList()) {
            if (source.zip()) {
                if (!Files.isRegularFile(source.path())) continue;
                try (ZipFile zip = new ZipFile(source.path().toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (entry.isDirectory() || !entry.getName().startsWith(source.prefix())) continue;
                        String relative = safeRelative(entry.getName().substring(source.prefix().length()));
                        if (policy.excludes(relative)) continue;
                        String hash;
                        try (InputStream stream = zip.getInputStream(entry)) { hash = BundledContentManifest.digest(stream); }
                        String entryName = entry.getName();
                        add(inputs, new Asset(relative, source.path() + "!/" + entry.getName(), source.currentDefault(), source.currentDefault(), hash,
                                () -> openZipEntry(source.path(), entryName), () -> {
                                    FileSystem fs = FileSystems.newFileSystem(source.path());
                                    return new OpenPath(fs.getPath("/" + entryName), fs);
                                }));
                    }
                }
            } else if (Files.isDirectory(source.path())) {
                try (var paths = Files.walk(source.path())) {
                    for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                        // Following external symlinks could import data outside the declared resource root.
                        if (Files.isSymbolicLink(file)) throw new IOException("Symbolic resource file requires an explicit source root: " + file);
                        String relative = safeRelative(source.path().relativize(file).toString().replace('\\', '/'));
                        if (policy.excludes(relative)) continue;
                        String hash;
                        try (InputStream input = Files.newInputStream(file)) { hash = BundledContentManifest.digest(input); }
                        add(inputs, new Asset(relative, file.toString(), ownership.isBundledHash(file, hash), false, hash,
                                () -> Files.newInputStream(file), () -> new OpenPath(file, null)));
                    }
                }
            }
        }
        Map<String, Asset> outputs = new TreeMap<>();
        Map<String, Object> index = new LinkedHashMap<>();
        Files.createDirectories(root);
        Path scratch = Files.createTempDirectory(root, ".merge-");
        try {
            for (var entry : inputs.entrySet()) {
                Asset output = resolve(entry.getKey(), entry.getValue(), policy, scratch);
                outputs.put(entry.getKey(), output);
                Map<String, Object> sourceIndex = new LinkedHashMap<>();
                sourceIndex.put("sha256", output.hash());
                sourceIndex.put("sources", entry.getValue().stream().map(Asset::source).toList());
                index.put(entry.getKey(), sourceIndex);
            }
        } finally {
            try { report.save(); }
            finally {
                try (var files = Files.walk(scratch)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
                }
            }
        }
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

    private Asset resolve(String path, List<Asset> assets, HostResourcePolicy policy, Path scratch) throws IOException {
        List<Asset> external = assets.stream().filter(asset -> !asset.bundled()).toList();
        List<Asset> defaults = assets.stream().filter(Asset::bundled).toList();
        if (external.isEmpty()) {
            return isLanguage(path) ? generated(path, "merged bundled language", mergeLanguage(path, defaults))
                    : choose(path, defaults);
        }
        Asset result = external.size() == 1 ? external.getFirst()
                : generated(path, "host-resolved external resources", policy.merge(path, external, scratch, report));
        for (Asset fallback : defaults)
            report.add("asset", path, result.source(), fallback.source(), "external resource takes precedence");
        if (isLanguage(path) && !defaults.isEmpty()) {
            List<Asset> languages = new ArrayList<>(defaults);
            languages.add(result);
            result = generated(path, "external language with bundled fallback", mergeLanguage(path, languages));
        }
        return result;
    }

    private static Asset generated(String path, String source, byte[] bytes) {
        return new Asset(path, source, false, false, BundledContentManifest.digest(bytes), () -> new ByteArrayInputStream(bytes));
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
