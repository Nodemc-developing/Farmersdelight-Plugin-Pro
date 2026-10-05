import tempfile
import unittest
from pathlib import Path

from meal_icons import result_ids


class MealRecipeOutputTest(unittest.TestCase):
    def read(self, text):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "recipes.yml"
            path.write_text(text, encoding="utf-8")
            return result_ids(path)

    def test_only_cooking_pot_outputs_generate_meal_icons(self):
        self.assertEqual(["example:soup"], self.read("""
farmersdelight_recipes:
  meal:
    station: cooking_pot
    output: {item: "example:soup", count: 3}
  cutting:
    station: cutting_board
    output: [{item: "example:straw"}]
  tank:
    station: fluid_tank
    output: {item: "example:milk"}
"""))

    def test_suffixed_roots_and_custom_groups_use_the_same_output_shape(self):
        self.assertEqual(["example:soup", "minecraft:baked_potato"], self.read("""
farmersdelight_recipes#chef:
  meal:
    station: cooking_pot
    group: example:large_pot
    output: {item: "example:soup"}
farmersdelight_recipes#other:
  second:
    station: cooking_pot
    output: {item: "minecraft:baked_potato"}
"""))

    def test_missing_outputs_and_unclaimed_sections_are_ignored(self):
        self.assertEqual([], self.read("""
unclaimed_recipes:
  meal:
    station: cooking_pot
    output: {item: "example:soup"}
farmersdelight_recipes:
  missing:
    station: cooking_pot
"""))


if __name__ == "__main__":
    unittest.main()
