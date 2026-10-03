package com.huidu.farmersdelight.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Resolves only explicit common definitions and genuinely registered host tags during loading. */
final class HostCommonTagCompiler {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private record Expansion(Set<String> members, boolean valid) { }
    private final Map<String, Set<String>> raw;
    private final Map<String, String> sources;
    private final Set<String> invalid;
    private final Function<String, Set<String>> hostTags;
    private final List<CommonTagResolver.HostTagDiagnostic> diagnostics;
    private final Map<String, Expansion> expanded = new HashMap<>();
    private final Set<String> visiting = new HashSet<>();

    private HostCommonTagCompiler(Map<String, Set<String>> raw, Map<String, String> sources, Set<String> invalid,
                                  List<CommonTagResolver.HostTagDiagnostic> diagnostics,
                                  Function<String, Set<String>> hostTags) {
        this.raw = raw; this.sources = sources; this.invalid = invalid; this.hostTags = hostTags;
        this.diagnostics = new ArrayList<>(diagnostics);
    }

    static CommonTagResolver.HostTagExport compile(Map<String, Set<String>> raw, Map<String, String> sources,
                                                  Set<String> invalid,
                                                  List<CommonTagResolver.HostTagDiagnostic> diagnostics,
                                                  Function<String, Set<String>> hostTags) {
        HostCommonTagCompiler compiler = new HostCommonTagCompiler(raw, sources, invalid, diagnostics, hostTags);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String tag : raw.keySet().stream().sorted().toList()) {
            Expansion group = compiler.expand(tag);
            if (group.valid()) result.put(tag, group.members());
        }
        Map<String, String> exportedSources = new LinkedHashMap<>();
        result.keySet().forEach(tag -> exportedSources.put(tag, sources.getOrDefault(tag, "common-tag registry")));
        return new CommonTagResolver.HostTagExport(Map.copyOf(result), Map.copyOf(exportedSources), List.copyOf(compiler.diagnostics));
    }

    private Expansion expand(String tag) {
        Expansion cached = expanded.get(tag);
        if (cached != null) return cached;
        if (!ID.matcher(tag).matches()) {
            problem(tag, "", "Group requires an explicit, valid namespace and path");
            Expansion failed = new Expansion(Set.of(), false); expanded.put(tag, failed); return failed;
        }
        if (invalid.contains(tag)) {
            Expansion failed = new Expansion(Set.of(), false); expanded.put(tag, failed); return failed;
        }
        if (!visiting.add(tag)) {
            problem(tag, "#" + tag, "Cyclic common group reference");
            return new Expansion(Set.of(), false);
        }
        Set<String> concrete = new HashSet<>();
        boolean valid = true;
        for (String member : raw.getOrDefault(tag, Set.of()).stream().sorted().toList()) {
            if (member.startsWith("#")) {
                String reference = member.substring(1);
                if (!ID.matcher(reference).matches()) {
                    problem(tag, member, "Referenced group requires an explicit, valid namespace and path"); valid = false; continue;
                }
                if (raw.containsKey(reference)) {
                    Expansion nested = expand(reference);
                    if (!nested.valid()) { problem(tag, member, "Referenced common group could not be compiled"); valid = false; }
                    else concrete.addAll(nested.members());
                } else {
                    Set<String> host = hostTags.apply(reference);
                    if (host == null) { problem(tag, member, "Neither a declared common group nor a registered host tag"); valid = false; }
                    else for (String item : host) {
                        if (item == null || !ID.matcher(item).matches()) {
                            problem(tag, member, "Host tag returned a non-concrete item identity"); valid = false;
                        } else concrete.add(item);
                    }
                }
            } else if (!ID.matcher(member).matches()) {
                problem(tag, member, "Member requires an explicit, valid concrete item ID"); valid = false;
            } else concrete.add(member);
        }
        visiting.remove(tag);
        Expansion result = new Expansion(Set.copyOf(concrete), valid); expanded.put(tag, result); return result;
    }

    private void problem(String tag, String member, String reason) {
        diagnostics.add(new CommonTagResolver.HostTagDiagnostic(sources.getOrDefault(tag, "common-tag registry"),
                "tags." + tag, tag, member, reason));
    }
}
