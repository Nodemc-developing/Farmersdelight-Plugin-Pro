package com.huidu.farmersdelight.pack.compat;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Selects complete definitions before any business parser resolves their contents. */
final class DefinitionPriority {
    record Definition<T>(String id, String source, boolean bundled, T value, Object payload) {
        Definition(String id, String source, boolean bundled, T value) {
            this(id, source, bundled, value, value);
        }
    }
    private record Source(String id, String source) { }

    static <T> List<Definition<T>> select(String category, List<Definition<T>> input, ContentConflicts report) {
        Map<String, Definition<T>> selected = new LinkedHashMap<>();
        Map<Source, Definition<T>> seen = new LinkedHashMap<>();
        for (Definition<T> entry : input) {
            Source source = new Source(entry.id(), entry.source());
            Definition<T> previous = seen.putIfAbsent(source, entry);
            if (previous != null) {
                if (previous.bundled() == entry.bundled() && samePayload(previous.payload(), entry.payload())) continue;
                report.add(category, entry.id(), previous.source(), entry.source(),
                        "same source produced different definitions; loading refused");
                throw new IllegalStateException("Conflicting " + category + " definition " + entry.id()
                        + " from repeated source " + entry.source());
            }
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

    private static boolean samePayload(Object first, Object second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        if (first instanceof Map<?, ?> a && second instanceof Map<?, ?> b) {
            if (!a.keySet().equals(b.keySet())) return false;
            for (var entry : a.entrySet()) if (!samePayload(entry.getValue(), b.get(entry.getKey()))) return false;
            return true;
        }
        if (first instanceof List<?> a && second instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int index = 0; index < a.size(); index++) if (!samePayload(a.get(index), b.get(index))) return false;
            return true;
        }
        if (first.getClass().isArray() && second.getClass().isArray()) {
            if (first.getClass() != second.getClass() || Array.getLength(first) != Array.getLength(second)) return false;
            for (int index = 0; index < Array.getLength(first); index++)
                if (!samePayload(Array.get(first, index), Array.get(second, index))) return false;
            return true;
        }
        return first.equals(second);
    }
}
