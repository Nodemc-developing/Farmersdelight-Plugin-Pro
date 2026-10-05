package com.huidu.farmersdelight.resource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huidu.farmersdelight.config.PlainYamlDocuments;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacyDecorationModelsTest {
    private static final String PACK = "craftengine/farmersdelight/";
    private static final String ASSETS = PACK + "resourcepack/assets/farmersdelight/";

    @Test void explicitLegacyMeshesRetainBothFacesAndEveryDecorationPart() throws Exception {
        var document = PlainYamlDocuments.parse(new String(bytes(PACK + "configuration/blocks.yml"), StandardCharsets.UTF_8), true);
        var origins = json(PACK + "ASSET-ORIGINS-LEGACY.json").getAsJsonArray("files");
        assertEquals(3, origins.size());
        var items = document.getConfigurationSection("items");
        assertNotNull(items);
        for (String name : List.of("full_tatami_mat", "half_tatami_mat", "canvas_rug")) {
            var item = items.getConfigurationSection("farmersdelight:" + name + "_elements");
            assertNotNull(item);
            var legacyModel = item.getConfigurationSection("legacy_model");
            assertNotNull(legacyModel);
            assertEquals("farmersdelight:block/legacy/" + name, legacyModel.getString("path"));
            var modernModel = item.getConfigurationSection("model");
            assertNotNull(modernModel);
            assertEquals("minecraft:select", modernModel.getString("type"));
            assertEquals("minecraft:display_context", modernModel.getString("property"));
            var fallback = modernModel.getConfigurationSection("fallback");
            assertNotNull(fallback);
            assertEquals("${__NAMESPACE__}:block/" + name, fallback.getString("path"));
            var cases = modernModel.getMapList("cases");
            assertEquals(1, cases.size());
            assertEquals(List.of("gui"), cases.getFirst().get("when"));
            assertEquals("${__NAMESPACE__}:item/" + name,
                    ((java.util.Map<?, ?>) cases.getFirst().get("model")).get("path"));

            var original = json(ASSETS + "models/block/" + name + ".json");
            var legacy = json(ASSETS + "models/block/legacy/" + name + ".json");
            assertEquals(original, legacy, "Every authored mesh part, face, texture, credit and transform must survive");
            var parts = new HashSet<String>();
            for (var element : legacy.getAsJsonArray("elements")) {
                var part = element.getAsJsonObject();
                parts.add(part.get("name").getAsString());
                var faces = part.getAsJsonObject("faces");
                assertTrue(faces.has("up"), name + " upper face");
                assertTrue(faces.has("down"), name + " lower face");
            }
            if (name.equals("full_tatami_mat")) assertEquals(java.util.Set.of("tatami_mat_head", "tatami_mat_foot"), parts);
            if (name.equals("half_tatami_mat")) assertEquals(java.util.Set.of("tatami_mat_half"), parts);
            if (name.equals("canvas_rug")) assertEquals(java.util.Set.of("canvas", "north_fraying", "east_fraying", "south_fraying", "west_fraying"), parts);
        }
        for (var value : origins) {
            var entry = value.getAsJsonObject();
            assertEquals(entry.get("sha256").getAsString(), hash(ASSETS + entry.get("path").getAsString()));
            assertEquals(entry.get("source_sha256").getAsString(), hash(ASSETS + entry.get("source").getAsString()));
        }
    }

    private String hash(String path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(path)));
    }

    private JsonObject json(String path) throws Exception {
        return JsonParser.parseString(new String(bytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private byte[] bytes(String path) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return stream.readAllBytes();
        }
    }
}
