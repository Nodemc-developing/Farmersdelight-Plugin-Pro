package com.huidu.farmersdelight.util.compat;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CraftEngineBaselineTest {
    @Test void stableRebuildsAndExistingSnapshotFamilyUseVersionMetadata() {
        for (String version : List.of("26.9.2", "26.9.2-rebuilt", "26.9.2+build.42", "26.9.2-SNAPSHOT"))
            assertEquals("26.9.2", CraftEngineBaseline.versionFamily(version));
        for (String version : List.of("26.10", "26.10-SNAPSHOT", "26.10-20260929.192451-4"))
            assertEquals("26.10", CraftEngineBaseline.versionFamily(version));
    }

    @Test void neighboringVersionsAndMalformedLabelsDoNotMatchByPrefix() {
        for (String version : List.of("26.9.20", "26.9.1", "26.9.3", "26.10.1", "26.11", "26.9.2-", "26.9.2/invalid", ""))
            assertThrows(IllegalStateException.class, () -> CraftEngineBaseline.versionFamily(version), version);
        assertThrows(IllegalStateException.class, () -> CraftEngineBaseline.versionFamily(null));
    }

    @Test void supportedPluginWithNoArtifactLocationPassesTheActualHostContract() {
        PluginMeta metadata = (PluginMeta) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PluginMeta.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getVersion")) return "26.9.2-rebuilt+42";
                    throw new AssertionError("Unexpected metadata access: " + method);
                });
        Plugin host = (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPluginMeta")) return metadata;
                    throw new AssertionError("Compatibility must not access the artifact or world: " + method);
                });
        assertEquals("26.9.2", CraftEngineBaseline.verifyHost(host));
        assertThrows(IllegalStateException.class, () -> CraftEngineBaseline.verifyHost(null));
    }

    @Test void absentRequiredApiReportsTheMissingMemberWithoutConsultingAnyHash() {
        String missing = "net.momirealms.craftengine.core.pack.PackCacheData";
        ClassLoader alteredApi = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(missing)) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        var failure = assertThrows(IllegalStateException.class, () -> CraftEngineBaseline.verifyApi(alteredApi));
        assertTrue(failure.getMessage().contains(missing));
        assertFalse(failure.getMessage().contains("SHA"));
    }
}
