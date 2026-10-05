package com.huidu.farmersdelight.pack.compat;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ItemVersionInputsTest {
    private static final Map<String, Object> REMAINDER = Map.of("id", "minecraft:bowl", "count", 1);

    @Test void earlyFoodPreservesNutritionContainerAndExtensionsWithoutChangingSource() {
        var input = Map.<String, Object>of("material", "cooked_beef", "extension", Map.of("future", true),
                "data", Map.of("food", Map.of("nutrition", 7, "saturation", 8.4, "can-always-eat", true),
                        "components", Map.of("minecraft:use_remainder", REMAINDER,
                                "minecraft:custom_data", Map.of("other", 42))));
        var result = ItemVersionInputs.adapt(input, false);
        var data = (Map<?, ?>) result.get("data");
        var components = (Map<?, ?>) data.get("components");
        var food = (Map<?, ?>) components.get("minecraft:food");
        assertEquals(7, food.get("nutrition"));
        assertEquals(8.4, food.get("saturation"));
        assertEquals(true, food.get("can_always_eat"));
        assertEquals(REMAINDER, food.get("using_converts_to"));
        assertEquals(Map.of("other", 42), components.get("minecraft:custom_data"));
        assertEquals(input.get("extension"), result.get("extension"));
        assertFalse(data.containsKey("food"));
        assertFalse(components.containsKey("minecraft:use_remainder"));
        assertTrue(((Map<?, ?>) input.get("data")).containsKey("food"));
    }

    @Test void nativeFoodRetainsEffectsAndOverridesShorthandFields() {
        var input = Map.<String, Object>of("data", Map.of("food", Map.of("nutrition", 1, "saturation", 2),
                "components", Map.of("minecraft:use_remainder", REMAINDER, "minecraft:food",
                        Map.of("nutrition", 3, "saturation", 4, "effects", java.util.List.of("custom")))));
        var result = ItemVersionInputs.adapt(input, false);
        var components = (Map<?, ?>) ((Map<?, ?>) result.get("data")).get("components");
        var food = (Map<?, ?>) components.get("minecraft:food");
        assertEquals(3, food.get("nutrition"));
        assertEquals(java.util.List.of("custom"), food.get("effects"));
    }

    @Test void modernVersionsKeepSeparateComponents() {
        var input = Map.<String, Object>of("data", Map.of("components", Map.of("minecraft:use_remainder", REMAINDER)));
        assertEquals(input, ItemVersionInputs.adapt(input, true));
        assertNotSame(input, ItemVersionInputs.adapt(input, true));
    }

    @Test void missingNutritionFailsRatherThanInventingFoodOrLosingTheContainer() {
        var input = Map.<String, Object>of("data", Map.of("components", Map.of("minecraft:use_remainder", REMAINDER)));
        assertThrows(IllegalArgumentException.class, () -> ItemVersionInputs.adapt(input, false));
    }

    @Test void conflictingContainerDefinitionsAreExplicitlyRejected() {
        var input = Map.<String, Object>of("data", Map.of("components", Map.of("minecraft:use_remainder", REMAINDER,
                "minecraft:food", Map.of("nutrition", 1, "saturation", 0,
                        "using_converts_to", Map.of("id", "minecraft:bucket")))));
        assertThrows(IllegalArgumentException.class, () -> ItemVersionInputs.adapt(input, false));
    }

    @Test void earlyTooltipUsesPresenceOnlyUnitAndFalseLeavesNoUnit() {
        for (String shorthand : List.of("hide_tooltip", "hide-tooltip")) {
            var input = Map.<String, Object>of("data", Map.of(shorthand, true));
            var data = data(ItemVersionInputs.adapt(input, true, false));
            assertEquals(Map.of("minecraft:hide_tooltip", Map.of()), data.get("components"));
            assertFalse(data.containsKey(shorthand));
            assertEquals(Map.of(shorthand, true), input.get("data"));

            var visible = data(ItemVersionInputs.adapt(Map.of("data", Map.of(shorthand, false)), true, false));
            assertFalse(visible.containsKey(shorthand));
            assertFalse(visible.containsKey("components"));
        }
    }

    @Test void modernTooltipMergesNativeDisplayWithoutLosingExtensionsOrChangingSource() {
        for (boolean hide : List.of(true, false)) {
            var originalDisplay = Map.<String, Object>of("hidden_components", List.of("minecraft:attribute_modifiers"),
                    "extension", Map.of("future", 3));
            var input = Map.<String, Object>of("data", Map.of("hide-tooltip", hide, "components",
                    Map.of("minecraft:tooltip_display", originalDisplay, "minecraft:custom_data", Map.of("keep", 42))));
            var result = ItemVersionInputs.adapt(input, true, true);
            var components = (Map<?, ?>) data(result).get("components");
            var display = (Map<?, ?>) components.get("minecraft:tooltip_display");
            assertEquals(hide, display.get("hide_tooltip"));
            assertEquals(originalDisplay.get("hidden_components"), display.get("hidden_components"));
            assertEquals(originalDisplay.get("extension"), display.get("extension"));
            assertEquals(Map.of("keep", 42), components.get("minecraft:custom_data"));
            assertFalse(data(result).containsKey("hide-tooltip"));
            assertFalse(originalDisplay.containsKey("hide_tooltip"));
            assertNotSame(originalDisplay, display);
            assertEquals(hide, data(input).get("hide-tooltip"));
        }
    }

    @Test void explicitTooltipConflictsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ItemVersionInputs.adapt(Map.of("data",
                Map.of("hide_tooltip", true, "hide-tooltip", false)), true, true));
        assertThrows(IllegalArgumentException.class, () -> ItemVersionInputs.adapt(Map.of("data",
                Map.of("hide_tooltip", true, "hide-tooltip", List.of("attribute_modifiers"))), true, true));
        assertThrows(IllegalArgumentException.class, () -> ItemVersionInputs.adapt(Map.of("data",
                Map.of("hide_tooltip", true, "components", Map.of("minecraft:tooltip_display",
                        Map.of("hide_tooltip", false)))), true, true));
        assertThrows(IllegalArgumentException.class, () -> ItemVersionInputs.adapt(Map.of("data",
                Map.of("hide_tooltip", false, "components", Map.of("minecraft:hide_tooltip", Map.of()))), true, false));
        assertThrows(IllegalArgumentException.class, () -> ItemVersionInputs.adapt(Map.of("data",
                Map.of("hide_tooltip", true, "components", List.of("invalid"))), true, true));
    }

    @Test void listTooltipShorthandRemainsForTheOriginalProcessor() {
        var input = Map.<String, Object>of("data", Map.of("hide-tooltip", List.of("attribute_modifiers"),
                "components", Map.of("minecraft:custom_data", Map.of("keep", true))));
        for (boolean modern : List.of(true, false)) {
            assertEquals(input, ItemVersionInputs.adapt(input, true, modern));
        }
    }

    @Test void tooltipAndEarlyFoodAdaptationsBothApply() {
        var input = Map.<String, Object>of("data", Map.of("hide_tooltip", true,
                "food", Map.of("nutrition", 2, "saturation", 1, "effects", List.of("keep")),
                "components", Map.of("minecraft:use_remainder", REMAINDER)));
        var components = (Map<?, ?>) data(ItemVersionInputs.adapt(input, false, false)).get("components");
        assertEquals(Map.of(), components.get("minecraft:hide_tooltip"));
        var food = (Map<?, ?>) components.get("minecraft:food");
        assertEquals(REMAINDER, food.get("using_converts_to"));
        assertEquals(List.of("keep"), food.get("effects"));
    }

    @Test void bundledInvisibleItemBooleanCannotBecomeAComponentNamedTrue() throws IOException {
        try (var resource = getClass().getResourceAsStream("/craftengine/farmersdelight/configuration/gui.yml")) {
            assertNotNull(resource);
            var yaml = net.momirealms.sparrow.yaml.SparrowYaml.create().load(
                    new String(resource.readAllBytes(), StandardCharsets.UTF_8)).getValues();
            var items = (Map<?, ?>) yaml.get("items");
            @SuppressWarnings("unchecked") var invisible = (Map<String, Object>) items.get("farmersdelight:gui_invisible");
            assertEquals(true, data(invisible).get("hide_tooltip"));
            for (boolean modern : List.of(true, false)) {
                var components = (Map<?, ?>) data(ItemVersionInputs.adapt(invisible, true, modern)).get("components");
                assertFalse(components.containsKey("minecraft:true"));
                assertFalse(data(ItemVersionInputs.adapt(invisible, true, modern)).containsKey("hide_tooltip"));
                assertEquals(modern ? Map.of("hide_tooltip", true) : Map.of(), components.get(
                        modern ? "minecraft:tooltip_display" : "minecraft:hide_tooltip"));
            }
            assertEquals(true, data(invisible).get("hide_tooltip"));
        }
    }

    private static Map<?, ?> data(Map<String, Object> item) {
        return (Map<?, ?>) item.get("data");
    }
}
