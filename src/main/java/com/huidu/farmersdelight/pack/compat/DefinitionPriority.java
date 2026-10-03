package com.huidu.farmersdelight.pack.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Selects complete definitions before any business parser resolves their contents. */
final class DefinitionPriority {
    record Definition<T>(String id, String source, boolean bundled, T value) { }

    static <T> List<Definition<T>> select(String category, List<Definition<T>> input, ContentConflicts report) {
        Map<String, Definition<T>> selected = new LinkedHashMap<>();
        for (Definition<T> entry : input) {
            Definition<T> old = selected.get(entry.id());
            if (old == null) { selected.put(entry.id(), entry); continue; }
            if (!old.bundled() && !entry.bundled()) {
                report.add(category, entry.id(), old.source(), entry.source(), "external-external conflict; loading refused");
                throw new IllegalStateException("Conflicting external " + category + " definition " + entry.id()
                        + " in " + old.source() + " and " + entry.source());
            }
            if (old.bundled() && entry.bundled()) {
                report.add(category, entry.id(), old.source(), entry.source(), "duplicate bundled definition; loading refused");
                throw new IllegalStateException("Duplicate bundled " + category + " definition " + entry.id());
            }
            Definition<T> winner = old.bundled() ? entry : old;
            Definition<T> omitted = old.bundled() ? old : entry;
            selected.put(entry.id(), winner);
            report.add(category, entry.id(), winner.source(), omitted.source(), "external content takes precedence");
        }
        return new ArrayList<>(selected.values());
    }
}
