package com.huidu.farmersdelight.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HostCommonTagCompilerTest {
    @Test void leafDefaultsHaveOnlyTheirExplicitOriginalItemIdentities() {
        YamlConfiguration defaults = defaults();
        YamlConfiguration old = new YamlConfiguration(); old.createSection("tags");
        String unchanged = old.saveToString();
        Map<String, Set<String>> raw = CommonTagResolver.mergedDefinitions(defaults.getConfigurationSection("tags"),
                old.getConfigurationSection("tags"), (path, error) -> fail(path + error));
        var exported = compile(raw, ignored -> null);
        assertEquals(Set.of("farmersdelight:cabbage", "farmersdelight:cabbage_leaf"), exported.groups().get("farmersdelight:leafs"));
        assertEquals(unchanged, old.saveToString());
        assertTrue(exported.diagnostics().isEmpty());
    }

    @Test void explicitEmptyUserGroupDoesNotAcquireFallbackMembers() {
        YamlConfiguration user = new YamlConfiguration(); user.createSection("tags").set("farmersdelight:leafs", List.of());
        var raw = CommonTagResolver.mergedDefinitions(defaults().getConfigurationSection("tags"), user.getConfigurationSection("tags"),
                (path, error) -> fail(path + error));
        var exported = compile(raw, ignored -> null);
        assertTrue(exported.groups().containsKey("farmersdelight:leafs"));
        assertEquals(Set.of(), exported.groups().get("farmersdelight:leafs"));
    }

    @Test void explicitUserMembersReplaceTheIndependentDefaultWithoutInferringAnotherGroup() {
        YamlConfiguration user = new YamlConfiguration(); user.createSection("tags").set("farmersdelight:leafs", List.of("custom:leaf"));
        var raw = CommonTagResolver.mergedDefinitions(defaults().getConfigurationSection("tags"), user.getConfigurationSection("tags"),
                (path, error) -> fail(path + error));
        assertEquals(Set.of("custom:leaf"), compile(raw, ignored -> null).groups().get("farmersdelight:leafs"));
    }

    @Test void nestedCommonAndActualHostGroupsExpandIntoConcreteIDs() {
        var exported = compile(Map.of("local:base", Set.of("farmersdelight:cabbage"),
                "local:combined", Set.of("#local:base", "#minecraft:logs")),
                tag -> tag.equals("minecraft:logs") ? Set.of("minecraft:oak_log", "minecraft:birch_log") : null);
        assertEquals(Set.of("farmersdelight:cabbage", "minecraft:oak_log", "minecraft:birch_log"), exported.groups().get("local:combined"));
        assertTrue(exported.diagnostics().isEmpty());
    }

    @Test void registeredEmptyHostGroupIsValidButUnknownReferenceIsNotAnEmptyDefinition() {
        var exported = compile(Map.of("local:valid_empty", Set.of("#local:host_empty"), "local:missing", Set.of("#local:not_registered")),
                tag -> tag.equals("local:host_empty") ? Set.of() : null);
        assertEquals(Set.of(), exported.groups().get("local:valid_empty"));
        assertFalse(exported.groups().containsKey("local:missing"));
        var diagnostic = exported.diagnostics().getFirst();
        assertEquals("test-common.yml", diagnostic.source()); assertEquals("tags.local:missing", diagnostic.path());
        assertEquals("local:missing", diagnostic.tag()); assertEquals("#local:not_registered", diagnostic.member());
    }

    @Test void cyclesAreDiagnosedAndNeverMasqueradeAsEmptyOrPartiallyValidGroups() {
        var exported = compile(Map.of("local:a", Set.of("#local:b", "minecraft:stick"), "local:b", Set.of("#local:a")), ignored -> null);
        assertFalse(exported.groups().containsKey("local:a")); assertFalse(exported.groups().containsKey("local:b"));
        assertTrue(exported.diagnostics().stream().anyMatch(diagnostic -> diagnostic.reason().contains("Cyclic")));
    }

    @Test void malformedMemberAndInvalidListPlaceholderDoNotBecomeValidEmptyGroups() {
        var exported = HostCommonTagCompiler.compile(Map.of("local:bad_member", Set.of("minecraft:stick[custom_data={}]"), "local:bad_list", Set.of()),
                Map.of("local:bad_member", "user-common.yml", "local:bad_list", "user-common.yml"), Set.of("local:bad_list"),
                List.of(new CommonTagResolver.HostTagDiagnostic("user-common.yml", "tags.local:bad_list", "local:bad_list", "", "Expected string list")), ignored -> null);
        assertTrue(exported.groups().isEmpty()); assertEquals(2, exported.diagnostics().size());
    }

    @Test void exportDoesNotMutateItsInputAndPublishesOnlyImmutableCollections() {
        var raw = new java.util.LinkedHashMap<String, Set<String>>(); raw.put("local:group", new java.util.HashSet<>(Set.of("minecraft:stick")));
        var exported = compile(raw, ignored -> null); raw.get("local:group").add("minecraft:stone");
        assertEquals(Set.of("minecraft:stick"), exported.groups().get("local:group"));
        assertThrows(UnsupportedOperationException.class, () -> exported.groups().put("local:new", Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> exported.groups().get("local:group").add("minecraft:stone"));
        assertThrows(UnsupportedOperationException.class, () -> exported.diagnostics().clear());
    }

    @Test void hostUsesThePinnedImmutableLoadingInputAndDoesNotReadItsDiagnosticSourceFile() {
        Map<String, Set<String>> oldGroups = new java.util.HashMap<>();
        oldGroups.put("local:pinned", new java.util.HashSet<>(Set.of("custom:original_leaf")));
        var old = new CommonTagResolver.HostTagInput(oldGroups, Map.of("local:pinned", "missing-diagnostic-source-only.yml"), Set.of(), List.of());
        var next = new CommonTagResolver.HostTagInput(Map.of("local:pinned", Set.of()), Map.of("local:pinned", "missing-next-source-only.yml"), Set.of(), List.of());
        oldGroups.get("local:pinned").clear();
        assertEquals(Set.of(), CommonTagResolver.prepareHostExport(next, ignored -> null).groups().get("local:pinned"));
        assertEquals(Set.of("custom:original_leaf"), CommonTagResolver.prepareHostExport(old, ignored -> null).groups().get("local:pinned"));
        assertEquals("missing-diagnostic-source-only.yml", CommonTagResolver.prepareHostExport(old, ignored -> null).sourceFiles().get("local:pinned"));
    }

    @Test void sourceAPIReplacementCannotChangeAnAlreadyCapturedHostLoadingRunOrLeakIntoBuiltin() {
        String source = "native-host-export-source-test";
        CommonTagResolver.clear();
        try {
            CommonTagResolver.registerSource(source, Map.of("local:pinned_source", List.of("custom:old")));
            var captured = new CommonTagResolver.HostTagInput(Map.of(), Map.of(), Set.of(), List.of(), CommonTagResolver.captureHostSources());
            CommonTagResolver.registerSource(source, Map.of("local:pinned_source", List.of("custom:new")));
            assertEquals(Set.of("custom:old"), CommonTagResolver.prepareHostExport(captured, ignored -> null).groups().get("local:pinned_source"));
            assertTrue(captured.groups().isEmpty(), "Addon members must not enter the separately owned builtin document");
            CommonTagResolver.unregisterSource(source);
            assertEquals(Set.of("custom:old"), CommonTagResolver.prepareHostExport(captured, ignored -> null).groups().get("local:pinned_source"));
            assertThrows(UnsupportedOperationException.class, () -> captured.capturedSources().get(source).get("local:pinned_source").add("custom:mutation"));
        } finally { CommonTagResolver.clear(); }
    }

    private static CommonTagResolver.HostTagExport compile(Map<String, Set<String>> raw,
                                                           java.util.function.Function<String, Set<String>> hostTags) {
        var sources = new java.util.HashMap<String, String>(); raw.keySet().forEach(tag -> sources.put(tag, "test-common.yml"));
        return HostCommonTagCompiler.compile(raw, sources, Set.of(), List.of(), hostTags);
    }
    private static YamlConfiguration defaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(HostCommonTagCompilerTest.class.getResourceAsStream("/common-tags.yml"), StandardCharsets.UTF_8));
    }
}
