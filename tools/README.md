# Repository contract checks

Three checks that enforce the machine-checkable half of this project's maintenance contracts. They run in CI
(the `lint` job in `.github/workflows/ci.yml`) on every push and pull request.

| Check | Guards |
| --- | --- |
| `strip_ce_comments.py --check` | Shipped CraftEngine configuration under `src/main/resources/craftengine/**/configuration/` carries no comments. Field references live in the wiki, not in the data files. |
| `meal_icons.py --check` | Every `configuration/meal_icons.yml` entry corresponds to a `farmersdelight_recipes` cooking-pot output, and every such output has an entry with a resolvable 16x16 texture. |
| `check_lang_keys.py` | Java language keys and named placeholders agree in both plugin locales. English item names and CE English translations agree; Chinese names exist in the three Chinese client locales and agree with CE's Chinese translations. English fallback values do not count as Chinese translations. |

All three are read-only with `--check` / `--quiet` and exit non-zero on a real problem, so no separate assertion
is needed. They need PyYAML (`python -m pip install pyyaml`).

```bash
python tools/strip_ce_comments.py --check
python tools/meal_icons.py --check
python tools/check_lang_keys.py --quiet
```

`python tools/test_lang_keys.py -q` includes seven cases covering untranslated item names, missing client/server keys, conflicting translations and unbound message placeholders.

`python tools/test_meal_icons.py` checks station filtering, suffixed recipe roots and custom cooking-pot groups.
`generate-fluid-content.py` generates tank item/block definitions only; it leaves workstation recipes in
`farmersdelight_fluids/configuration/fluid_recipes.yml` untouched.

`python tools/check_content_pack.py --quiet` checks the reviewed independent content delivery contract in
`content-pack-contract.json` at each recorded Minecraft version boundary. Required player IDs must have
item definitions, custom visuals, reachable category entries (including CraftEngine item-category
injection), required block definitions and acquisition recipe outputs. Internal GUI, renderer and fluid
level IDs are checked as dependencies but do not count as contracted player items. The contract is reviewed
data; the check never regenerates it from the files being checked, so deleting an entire feature cannot
silently shrink the expected inventory.

The check resolves local templates and arguments, both `block` and `blocks`, `config_factory` instances,
version comparisons/ranges/fallbacks, CE recipe outputs and native workstation recipes. Models and textures
are resolved across the two delivery packs, including inherited model parents and generated models.
Vanilla resources are client dependencies rather than files that must be copied into our packs. Unknown
configuration sections, templates, item/model/recipe fields and ingredient shapes fail explicitly.
CraftEngine's general settings, behaviors and world-generation semantics still require native runtime
validation; this tool does not claim to reproduce their full parser or verify client rendering.

`python tools/test_content_pack.py` exercises successful closure and failure fixtures for a removed whole
feature, missing blocks/recipes/parent models/textures, unreachable categories, malformed matching/output
fields, model/template cycles, duplicate YAML keys and version-gated category injection. Source recipe
files are checked in the source checkout or at their packaged `farmersdelight/configuration` locations.
Use `--root` to check extracted delivery packs, `--version` for one boundary, or `--json` for a detailed report.

Without `--check`, `strip_ce_comments.py` and `meal_icons.py` rewrite the files instead of reporting. That is
the intended way to fix what they find; both keep existing order and only add, prune or de-comment.

## Scope

These copies are scoped to this repository. The monorepo parent directory holds workspace-level copies of the
same three tools that check every module in one pass:

* `strip_ce_comments.py` there also walks the addons' craftengine packs and the standalone packs under `packs/`;
* `meal_icons.py` there also covers the `crabbersdelight`, `brewinandchewin`, `endsdelight`, `corndelight` and
  `festivaldelicacies` namespaces;
* `check_lang_keys.py` there also checks the addons' language files and resolves the cross-module
  `<namespace>.<suffix>` key form.

The addons' packs and language files live in their own repositories, so a change here cannot affect them; the
split is what makes these checks runnable in this repository's CI at all.

## Related tools that stay workspace-level

`check_ce_symbols.py`, `check_item_names.py`, `check_corndelight_pack.py`, `check_endsdelight_pack.py` and
`audit_jar_backdoor.py` need inputs that are not in this repository (CraftEngine release jars, the original
mod's language files, the standalone packs). They stay in the monorepo parent directory's `tools/`.
