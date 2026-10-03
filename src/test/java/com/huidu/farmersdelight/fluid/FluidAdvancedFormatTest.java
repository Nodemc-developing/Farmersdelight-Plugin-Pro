package com.huidu.farmersdelight.fluid;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FluidAdvancedFormatTest {
    @Test void predicatesAreDeeplyImmutableAndUnknownMatchingFieldsAreExplicitlyRejected() {
        Map<String, Object> components = new LinkedHashMap<>(Map.of("test:temperature", 7));
        Map<String, Object> expression = new LinkedHashMap<>(Map.of("fluid", "minecraft:water", "components", components, "exact-components", true));
        var recipe = FluidRecipeSpec.parse("test:advanced", body(expression)); components.put("test:temperature", 99);
        assertEquals(7, ((Map<?, ?>) ((Map<?, ?>) recipe.fluidExpression()).get("components")).get("test:temperature"));
        assertEquals(0, recipe.timeTicks());
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("test:bad", body(Map.of("fluid", "minecraft:water", "unknown-predicate", true))));
        Map<String, Object> invalid = body("minecraft:water"); invalid.put("ingredient", Map.of("item", "minecraft:sponge", "count", 2));
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("test:bad-count", invalid));
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("test:branch-amount", body(Map.of("any-of", List.of(Map.of("fluid", "minecraft:water", "amount", 2000), "minecraft:milk")))));
    }

    @Test void removingMatchingComponentsCannotResurrectThemAndRemovedChoiceMetadataSurvivesOutsideThePredicate() {
        Map<String, Object> previous = new LinkedHashMap<>(Map.of(
                "ingredient", Map.of("items", List.of(Map.of("item", "minecraft:sponge", "x-note", Map.of("owner", "a")), Map.of("item", "minecraft:paper", "x-note", "b"))),
                "result", Map.of("id", "minecraft:wet_sponge", "components", Map.of("minecraft:custom_name", "old", "minecraft:unbreakable", true, "x-note", "c")),
                "fluid", Map.of("any-of", List.of(Map.of("fluid", "minecraft:water", "x-note", "d")), "x-source", "e")));
        Map<String, Object> edited = Map.of("ingredient", "minecraft:sponge", "result", Map.of("id", "minecraft:wet_sponge", "components", Map.of("minecraft:custom_name", "new")), "fluid", "minecraft:water");
        Map<String, Object> merged = FluidRecipeFiles.merge(previous, edited);
        var components = (Map<?, ?>) ((Map<?, ?>) merged.get("result")).get("components");
        assertEquals("new", components.get("minecraft:custom_name")); assertFalse(components.containsKey("minecraft:unbreakable"));
        assertEquals("c", components.get("x-note"));
        var saved = (Map<?, ?>) ((Map<?, ?>) merged.get("extensions")).get("saved_fields");
        assertEquals("b", saved.get("ingredient/items/1/x-note")); assertEquals("d", saved.get("fluid/any-of/0/x-note"));
        assertEquals("e", ((Map<?, ?>) merged.get("fluid")).get("x-source"));
    }

    @Test void aNewJugDefinitionRetainsItsSchemaAndCreatesNativeLootCapacityAndVisualBindings() {
        Map<String, Object> original = Map.of("model", "test:block/glass_jug", "settings", Map.of("libuid:fluid_container", Map.of("capacity", 24000)),
                "behavior", List.of(Map.of("type", "papersdelight:jug_item", "transparent", true, "model", "test:block/liquid"),
                        Map.of("type", "block_item", "block", Map.of("behavior", Map.of("type", "papersdelight:jug", "transparent", true),
                                "loot", Map.of("template", "default:loot_table/self"), "states", Map.of("appearances", Map.of("north", Map.of("entity_renderer", Map.of("type", "item_display", "item", "test:shell"))))))));
        var adapted = (Map<?, ?>) FluidContentFormat.item("test:jug", original);
        var behaviors = (List<?>) adapted.get("behavior");
        assertEquals("test:fluidcore_visual/jug", ((Map<?, ?>) behaviors.get(0)).get("item-model"));
        var block = (Map<?, ?>) ((Map<?, ?>) behaviors.get(1)).get("block"); var jug = (Map<?, ?>) block.get("behavior");
        assertEquals(24000L, jug.get("capacity")); assertEquals("test:fluidcore_visual/jug", jug.get("item-model"));
        var menu = (Map<?, ?>) jug.get("menu");
        assertEquals("jug", menu.get("layout")); assertEquals("auto", menu.get("theme"));
        assertEquals("farmersdelight:jug", menu.get("background-image"));
        assertEquals("farmersdelight:jug_capacity_bucket", menu.get("bucket-item"));
        assertEquals("farmersdelight:jug_capacity_bottle", menu.get("bottle-item"));
        var originalBlock = (Map<?, ?>) ((Map<?, ?>) ((List<?>) original.get("behavior")).get(1)).get("block");
        assertFalse(((Map<?, ?>) originalBlock.get("behavior")).containsKey("menu"));
        assertTrue(((Map<?, ?>) block.get("loot")).containsKey("pools"));
        var renderer = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) block.get("states")).get("appearances")).get("north")).get("entity_renderer");
        assertEquals(Map.of("type", "fluidcore:tank"), renderer.get("tint_source"));
        assertFalse(((Map<?, ?>) ((List<?>) original.get("behavior")).get(0)).containsKey("item-model"));
    }
    @Test void anotherRendererTintIsRejectedAndOwnedTintMetadataSurvives() {
        var original = jugWithTint(Map.of("type", "third_party:pattern", "x-note", "keep"));
        var failure = assertThrows(IllegalArgumentException.class, () -> FluidContentFormat.item("test:jug", original));
        assertTrue(failure.getMessage().contains("test:jug/states/appearances/north"));
        assertTrue(failure.getMessage().contains("incompatible tint_source"));
        var allowed = (Map<?, ?>) FluidContentFormat.item("test:jug", jugWithTint(Map.of("type", "fluidcore:tank", "x-note", "keep")));
        var block = (Map<?, ?>) ((Map<?, ?>) ((List<?>) allowed.get("behavior")).get(1)).get("block");
        var appearance = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) block.get("states")).get("appearances")).get("north");
        var renderer = (Map<?, ?>) appearance.get("entity_renderer");
        assertEquals("keep", ((Map<?, ?>) renderer.get("tint_source")).get("x-note"));
    }
    @Test void userMenuFieldsAndUnknownMetadataOverrideDefaultsWithoutChangingTheOriginalDefinition() {
        Map<String, Object> menu = new LinkedHashMap<>(Map.of("theme", "plain", "bucket-item", "test:bucket", "x-note", List.of("keep")));
        Map<String, Object> jug = new LinkedHashMap<>(Map.of("type", "papersdelight:jug", "menu", menu));
        Map<String, Object> original = Map.of("behavior", Map.of("type", "block_item", "block", Map.of("behavior", jug, "loot", Map.of("template", "default:loot_table/self"))));
        var adapted = (Map<?, ?>) FluidContentFormat.item("test:jug", original);
        var outputBlock = (Map<?, ?>) ((Map<?, ?>) adapted.get("behavior")).get("block");
        var outputMenu = (Map<?, ?>) ((Map<?, ?>) outputBlock.get("behavior")).get("menu");
        assertEquals("plain", outputMenu.get("theme")); assertEquals("test:bucket", outputMenu.get("bucket-item"));
        assertEquals("jug", outputMenu.get("layout")); assertEquals(List.of("keep"), outputMenu.get("x-note"));
        assertFalse(menu.containsKey("layout")); assertFalse(jug.containsKey("capacity"));
        menu.put("x-note", List.of("changed")); assertEquals(List.of("keep"), outputMenu.get("x-note"));
    }
    @Test void scalarMenuIsRejectedBeforeHostConstructionAndNativeDefinitionsKeepTheirOwnMenu() {
        Map<String, Object> original = Map.of("behavior", Map.of("type", "block_item", "block", Map.of("behavior", Map.of("type", "papersdelight:jug", "menu", "invalid"), "loot", Map.of("template", "default:loot_table/self"))));
        var error = assertThrows(IllegalArgumentException.class, () -> FluidContentFormat.item("test:jug", original));
        assertTrue(error.getMessage().contains("test:jug/behavior/block/behavior/menu"));
        Map<String, Object> nativeDefinition = Map.of("behavior", Map.of("type", "block_item", "block", Map.of("behavior", Map.of("type", "fluidcore:tank", "menu", Map.of("theme", "plain")))));
        assertSame(nativeDefinition, FluidContentFormat.item("test:native_tank", nativeDefinition));
    }
    private static Map<String, Object> jugWithTint(Map<String, Object> tint) {
        return Map.of("model", "test:block/glass_jug", "behavior", List.of(
                Map.of("type", "papersdelight:jug_item", "model", "test:block/liquid"),
                Map.of("type", "block_item", "block", Map.of("behavior", Map.of("type", "papersdelight:jug"),
                        "loot", Map.of("template", "default:loot_table/self"), "states", Map.of("appearances", Map.of("north",
                                Map.of("entity_renderer", Map.of("type", "item_display", "item", "test:shell", "tint_source", tint))))))));
    }
    private static Map<String, Object> body(Object expression) {
        return new LinkedHashMap<>(Map.of("type", "soaking", "ingredient", "minecraft:sponge", "fluid", expression, "result", "minecraft:wet_sponge"));
    }
}
