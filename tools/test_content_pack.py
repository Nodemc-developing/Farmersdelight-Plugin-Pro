import copy
import json
import tempfile
import unittest
from pathlib import Path

import yaml

from check_content_pack import Check, factory_documents, load_contract, select_version


class IndependentContentTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.pack = self.root / "farmersdelight"
        self.assets = self.pack / "resourcepack/assets/farmersdelight"
        (self.pack / "configuration").mkdir(parents=True)
        (self.pack / "pack.yml").write_text("namespace: farmersdelight\nenable: true\n")
        self.contract = {"schema": 1, "packs": ["farmersdelight"],
                         "category_roots": ["farmersdelight:default"], "versions": ["26.3"],
                         "player_items": {"farmersdelight:wooden_basket": {"block": True, "recipe": True}}}
        self.document = {
            "templates": {"farmersdelight:block_item": {
                "model": "${__NAMESPACE__}:block/${__ID__}",
                "behavior": {"type": "block_item", "block": "${__NAMESPACE__}:${__ID__}"}}},
            "items": {
                "farmersdelight:wooden_basket": {"template": "farmersdelight:block_item"},
                "farmersdelight:entity_renderer/helper": {"model": "farmersdelight:block/wooden_basket"}},
            "block": {"farmersdelight:wooden_basket": {"states": {
                "model": {"path": "farmersdelight:block/wooden_basket"}}}},
            "categories": {
                "farmersdelight:default": {"list": ["#farmersdelight:decorations"]},
                "farmersdelight:decorations": {"list": ["farmersdelight:wooden_basket"]}},
            "recipes": {"farmersdelight:wooden_basket": {"type": "shaped", "pattern": ["S"],
                "ingredients": {"S": "minecraft:stick"}, "result": {"id": "farmersdelight:wooden_basket"}}},
        }
        self.write_json("models/block/wooden_basket.json", {
            "parent": "farmersdelight:block/template_basket", "textures": {"side": "farmersdelight:block/basket_side"}})
        self.write_json("models/block/template_basket.json", {"parent": "minecraft:block/block",
                                                               "textures": {"particle": "#side"}})
        self.write_bytes("textures/block/basket_side.png", b"fixture texture")

    def write_bytes(self, relative, data):
        path = self.assets / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def write_json(self, relative, value):
        self.write_bytes(relative, json.dumps(value).encode())

    def report(self, version="26.3"):
        (self.pack / "configuration/content.yml").write_text(yaml.safe_dump(self.document), encoding="utf-8")
        return Check(self.root, self.contract, version).run()

    def assert_failure(self, substring):
        self.assertTrue(any(substring in error for error in self.report()["errors"]), substring)

    def test_registry_template_parent_and_texture_closure(self):
        report = self.report()
        self.assertEqual([], report["errors"])
        self.assertEqual(1, report["player_items"])
        self.assertEqual(2, report["items"], "renderer helpers are dependencies, not formal player items")
        self.assertGreater(report["model_visits"], 1)

    def test_removed_entire_feature_still_fails_fixed_contract(self):
        self.document = {"categories": {"farmersdelight:default": {"list": []}}}
        self.assert_failure("required player item is missing")
        self.assert_failure("required block is missing")
        self.assert_failure("required acquisition recipe output is missing")

    def test_block_definition_must_be_shipped(self):
        self.document.pop("block")
        self.assert_failure("missing blocks reference")

    def test_both_renderer_field_spellings_check_item_dependencies(self):
        states = self.document["block"]["farmersdelight:wooden_basket"]["states"]
        for field in ("entity_renderer", "entity-renderer"):
            states[field] = {"item": "farmersdelight:missing_renderer"}
            self.assert_failure("missing items reference farmersdelight:missing_renderer")
            del states[field]

    def test_disconnected_category_does_not_count_as_accessible(self):
        self.document["categories"]["farmersdelight:default"]["list"] = []
        self.assert_failure("absent from reachable categories")

    def test_missing_recipe_is_not_replaced_by_existing_item_definition(self):
        self.document.pop("recipes")
        self.assert_failure("required acquisition recipe output is missing")

    def test_parent_texture_dependency_must_resolve(self):
        (self.assets / "textures/block/basket_side.png").unlink()
        self.assert_failure("missing texture farmersdelight:block/basket_side")

    def test_missing_parent_model_is_reported(self):
        (self.assets / "models/block/template_basket.json").unlink()
        self.assert_failure("missing model farmersdelight:block/template_basket")

    def test_vanilla_models_are_not_external_asset_failures(self):
        self.document["items"]["farmersdelight:entity_renderer/helper"]["model"] = "minecraft:item/stick"
        self.assertEqual([], self.report()["errors"])

    def test_parent_cycle_is_reported(self):
        self.write_json("models/block/template_basket.json", {"parent": "farmersdelight:block/wooden_basket"})
        self.assert_failure("model parent cycle")

    def test_unknown_model_fields_are_not_silently_ignored(self):
        self.document["items"]["farmersdelight:wooden_basket"] = {"model": {"paht": "farmersdelight:block/wooden_basket"}}
        self.assert_failure("unknown model fields")
        self.assert_failure("model path is missing")

    def test_unknown_recipe_output_shape_is_reported(self):
        self.document["recipes"]["farmersdelight:wooden_basket"]["result"] = {"itm": "farmersdelight:wooden_basket"}
        self.assert_failure("output has no id/item field")

    def test_unknown_matching_fields_are_not_silently_accepted(self):
        self.document["recipes"]["farmersdelight:wooden_basket"]["ingredients"]["S"] = {
            "item": "minecraft:stick", "exact-componentz": {}}
        self.assert_failure("unsupported ingredient/output fields")

    def test_player_item_requires_custom_visual_definition(self):
        self.document["items"]["farmersdelight:wooden_basket"] = {"material": "minecraft:stick"}
        self.assert_failure("no custom model or texture")

    def test_unknown_recipe_type_is_reported(self):
        self.document["recipes"]["farmersdelight:wooden_basket"]["type"] = "shapless"
        self.assert_failure("unsupported CE recipe type")

    def test_food_is_invalid_for_crafting_but_valid_for_furnace_recipe(self):
        recipe = self.document["recipes"]["farmersdelight:wooden_basket"]
        recipe["category"] = "food"
        self.assert_failure("invalid shaped recipe category 'food'")
        self.document["recipes"]["farmersdelight:wooden_basket"] = {
            "type": "smelting", "category": "FOOD", "ingredient": "minecraft:stick",
            "result": {"id": "farmersdelight:wooden_basket"}}
        self.assertEqual([], self.report()["errors"])
        self.document["recipes"]["farmersdelight:wooden_basket"]["category"] = "equipment"
        self.assert_failure("invalid smelting recipe category 'equipment'")

    def test_missing_and_unknown_external_templates_are_reported(self):
        self.document["items"]["farmersdelight:wooden_basket"]["template"] = "default:unreviewed"
        self.assert_failure("missing or unsupported template default:unreviewed")

    def test_template_cycle_is_reported(self):
        self.document["templates"]["farmersdelight:block_item"]["template"] = "farmersdelight:block_item"
        self.assert_failure("template cycle")

    def test_version_gated_item_and_native_category_injection(self):
        self.contract["player_items"]["farmersdelight:copper_knife"] = {"since": "1.21.9", "recipe": True}
        self.document["items"]["$$>=1.21.9"] = {"farmersdelight:copper_knife": {
            "model": "minecraft:item/stick", "category": "farmersdelight:decorations"}}
        self.document["recipes"]["$$>=1.21.9"] = {"farmersdelight:copper_knife": {
            "type": "shapeless", "ingredients": ["minecraft:copper_ingot"], "result": {"id": "farmersdelight:copper_knife"}}}
        self.assertEqual([], self.report("1.21.8")["errors"])
        self.assertEqual([], self.report("1.21.9")["errors"])
        self.assertEqual([], self.report("26.3")["errors"])

    def test_native_recipe_outputs_and_choice_inputs_use_same_registry(self):
        self.document.pop("recipes")
        self.document["farmersdelight_recipes#test"] = {"basket": {
            "station": "cutting_board", "input": {"item": "minecraft:stick|farmersdelight:wooden_basket"},
            "output": [{"item": "farmersdelight:wooden_basket", "chance": 0.5}]}}
        self.assertEqual([], self.report()["errors"])

    def test_extracted_delivery_uses_packaged_native_recipe_locations(self):
        self.document.pop("recipes")
        self.contract["source_recipe_files"] = ["../recipes/cooking_pot_recipes.yml"]
        (self.pack / "configuration/cooking_pot_recipes.yml").write_text(yaml.safe_dump({
            "farmersdelight_recipes": {"basket": {"station": "cooking_pot", "input": {"items": ["minecraft:stick"]},
                "output": {"item": "farmersdelight:wooden_basket"}}}}), encoding="utf-8")
        self.assertEqual([], self.report()["errors"])

    def test_missing_separately_packaged_recipe_file_is_reported(self):
        self.contract["source_recipe_files"] = ["../recipes/cooking_pot_recipes.yml"]
        self.assert_failure("missing separately packaged source recipe file")

    def test_native_duplicate_across_files_fails_even_when_contents_match(self):
        recipe = {"station": "cutting_board", "input": {"item": "minecraft:stick"},
                  "output": {"item": "minecraft:stick"}}
        self.document["farmersdelight_recipes"] = {"oak_furniture": recipe}
        (self.pack / "configuration/second.yml").write_text(yaml.safe_dump({
            "farmersdelight_recipes#secondary": {"farmersdelight:oak_furniture": recipe}}), encoding="utf-8")
        self.assert_failure("duplicate farmersdelight_recipes definition farmersdelight:oak_furniture")

    def test_registry_duplicate_across_files_is_not_silently_overwritten(self):
        (self.pack / "configuration/second.yml").write_text(yaml.safe_dump({
            "items": {"farmersdelight:wooden_basket": {"template": "farmersdelight:block_item"}}}), encoding="utf-8")
        self.assert_failure("duplicate items definition farmersdelight:wooden_basket")

    def test_missing_item_in_native_choices_is_reported(self):
        self.document["farmersdelight_recipes"] = {"test": {"station": "cutting_board",
            "input": {"item": "minecraft:stick|farmersdelight:missing"}, "output": {"item": "minecraft:stick"}}}
        self.assert_failure("missing items reference farmersdelight:missing")

    def test_registered_tool_actions_are_not_mistaken_for_items(self):
        self.document["farmersdelight_recipes"] = {"test": {"station": "cutting_board",
            "input": {"item": "minecraft:stick", "tools": ["farmersdelight:axe_strip", "farmersdelight:axe_dig"]},
            "output": {"item": "minecraft:stick"}}}
        self.assertEqual([], self.report()["errors"])
        self.document["farmersdelight_recipes"]["test"]["input"]["item"] = "farmersdelight:axe_strip"
        self.assert_failure("missing items reference farmersdelight:axe_strip")

    def test_duplicate_yaml_registry_is_rejected(self):
        path = self.pack / "configuration/content.yml"
        path.write_text("items: {}\nitems: {}\n")
        report = Check(self.root, self.contract, "26.3").run()
        self.assertTrue(any("duplicate YAML key" in error for error in report["errors"]))

    def test_generated_models_have_real_texture_dependencies(self):
        self.document["items"]["farmersdelight:entity_renderer/helper"]["model"] = {
            "path": "farmersdelight:generated/helper", "generation": {
                "parent": "minecraft:item/generated", "textures": {"layer0": "farmersdelight:missing"}}}
        self.assert_failure("missing texture farmersdelight:missing")

    def test_parameterized_local_template_dependencies(self):
        self.document["templates"]["farmersdelight:look"] = {"states": {"model": {"path": "${basket_model}"}}}
        self.document["block"]["farmersdelight:wooden_basket"] = {"template": "farmersdelight:look",
            "arguments": {"basket_model": "farmersdelight:block/wooden_basket"}}
        self.assertEqual([], self.report()["errors"])
        self.document["block"]["farmersdelight:wooden_basket"]["arguments"]["basket_model"] = "farmersdelight:missing"
        self.assert_failure("missing model farmersdelight:missing")

    def test_range_fallback_and_factory_instance_expansion(self):
        document = {"config_factory": {"blueprint": {"items": {"farmersdelight:${name}": {"model": "minecraft:item/stick"}}},
                      "$$>=1.21.9": {"instances": [{"name": "copper"}]},
                      "$$fallback": {"instances": [{"name": "iron"}]}}}
        self.assertIn("farmersdelight:copper", factory_documents(select_version(document, "26.3"))[1]["items"])
        self.assertIn("farmersdelight:iron", factory_documents(select_version(document, "1.21.8"))[1]["items"])
        ranges = {"$$26.1~26.2": ["in-range"], "$$fallback": ["fallback"]}
        self.assertEqual(["in-range"], select_version(ranges, "26.1"))
        self.assertEqual(["in-range"], select_version(ranges, "26.2"))
        self.assertEqual(["fallback"], select_version(ranges, "26.3"))
        with self.assertRaisesRegex(ValueError, "unknown version condition"):
            select_version({"$$garbage": {}}, "26.3")

    def test_contract_unknown_fields_require_review(self):
        contract = copy.deepcopy(self.contract)
        contract["player_items"]["farmersdelight:wooden_basket"]["recpie"] = True
        path = self.root / "contract.json"
        path.write_text(json.dumps(contract))
        with self.assertRaisesRegex(ValueError, "unknown item contract fields"):
            load_contract(path)


if __name__ == "__main__":
    unittest.main()
