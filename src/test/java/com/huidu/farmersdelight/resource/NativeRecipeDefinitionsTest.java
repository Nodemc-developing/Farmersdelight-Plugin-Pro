package com.huidu.farmersdelight.resource;

import com.google.gson.GsonBuilder;
import com.huidu.farmersdelight.config.PlainYamlDocuments;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class NativeRecipeDefinitionsTest {
    @Test void cookingPotDefinitionsRetainAllBundledIngredientsAndOutputs() throws Exception {
        var yaml = resource("recipes/cooking_pot_recipes.yml");
        assertRecipes(yaml, 28, "cooking_pot");
        var recipes = yaml.getConfigurationSection("farmersdelight_recipes");
        assertFalse(recipes.getConfigurationSection("dumplings").getConfigurationSection("input").contains("container"));
        assertFalse(recipes.getConfigurationSection("cabbage_rolls").getConfigurationSection("input").contains("container"));
        assertFingerprint(yaml, "a3fff9843b95cb7e41ed3357579447960a40ce924ea48ac63c87a2b121e1a706");
    }

    @Test void cuttingBoardDefinitionsRetainAllProbabilitiesToolsAndSounds() throws Exception {
        var yaml = resource("recipes/cutting_board_recipes.yml");
        assertRecipes(yaml, 107, "cutting_board");
        assertFingerprint(yaml, "b055b6c31e7ef0501e759d36ddf1d314c673f20f52494507f461b1b5446f1880");
    }

    @Test void fluidDefinitionsRetainAllAmountsDelaysAndAdvancedGroups() throws Exception {
        var yaml = resource("craftengine/farmersdelight_fluids/configuration/fluid_recipes.yml");
        assertRecipes(yaml, 24, "fluid_tank");
        assertEquals(List.of("minecraft:wheat"),
                yaml.getConfigurationSection("advanced_tags").getConfigurationSection("c:crops/wheat").getStringList("values"));
        assertFingerprint(yaml, "ac760e69f0440477d8ef86c82c528fc2fe35d96b80933cd611f4a1ebeffdb76b");
    }

    private ConfigurationSection resource(String path) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, path);
            return PlainYamlDocuments.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8), true);
        }
    }

    private static void assertRecipes(ConfigurationSection yaml, int count, String station) {
        var recipes = yaml.getConfigurationSection("farmersdelight_recipes");
        assertNotNull(recipes);
        assertEquals(count, recipes.getKeys(false).size());
        for (String id : recipes.getKeys(false)) {
            var body = recipes.getConfigurationSection(id);
            assertNotNull(body, id);
            assertEquals(station, body.getString("station"), id);
            assertNotNull(body.getConfigurationSection("input"), id);
            assertNotNull(body.get("output"), id);
        }
    }

    private static void assertFingerprint(ConfigurationSection yaml, String expected) throws Exception {
        String json = new GsonBuilder().disableHtmlEscaping().create().toJson(ordered(yaml));
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, actual, "Bundled recipe data changed; review ingredients, counts, probabilities and processing conditions");
    }

    private static Object ordered(Object value) {
        if (value instanceof ConfigurationSection section) value = section.getValues(false);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), ordered(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(NativeRecipeDefinitionsTest::ordered).toList();
        return value;
    }
}
