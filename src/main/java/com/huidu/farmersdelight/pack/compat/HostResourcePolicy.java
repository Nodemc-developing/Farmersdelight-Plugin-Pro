package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.conflict.PathContext;
import net.momirealms.craftengine.core.pack.conflict.resolution.ConditionalResolution;
import net.momirealms.craftengine.core.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** External files follow the host's configured rules, independently of bundled-content priority. */
record HostResourcePolicy(List<ConditionalResolution> resolutions, Set<String> excludedExtensions,
                          Consumer<String> warning) {
    HostResourcePolicy {
        resolutions = List.copyOf(resolutions);
        excludedExtensions = Set.copyOf(excludedExtensions);
    }

    boolean excludes(String path) {
        return excludedExtensions.contains(FileUtils.getExtension(Path.of(path)));
    }

    byte[] merge(String relative, List<ManagedResourceLayer.Asset> inputs, Path scratch,
                 ContentConflicts report) throws IOException {
        Path target = scratch.resolve(relative).normalize();
        if (!target.startsWith(scratch)) throw new IOException("Unsafe resource path " + relative);
        Files.createDirectories(target.getParent());
        Files.write(target, inputs.getFirst().bytes());
        PathContext relativeContext = PathContext.of(Path.of(relative));
        boolean unresolved = false;
        for (int index = 1; index < inputs.size(); index++) {
            var incoming = inputs.get(index);
            ConditionalResolution matched = null;
            for (var rule : resolutions) {
                if (rule.matcher().test(relativeContext)) { matched = rule; break; }
            }
            if (matched == null) {
                // The host retains the first file and reports duplicates when no rule matches.
                if (!inputs.getFirst().hash().equals(incoming.hash())) {
                    unresolved = true;
                    report.add("asset", relative, inputs.getFirst().source(), incoming.source(),
                            "no host resolution; first external resource retained in host input order");
                }
                continue;
            }
            try (var source = incoming.openPath()) {
                incoming.verify(source.path());
                matched.resolution().run(PathContext.of(target), PathContext.of(source.path()));
                incoming.verify(source.path());
            } catch (RuntimeException failure) {
                throw new IOException("Host resource resolution failed for " + relative + " from " + incoming.source(), failure);
            }
            report.add("asset", relative, inputs.getFirst().source(), incoming.source(),
                    "host resolution: " + matched.resolution().getClass().getSimpleName());
        }
        if (unresolved) warning.accept("Unresolved external resource duplicates at " + relative
                + "; retained the first file using CraftEngine input order. Sources: "
                + inputs.stream().map(ManagedResourceLayer.Asset::source).toList());
        return Files.readAllBytes(target);
    }
}
