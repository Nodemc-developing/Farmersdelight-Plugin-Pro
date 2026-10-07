package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import com.huidu.farmersdelight.pack.PackSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecipeDocumentProbeTest {
    private static final Path SOURCE = Path.of("content-pack/configuration/recipes.yml");

    @Test void unrelatedLanguageDuplicatesDoNotEnterStrictRecipeConstruction() throws Exception {
        String contents = "lang:\n  zh_cn:\n    fish.external.sturgeon: first\n    fish.external.sturgeon: second\n";
        assertFalse(RecipeDocumentProbe.ownsRecipeRoots(contents));
        assertNull(RecipeDocumentProbe.parse(SOURCE, contents, false));
        assertThrows(InvalidConfigurationException.class, () -> PlainYamlDocuments.parse(contents, true));
    }

    @Test void realRecipeDuplicatesStillFailAndNameTheOriginalFile() {
        String contents = "farmersdelight_recipes:\n  addon:soup:\n    station: cooking_pot\n    station: cutting_board\n";
        assertTrue(RecipeDocumentProbe.ownsRecipeRoots(contents));
        var failure = assertThrows(InvalidConfigurationException.class, () -> RecipeDocumentProbe.parse(SOURCE, contents, false));
        assertTrue(failure.getMessage().contains(SOURCE.toString()));
        assertNotNull(failure.getCause());
    }

    @Test void managedFilesRemainStrictEvenWithoutAnyOwnedRootAndKeepEmptyDocuments() throws Exception {
        assertThrows(InvalidConfigurationException.class, () -> RecipeDocumentProbe.parse(SOURCE, "language: a\nlanguage: b\n", true));
        assertNotNull(RecipeDocumentProbe.parse(SOURCE, "", true));
        assertEquals("value", RecipeDocumentProbe.parse(SOURCE, "custom_extension: value\n", true).getString("custom_extension"));
    }

    @Test void quotedFlowRootsAndSectionSuffixesAreRecognizedWithoutChangingLiteralIds() throws Exception {
        String contents = "{'farmersdelight_recipes#addon': {'addon:soup.v2': {station: cooking_pot}}}";
        var document = RecipeDocumentProbe.parse(SOURCE, contents, false);
        assertNotNull(document);
        assertEquals(List.of("addon:soup.v2"), List.copyOf(document.getConfigurationSection("farmersdelight_recipes#addon").getKeys(false)));
    }

    @Test void commentsStringsAndNestedTranslationKeysNeverClaimARecipeDocument() {
        for (String contents : List.of("# farmersdelight_recipes: {}\nlang: text\n",
                "lang: 'farmersdelight_recipes: {}'\n", "lang: |\n  farmersdelight_recipes: {}\n",
                "lang:\n  farmersdelight_recipes: translated text\n",
                "language:\n  <<: {farmersdelight_recipes: text}\n",
                "\"<<\": {farmersdelight_recipes: text}\n", "another_plugins_recipes: {}\n")) {
            assertFalse(RecipeDocumentProbe.ownsRecipeRoots(contents), contents);
        }
    }

    @Test void topLevelMergesAndAnchoredRootKeysAreRecognized() {
        for (String contents : List.of("defaults: &recipe {farmersdelight_recipes: {}}\n<<: *recipe\n",
                "defaults: &recipe {farmersdelight_recipes: {}}\nother: &other {lang: text}\n<<: [*other, *recipe]\n",
                "? &root farmersdelight_recipes\n: {}\n")) {
            assertTrue(RecipeDocumentProbe.ownsRecipeRoots(contents), contents);
        }
        assertTimeout(Duration.ofSeconds(2), () -> assertFalse(RecipeDocumentProbe.ownsRecipeRoots("&root {<<: *root, lang: text}")));
    }

    @Test void existingFactoryAndOwnedAuxiliaryRootsRemainEligible() {
        for (String root : List.of("config_factory", "config-factory", "config_factories", "config-factories"))
            assertTrue(RecipeDocumentProbe.ownsRecipeRoots("'" + root + "#factory': {}\n"));
        for (PackSection kind : PackSection.values()) {
            assertTrue(RecipeDocumentProbe.ownsRecipeRoots(kind.sectionId() + ": {}\n"));
            assertTrue(RecipeDocumentProbe.ownsRecipeRoots(kind.rootKey() + ": {}\n"));
        }
    }

    @Test void mixedDocumentsRemainStrictAndMultipleDocumentStreamsCannotHideOwnedRecipes() {
        String mixed = "lang:\n  text: first\n  text: second\nfarmersdelight_recipes: {}\n";
        assertThrows(InvalidConfigurationException.class, () -> RecipeDocumentProbe.parse(SOURCE, mixed, false));
        String stream = "lang: text\n---\nfarmersdelight_recipes: {}\n";
        assertTrue(RecipeDocumentProbe.ownsRecipeRoots(stream));
        assertThrows(InvalidConfigurationException.class, () -> RecipeDocumentProbe.parse(SOURCE, stream, false));
    }

    @Test void probingDoesNotRemoveUnknownFieldsBeforeTheNativeSchemaValidatesThem() throws Exception {
        var document = RecipeDocumentProbe.parse(SOURCE,
                "farmersdelight_recipes: {addon:soup: {station: cooking_pot, unknown_option: true}}\n", false);
        var recipe = document.getConfigurationSection("farmersdelight_recipes").getConfigurationSection("addon:soup");
        assertTrue(recipe.getBoolean("unknown_option"));
        assertThrows(IllegalArgumentException.class, () -> NativeRecipeSchema.normalizePot(recipe));
    }

    @Test void invalidYamlErrorsCarryTheSourceAndDoNotBecomeAnIgnoredForeignDocument() {
        var failure = assertThrows(InvalidConfigurationException.class,
                () -> RecipeDocumentProbe.parse(SOURCE, "farmersdelight_recipes: [\n", false));
        assertTrue(failure.getMessage().contains(SOURCE.toString()));
    }
}
