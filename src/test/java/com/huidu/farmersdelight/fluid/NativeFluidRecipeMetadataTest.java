package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeFluidRecipeMetadataTest {
    @Test void nestedExtensionKeysThatResembleRecipeFieldsAreNeverTreatedAsGameplayFields() {
        var previous = Map.<String, Object>of("station", "fluid_tank", "operation", "soak",
                "input", Map.of("item", Map.of("item", "minecraft:sponge", "extensions", Map.of("item", "source", "count", 9))),
                "output", Map.of("item", "minecraft:wet_sponge", "nbt", "full-item-snapshot", "count", 1,
                        "x-display", Map.of("item", "icon", "count", 7)),
                "fluid", Map.of("match", "minecraft:water", "amount-mb", 1000),
                "extensions", Map.of("item", "root-note", "count", 2), "category", "custom:category");
        var edited = Map.<String, Object>of("station", "fluid_tank", "operation", "soak",
                "input", Map.of("item", Map.of("item", "minecraft:sponge", "extensions", Map.of("x-note", "updated"))),
                "output", Map.of("item", "minecraft:wet_sponge", "nbt", "full-item-snapshot", "count", 3,
                        "x-display", Map.of("x-note", "updated")),
                "fluid", Map.of("match", "minecraft:water", "amount-mb", 500),
                "extensions", Map.of("x-note", "updated"));
        var merged = FluidRecipeFiles.merge(previous, edited);
        var input = (Map<?, ?>) ((Map<?, ?>) merged.get("input")).get("item");
        assertEquals(Map.of("item", "source", "count", 9, "x-note", "updated"), input.get("extensions"));
        var output = (Map<?, ?>) merged.get("output");
        assertEquals("full-item-snapshot", output.get("nbt"));
        assertEquals(3, output.get("count"));
        assertEquals(Map.of("item", "icon", "count", 7, "x-note", "updated"), output.get("x-display"));
        var extensions = (Map<?, ?>) merged.get("extensions");
        assertEquals("root-note", extensions.get("item"));
        assertEquals(2, extensions.get("count"));
        assertEquals("updated", extensions.get("x-note"));
        assertEquals("custom:category", merged.get("category"));
        assertEquals(1, ((Map<?, ?>) previous.get("output")).get("count"));
    }

    @Test void removedOutputUsesNativeContainerWithoutLosingLiteralSourceMetadata() throws Exception {
        var yaml = PlainYamlDocuments.parse("""
                station: fluid_tank
                operation: fill
                input: {item: minecraft:glass_bottle}
                output: {item: minecraft:potion, x-note: retained}
                fluid: {match: minecraft:water, amount-mb: 250}
                extensions:
                  saved_fields: {previous: marker}
                  source: owner
                process: {ticks: 20, x-display: preserved}
                """, true);
        var edited = Map.<String, Object>of("station", "fluid_tank", "operation", "fill",
                "input", Map.of("item", "minecraft:glass_bottle"),
                "fluid", Map.of("match", "minecraft:water", "amount-mb", 250));
        var merged = FluidRecipeFiles.merge(yaml.getValues(false), edited);
        assertFalse(merged.containsKey("output"));
        assertTrue(FluidRecipeSpec.parse("example:native", merged).result().isEmpty());
        assertEquals(Map.of("x-display", "preserved"), merged.get("process"));
        var extensions = (Map<?, ?>) merged.get("extensions");
        assertEquals("owner", extensions.get("source"));
        var saved = (Map<?, ?>) extensions.get("saved_fields");
        assertEquals("marker", saved.get("previous"));
        assertEquals("retained", saved.get("output/x-note"));
    }
}
