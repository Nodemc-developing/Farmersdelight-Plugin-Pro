package com.huidu.farmersdelight.pack.compat;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Original source paths remain visible even when loading through a managed resource layer. */
final class ContentConflicts {
    record Conflict(String category, String id, String winner, String omitted, String reason) { }
    private final Path report;
    private final List<Conflict> conflicts = new ArrayList<>();
    ContentConflicts(Path report) { this.report = report; }
    synchronized void add(String category, String id, String winner, String omitted, String reason) {
        Conflict conflict = new Conflict(category, id, winner, omitted, reason);
        if (!conflicts.contains(conflict)) conflicts.add(conflict);
    }
    synchronized void save() throws IOException {
        BundledContentManifest.atomicWrite(report, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(List.copyOf(conflicts)).getBytes(StandardCharsets.UTF_8));
    }
}
