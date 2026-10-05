package com.huidu.farmersdelight.fluid;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huidu.farmersdelight.config.PlainYamlDocuments;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class BundledTankLegacyModelsTest {
    private static final String PACK = "craftengine/farmersdelight_fluids/";
    private static final String ASSETS = PACK + "resourcepack/assets/farmersdelight/";

    @Test void eachCompositeLevelHasAnExplicitLegacyMeshWhileModernTintsRemainIndependent() throws Exception {
        var document = PlainYamlDocuments.parse(new String(bytes(PACK + "configuration/fluid_tank.yml"),
                StandardCharsets.UTF_8), true);
        var items = document.getConfigurationSection("items");
        assertNotNull(items);
        var legacyPaths = new HashSet<String>();
        int composites = 0;
        for (String id : items.getKeys(false)) {
            var item = items.getConfigurationSection(id);
            assertNotNull(item);
            var legacyModel = item.getConfigurationSection("legacy_model");
            assertNotNull(legacyModel, id);
            String legacy = legacyModel.getString("path");
            assertNotNull(legacy, id);
            assertNotNull(mesh(legacy));
            var modernModel = item.getConfigurationSection("model");
            assertNotNull(modernModel, id);
            if (!"minecraft:composite".equals(modernModel.getString("type"))) continue;
            composites++;
            legacyPaths.add(legacy);
            var models = modernModel.getMapList("models");
            assertEquals(2, models.size(), id);
            assertEquals("minecraft:model", models.getFirst().get("type"));
            assertEquals("minecraft:model", models.getLast().get("type"));
            var liquidTints = (java.util.List<?>) models.getLast().get("tints");
            assertEquals(2, liquidTints.size(), id);
            for (var tint : liquidTints) {
                assertEquals(1, ((java.util.Map<?, ?>) tint).get("index"), id);
                assertEquals("minecraft:custom_model_data", ((java.util.Map<?, ?>) tint).get("type"), id);
            }
            if (id.contains("/waterlogged/")) {
                var dryItem = items.getConfigurationSection(id.replace("/waterlogged/", "/"));
                assertNotNull(dryItem);
                var dryLegacyModel = dryItem.getConfigurationSection("legacy_model");
                assertNotNull(dryLegacyModel);
                assertEquals(dryLegacyModel.getString("path"), legacy);
            }
        }
        assertEquals(64, composites);
        assertEquals(32, legacyPaths.size());
    }

    @Test void flattenedLegacyMeshesPreserveBothShellsAndAllSixteenLiquidLevels() throws Exception {
        for (String shellName : new String[]{"glass_jug", "jug"}) {
            var shell = json(ASSETS + "models/block/" + shellName + ".json");
            var shellElements = shell.getAsJsonArray("elements");
            double previousHeight = 1.125;
            for (int level = 1; level <= 16; level++) {
                var merged = json(ASSETS + "models/block/jug_legacy/" + shellName + "_level_%02d.json".formatted(level));
                var elements = merged.getAsJsonArray("elements");
                assertEquals(shellElements.size() + 1, elements.size());
                for (int index = 0; index < shellElements.size(); index++) {
                    assertEquals(shellElements.get(index), elements.get(index), "Attributed shell geometry stays unchanged");
                }
                for (var texture : shell.getAsJsonObject("textures").entrySet()) {
                    assertEquals(texture.getValue(), merged.getAsJsonObject("textures").get(texture.getKey()));
                }
                var liquid = elements.get(elements.size() - 1).getAsJsonObject();
                double height = liquid.getAsJsonArray("to").get(1).getAsDouble();
                assertTrue(height > previousHeight, "Each legacy level must retain its own visible fill height");
                assertEquals(1.125 + 10.5 * level / 16, height, 0.000001);
                previousHeight = height;
                assertEquals(6, liquid.getAsJsonObject("faces").size());
                for (var element : elements) {
                    for (var face : element.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
                        var value = face.getValue().getAsJsonObject();
                        assertFalse(value.has("tintindex"), "Legacy paper items cannot supply independent dynamic tints");
                        String reference = value.get("texture").getAsString();
                        assertTrue(reference.startsWith("#"));
                        assertTrue(merged.getAsJsonObject("textures").has(reference.substring(1)));
                    }
                }
                assertEquals("farmersdelight:block/jug_legacy_liquid",
                        merged.getAsJsonObject("textures").get("legacy_liquid").getAsString());
            }
        }
    }

    @Test void legacyAssetsHaveMatchingHashesAndKeepTheOriginalGeometryAttribution() throws Exception {
        var origins = json(PACK + "ASSET-ORIGINS.json");
        assertEquals("MIT", origins.getAsJsonObject("generated_legacy_models")
                .getAsJsonObject("upstream_geometry").get("license").getAsString());
        assertEquals(origins.get("commit"), origins.getAsJsonObject("generated_legacy_models")
                .getAsJsonObject("upstream_geometry").get("commit"));
        assertEquals(32, origins.getAsJsonObject("generated_legacy_models").getAsJsonArray("files").size());
        for (String group : new String[]{"generated_legacy_models", "generated_legacy_textures"}) {
            var generated = origins.getAsJsonObject(group);
            assertEquals("AGPL-3.0-only", generated.get("license").getAsString());
            for (var file : generated.getAsJsonArray("files")) verifyHash(file.getAsJsonObject());
        }
        for (var source : origins.getAsJsonArray("files")) verifyHash(source.getAsJsonObject());
        var texture = ImageIO.read(new ByteArrayInputStream(bytes(ASSETS + "textures/block/jug_legacy_liquid.png")));
        assertNotNull(texture);
        assertEquals(16, texture.getWidth());
        assertEquals(16, texture.getHeight());
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int argb = texture.getRGB(x, y);
            assertEquals(255, argb >>> 24);
            assertTrue((argb & 255) > ((argb >>> 8) & 255), "Original legacy liquid tile has a fixed blue tint");
        }
    }

    private void verifyHash(JsonObject entry) throws Exception {
        assertEquals(entry.get("sha256").getAsString(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(ASSETS + entry.get("path").getAsString()))));
    }

    private JsonObject mesh(String id) throws Exception {
        assertTrue(id.startsWith("farmersdelight:"));
        return json(ASSETS + "models/" + id.substring("farmersdelight:".length()) + ".json");
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
