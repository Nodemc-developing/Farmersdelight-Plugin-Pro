import json
import tempfile
import unittest
from pathlib import Path

import yaml

from check_lang_keys import content_language_problems, placeholder_problems


class ContentLanguageTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.pack = Path(self.folder.name)
        self.lang = self.pack / "resourcepack/assets/farmersdelight/lang"
        self.lang.mkdir(parents=True)
        self.key = "block.farmersdelight.red_mushroom_crate"
        self.write_locale("en_us", "Red Mushroom Crate")
        for locale in ("zh_cn", "zh_tw", "zh_hk"):
            self.write_locale(locale, "红蘑菇箱")
        self.translations = self.pack / "configuration/translations.yml"
        self.translations.parent.mkdir()
        self.write_ce("红蘑菇箱")

    def write_locale(self, locale, value):
        (self.lang / f"{locale}.json").write_text(json.dumps({self.key: value}, ensure_ascii=False), encoding="utf-8")

    def write_ce(self, value):
        self.translations.write_text(yaml.safe_dump({"translations": {"en": {self.key: "Red Mushroom Crate"},
            "zh_cn": {self.key: value}}}, allow_unicode=True), encoding="utf-8")

    def test_client_and_server_localization_agree(self):
        self.assertEqual([], content_language_problems(self.pack))

    def test_english_fallback_is_not_a_chinese_translation(self):
        self.write_locale("zh_cn", "Red Mushroom Crate")
        self.write_ce("Red Mushroom Crate")
        self.assertTrue(any("missing Chinese item name" in problem for problem in content_language_problems(self.pack)))

    def test_missing_key_does_not_pass_using_english_fallback(self):
        (self.lang / "zh_tw.json").write_text("{}", encoding="utf-8")
        self.assertTrue(any("zh_tw: missing" in problem for problem in content_language_problems(self.pack)))

    def test_server_translation_must_not_override_client_with_english(self):
        self.write_ce("Red Mushroom Crate")
        self.assertTrue(any("differs from client pack" in problem for problem in content_language_problems(self.pack)))

    def test_chinese_text_cannot_be_shipped_as_an_english_item_name(self):
        self.write_locale("en_us", "红蘑菇箱")
        self.assertTrue(any("invalid English item name" in problem for problem in content_language_problems(self.pack)))

    def test_missing_ce_english_item_name_is_reported(self):
        data = yaml.safe_load(self.translations.read_text(encoding="utf-8"))
        data["translations"]["en"].clear()
        self.translations.write_text(yaml.safe_dump(data, allow_unicode=True), encoding="utf-8")
        self.assertTrue(any("CE en: missing" in problem for problem in content_language_problems(self.pack)))

    def test_translated_messages_cannot_add_unbound_placeholders(self):
        english = {"reload": "Loaded {total}; split {split}; issues {issues}"}
        chinese = {"reload": "加载 {total}；拆分 {split}"}
        self.assertEqual(["reload: named placeholders differ between en_us and zh_cn"], placeholder_problems(english, chinese))
        english["reload"] = "Split {split}; loaded {total}"
        self.assertEqual([], placeholder_problems(english, chinese))


if __name__ == "__main__":
    unittest.main()
