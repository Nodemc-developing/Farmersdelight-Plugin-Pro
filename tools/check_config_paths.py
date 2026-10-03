"""Check that every literal config path the code reads actually exists in a shipped config file.

Why this matters: `ConfigLookup` walks aliases in order and falls back to the compiled-in default when none
of them is set. So a typo'd or renamed path does not fail loudly — the feature silently uses its default and
the operator's edited value is ignored. That is the "config reachability" defect class.

What it checks:
  * every string literal passed to the plugin's `getConfig*` / `getFirstConfigSection` helpers, and to
    `ConfigSectionReader`;
  * resolved against the union of the bundled default files in src/main/resources and the main
    config's runtime compatibility view. Known option names and semantic aliases are read from
    PapersDelightConfigFormat.java; an arbitrary hyphen/underscore replacement is not accepted.

A call site is satisfied when *at least one* of its path aliases exists — the aliases are deliberate
fallbacks for migrated keys, so an old name that no longer ships is fine as long as a current one exists.
Registry sections are exempt: those are registry keys an operator adds, not settings we ship.

Usage:
    python tools/check_config_paths.py            # from Farmersdelight-Plugin-Pro/
    python tools/check_config_paths.py --quiet
Exit code 0 when clean, 1 when a call site has no reachable path.
"""
from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("pyyaml is required: python -m pip install pyyaml")

FD_PACKAGE = "com.huidu.farmersdelight"
BUNDLED = ["config.yml", "gui.yml", "drops.yml", "world-data.yml", "display-overrides.yml"]
FORMAT_SOURCE = Path("src/main/java/com/huidu/farmersdelight/config/PapersDelightConfigFormat.java")

# Helpers whose literal arguments are config paths.
HELPERS = (
    "getConfigBoolean", "getConfigInt", "getConfigDouble", "getConfigStringList",
    "getFirstConfigSection",
)
# getConfigBoolean(true, "a", "b") -> the default comes first, so paths are the string literals after comma 1.
FIRST_ARG_IS_DEFAULT = {"getConfigBoolean", "getConfigInt", "getConfigDouble"}

CALL_RE = re.compile(r"\b(" + "|".join(HELPERS) + r")\s*\(")
STRING_RE = re.compile(r'"([^"]*)"')
SKIP_PARTS = ("\\build\\", "/build/", "\\Reference\\", "/Reference/")

# A call site that intentionally reads a key no shipped file defines marks itself with this comment, and the
# reason must be written next to it. Deliberately an in-source marker rather than an allowlist file: the
# exemption then sits where a reader will see it, and there is no second list to keep in sync.
EXEMPT_MARKER = "config-path-check: no shipped key, on purpose"
EXEMPT_LOOKBACK_LINES = 8


def is_exempt(text: str, call_start: int) -> bool:
    """True when the call is annotated as an intentional unreachable read."""
    lines = text[:call_start].splitlines()
    return any(EXEMPT_MARKER in line for line in lines[-EXEMPT_LOOKBACK_LINES:])


def call_arguments(text: str, open_paren: int) -> str:
    """Return the text between the matching parentheses.

    Needed because a naive non-greedy `\\((.*?)\\)` stops at the first closing paren, which truncates the
    alias list of a call whose arguments span several lines — and a truncated list produces false positives
    (it misses the alias that does ship).
    """
    depth = 0
    i = open_paren
    while i < len(text):
        char = text[i]
        if char == '"':  # skip string literals so a paren inside one cannot unbalance the scan
            i += 1
            while i < len(text) and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1:i]
        i += 1
    return text[open_paren + 1:]


def module_root() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "src" / "main" / "resources" / "config.yml").is_file():
            return parent
    sys.exit("could not locate the Farmersdelight-Plugin-Pro module root")


def load_shipped_keys(root: Path) -> set[str]:
    keys: set[str] = set()
    resources = root / "src" / "main" / "resources"
    for name in BUNDLED:
        path = resources / name
        if not path.is_file():
            continue
        doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        walk(doc, "", keys)
        if name == "config.yml":
            option_names, aliases = load_runtime_schema(root)
            walk(runtime_config_view(doc, option_names, aliases), "", keys)
    return keys


def load_runtime_schema(root: Path) -> tuple[set[str], list[tuple[str, str]]]:
    """Read the actual finite option whitelist and PATHS map, failing on unsupported schema syntax.

    The small parser accepts the string literals and List.of loops used by the Java initializer.
    It deliberately fails if that initializer changes shape, so new aliases cannot silently disappear
    from the check or turn into unrestricted spelling normalization.
    """
    text = (root / FORMAT_SOURCE).read_text(encoding="utf-8")
    options = re.search(r'OPTION_NAMES\s*=\s*Set\.of\(\("""(.*?)"""\)', text, re.S)
    initializer = re.search(r'\bstatic\s*\{(.*?)^    \}', text, re.S | re.M)
    if options is None or initializer is None:
        raise ValueError("cannot read PapersDelightConfigFormat option names/PATHS initializer")
    aliases: list[tuple[str, str]] = []

    def expression(raw: str, variables: dict[str, str]) -> str:
        tokens = re.split(r'\s*\+\s*(?=(?:[^"]*"[^"]*")*[^"]*$)', raw.strip())
        result: list[str] = []
        for token in tokens:
            token = token.strip()
            if re.fullmatch(r'"(?:[^"\\]|\\.)*"', token):
                result.append(json.loads(token))
            elif token in variables:
                result.append(variables[token])
            else:
                raise ValueError(f"unsupported PATHS expression: {raw}")
        return "".join(result)

    def statements(body: str, variables: dict[str, str]) -> None:
        body = body.strip()
        while body:
            loop = re.match(r'for\s*\(String\s+(\w+)\s*:\s*List\.of\((.*?)\)\)\s*\{([^{}]*)\}', body, re.S)
            put = re.match(r'PATHS\.put\((.*?)\);', body, re.S)
            if loop:
                for value in split_arguments(loop.group(2)):
                    nested = dict(variables)
                    nested[loop.group(1)] = expression(value, variables)
                    statements(loop.group(3), nested)
                body = body[loop.end():].strip()
            elif put:
                arguments = split_arguments(put.group(1))
                if len(arguments) != 2:
                    raise ValueError("PATHS.put must contain exactly two string expressions")
                aliases.append(tuple(expression(value, variables) for value in arguments))
                body = body[put.end():].strip()
            else:
                raise ValueError(f"unsupported PATHS initializer statement: {body[:100]}")

    statements(initializer.group(1), {})
    return set(options.group(1).split()), aliases


def option_name(key: str, public: bool, names: set[str]) -> str:
    if ":" in key or key.startswith("#"):
        return key
    legacy = key.replace("_", "-")
    return legacy.replace("-", "_") if public and legacy in names else legacy if legacy in names else key


def rename_known(value, public: bool, names: set[str]):
    if isinstance(value, list):
        return [rename_known(item, public, names) for item in value]
    if not isinstance(value, dict):
        return copy.deepcopy(value)
    result: dict = {}
    # Match Java's two-pass precedence: explicitly spelled target keys beat aliases.
    for original_pass in (True, False):
        for key, item in value.items():
            key = str(key)
            renamed = option_name(key, public, names)
            if (renamed == key) != original_pass:
                continue
            item = rename_known(item, public, names)
            if isinstance(result.get(renamed), dict) and isinstance(item, dict):
                merge(result[renamed], item, False)
            elif renamed not in result:
                result[renamed] = item
    return result


def path_value(document: dict, path: str):
    current = document
    for key in path.split("."):
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def put_path(document: dict, path: str, value, overwrite: bool = True) -> None:
    keys = path.split(".")
    current = document
    for key in keys[:-1]:
        child = current.get(key)
        if not isinstance(child, dict):
            if child is not None and not overwrite:
                return
            child = {}
            current[key] = child
        current = child
    if overwrite or keys[-1] not in current:
        current[keys[-1]] = copy.deepcopy(value)


def remove_path(document: dict, path: str):
    keys = path.split(".")
    current = document
    for key in keys[:-1]:
        current = current.get(key) if isinstance(current, dict) else None
    return current.pop(keys[-1], None) if isinstance(current, dict) else None


def merge(target: dict, source: dict, overwrite: bool) -> None:
    for key, value in source.items():
        if isinstance(target.get(key), dict) and isinstance(value, dict):
            merge(target[key], value, overwrite)
        elif overwrite or key not in target:
            target[key] = copy.deepcopy(value)


def move_path(document: dict, old: str, new: str) -> None:
    value = remove_path(document, old)
    if value is None:
        return
    existing = path_value(document, new)
    if isinstance(existing, dict) and isinstance(value, dict):
        merge(existing, value, False)
    else:
        put_path(document, new, value, False)


def is_number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def canonical_config(document: dict, names: set[str], aliases: list[tuple[str, str]]) -> dict:
    result = rename_known(document, True, names)
    for old, new in aliases:
        move_path(result, old, new)
    heat = result.get("heat_sources")
    if isinstance(heat, dict):
        fields = copy.deepcopy(heat)
        entries = []
        for entry in fields.pop("entries", []):
            if isinstance(entry, dict):
                entry = copy.deepcopy(entry)
                for old, new in (("vanilla_block", "material"), ("custom_block", "ce_block"),
                                 ("custom_block_tag", "ce_block_tag")):
                    move_path(entry, old, new)
                if "material" in entry:
                    entry["material"] = str(entry["material"]).replace("minecraft:", "").upper()
                entries.append(entry)
        result["heat_sources"] = entries
        fields = {key: value for key, value in fields.items() if value != []}
        if fields:
            put_path(result, "heat_source_legacy", fields, False)
    move_path(result, "pet_foods", "pet_food.definitions")
    interval = remove_path(result, "cooking_pot.effects.interval")
    if is_number(interval):
        put_path(result, "cooking_pot.particles.interval_ticks", max(1, min(2**31 - 1, int(interval))) * 4, False)
    for station, old in (("cutting_board", "cutting_board.display_item_spread"),
                         ("skillet", "skillet.display.item_spread")):
        spread = remove_path(result, old)
        if is_number(spread):
            put_path(result, station + ".display.stack_xz_offset", spread / 2, False)
    offset = remove_path(result, "cutting_board.default_display_offset")
    if isinstance(offset, str):
        offset = offset.split(",")
    if isinstance(offset, list) and len(offset) == 3:
        try:
            vector = [float(value) for value in offset]
        except (TypeError, ValueError):
            pass
        else:
            for axis, value in zip("xyz", vector):
                put_path(result, "cutting_board.display.translate_" + axis, value, False)
    for effect in ("comfort", "nourishment"):
        style = path_value(result, effect + "_effect.bossbar.style")
        if style is not None:
            public = str(style).upper()
            put_path(result, effect + "_effect.bossbar.style",
                     "SOLID" if public == "PROGRESS" else public.replace("NOTCHED_", "SEGMENTED_"))
    for path in ("buff.display.styles.comfort", "buff.display.styles.nourishment", "buff.display.styles",
                 "cooking_pot.effects", "skillet.effects", "stove.effects"):
        if path_value(result, path) == {}:
            remove_path(result, path)
    return result


def runtime_config_view(document: dict, names: set[str], aliases: list[tuple[str, str]]) -> dict:
    """Materialize the shipped main config's reachable paths, mirroring runtimeViewOf.

    Values only control whether a derived alias exists; this is a reachability check, not a
    replacement for Bukkit's parsing or the Java migration equivalence tests. Special derived
    sections are conditional on the same fields as configureDisplay/configureStoveDisplay.
    """
    canonical = canonical_config(document, names, aliases)
    legacy = rename_known(canonical, False, names)
    for old, new in aliases:
        value = path_value(canonical, new)
        if value is not None:
            target = ".".join(option_name(key, False, names) for key in old.split("."))
            put_path(legacy, target, rename_known(value, False, names) if isinstance(value, dict) else value)
    if isinstance(legacy.get("enchantment"), dict):
        section = copy.deepcopy(legacy["enchantment"])
        enabled = path_value(canonical, "enchantment.enable")
        if enabled is not None:
            section["enabled"] = enabled
        legacy["enchantments"] = section
    for effect in ("comfort", "nourishment"):
        section = legacy.get(effect + "_effect")
        if isinstance(section, dict):
            section = copy.deepcopy(section)
            enabled = path_value(canonical, effect + "_effect.enable")
            if enabled is not None:
                section["enabled"] = enabled
            put_path(legacy, "buff." + effect, section)
        style = path_value(canonical, effect + "_effect.bossbar.style")
        if style is not None:
            style = str(style).upper()
            put_path(legacy, "buff.display.styles." + effect + ".overlay",
                     "PROGRESS" if style == "SOLID" else style.replace("SEGMENTED_", "NOTCHED_"))
    if isinstance(canonical.get("heat_sources"), list):
        entries = []
        for entry in canonical["heat_sources"]:
            if isinstance(entry, dict):
                entry = copy.deepcopy(entry)
                for old, new in (("material", "vanilla-block"), ("vanilla_block_tag", "vanilla-block-tag"),
                                 ("ce_block", "custom-block"), ("ce_block_tag", "custom-block-tag"),
                                 ("heat_source", "heat-source")):
                    move_path(entry, old, new)
                entries.append(entry)
        put_path(legacy, "heat-sources.entries", entries)
        for field in ("tags", "vanilla-blocks", "vanilla-tags", "custom-blocks", "conductors", "conductor-tags"):
            put_path(legacy, "heat-sources." + field, [], False)
    if isinstance(canonical.get("heat_source_legacy"), dict):
        for field, value in rename_known(canonical["heat_source_legacy"], False, names).items():
            put_path(legacy, "heat-sources." + field, value)
    interval = path_value(canonical, "cooking_pot.particles.interval_ticks")
    if is_number(interval):
        put_path(legacy, "cooking-pot.effects.interval", min(2**31 - 1, (max(1, int(interval)) + 3) // 4))
    for station, target, board in (("cutting_board", "cutting-board", True), ("skillet", "skillet.display", False)):
        display = station + ".display."
        if any(path_value(canonical, display + "translate_" + axis) is not None for axis in "xyz"):
            put_path(legacy, target + (".default-display-offset" if board else ".position"), [0, 0, 0])
        scale = path_value(canonical, display + "scale")
        if board and scale is not None:
            put_path(legacy, target + ".default-display-scale", scale)
        if any(path_value(canonical, display + key) is not None for key in ("rotation_pitch", "rotation_y", "rotation_roll")):
            put_path(legacy, target + (".default-display-rotation" if board else ".rotation"), [0, 0, 0])
        spread = path_value(canonical, display + "stack_xz_offset")
        if is_number(spread):
            put_path(legacy, target + (".display-item-spread" if board else ".item-spread"), spread * 2)
    if path_value(canonical, "stove.display.slot_offsets") is None and any(
            path_value(canonical, "stove.display." + key) is not None for key in ("slot_1_x", "slot_1_z", "translate_y")):
        put_path(legacy, "stove.display.slot-offsets", ["0,0,0"] * 6)
    sounds = path_value(legacy, "cutting-board.sounds.tool-sounds")
    if isinstance(sounds, dict) and "knife" in sounds:
        knife = sounds.pop("knife")
        sounds.setdefault("#farmersdelight:tools/knives", knife)
    pet = canonical.get("pet_food")
    if isinstance(pet, dict):
        definitions = rename_known(pet.get("definitions", {}), False, names)
        if not isinstance(definitions, dict):
            definitions = {}
        for group in ("dog_food", "horse_feed"):
            if group in pet:
                definitions[group] = copy.deepcopy(pet[group])
        legacy["pet-foods"] = definitions
    merge(canonical, legacy, True)
    return canonical


def check_runtime_aliases(root: Path) -> None:
    """Regression checks for semantic aliases, conditional synthesis and typo rejection."""
    names, aliases = load_runtime_schema(root)
    document = yaml.safe_load("""
lang: zh_cn
stats: {shutdown_wait_millis: 500}
enchantment: {enable: false}
comfort_effect:
  enable: false
  bossbar: {color: BLUE, style: SEGMENTED_10}
cooking_pot:
  particles: {interval_ticks: 81, view_distance_blocks: 20}
cutting_board:
  hopper_interaction: false
  sounds: {remove_item: {volume: 0.5, pitch: 1}}
  display: {translate_x: 0.25, scale: 0.75, rotation_y: 45, stack_xz_offset: 0.1}
skillet:
  display: {translate_y: 1.1}
stove:
  display: {slot_1_x: 0.3}
heat_sources: []
container_returns:
  my-pack:foo-bar: my-pack:empty-bowl
custom_extension: {unknown_field: 1}
""")
    runtime = runtime_config_view(document, names, aliases)
    keys: set[str] = set()
    walk(runtime, "", keys)
    for path in ("language", "performance.shutdown-wait-millis", "enchantments.enabled", "buff.comfort.enabled",
                 "buff.display.styles.comfort.overlay", "cooking-pot.effects.interval",
                 "cooking-pot.effects.viewer-distance", "cutting-board.allow-hopper",
                 "cutting-board.sounds.retrieve-volume", "cutting-board.default-display-offset",
                 "cutting-board.default-display-scale", "cutting-board.default-display-rotation",
                 "cutting-board.display-item-spread", "skillet.display.position", "stove.display.slot-offsets",
                 "heat-sources.entries", "container-returns.my-pack:foo-bar", "custom_extension.unknown_field"):
        assert path in keys, f"runtime alias missing: {path}"
    assert path_value(runtime, "cooking-pot.effects.interval") == 21
    assert path_value(runtime, "buff.display.styles.comfort.overlay") == "NOTCHED_10"
    for wrong in ("cooking-pot.effects.intervall", "performance.shutdown-wait-milliseconds", "enchantments.enables",
                  "cutting-board.sounds.retrieve-volume-extra", "skillet.display.no-such-setting",
                  "container-returns.my_pack:foo_bar", "custom-extension.unknown-field"):
        assert wrong not in keys, f"invalid path was accepted: {wrong}"
    empty_keys: set[str] = set()
    walk(runtime_config_view({}, names, aliases), "", empty_keys)
    assert not empty_keys, "derived sections must not exist without their source fields"
    conflict = runtime_config_view({"cooking-pot": {"tick-budget": 99}, "cooking_pot": {"tick_budget": 7}}, names, aliases)
    assert path_value(conflict, "cooking-pot.tick-budget") == 7


def walk(node, prefix: str, out: set[str]) -> None:
    if not isinstance(node, dict):
        if prefix:
            out.add(prefix)
        return
    for key, value in node.items():
        path = f"{prefix}.{key}" if prefix else str(key)
        out.add(path)
        walk(value, path, out)


def split_arguments(args: str) -> list[str]:
    """Split a call's arguments on commas at parenthesis depth zero."""
    parts: list[str] = []
    depth = 0
    current: list[str] = []
    i = 0
    while i < len(args):
        char = args[i]
        if char == '"':
            current.append(char)
            i += 1
            while i < len(args) and args[i] != '"':
                current.append(args[i])
                i += 2 if args[i] == "\\" else 1
            if i < len(args):
                current.append(args[i])
        elif char in "([{":
            depth += 1
            current.append(char)
        elif char in ")]}":
            depth -= 1
            current.append(char)
        elif char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
        i += 1
    if current:
        parts.append("".join(current))
    return parts


def literal_paths(helper: str, args: str) -> list[str]:
    """The path literals of one call, or [] when the paths are computed rather than written down.

    The default argument is a literal number or boolean for most helpers, so it must not be mistaken for a
    path — but it is a *string* for some callers, hence the split-then-inspect approach rather than simply
    dropping the first literal.
    """
    parts = split_arguments(args)
    if helper in FIRST_ARG_IS_DEFAULT and parts:
        first = parts[0]
        if not STRING_RE.search(first):  # a numeric/boolean default, e.g. "8" or "true"
            parts = parts[1:]
    # A string fragment inside a concatenation is not a complete config path.
    # Computed paths are outside this literal checker, as documented above.
    values = [json.loads(part.strip()) for part in parts
              if re.fullmatch(r'"(?:[^"\\]|\\.)*"', part.strip())]
    return [v for v in values if v]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--self-test", action="store_true", help="also check runtime aliases and typo rejection")
    args = parser.parse_args()

    root = module_root()
    if args.self_test:
        assert literal_paths("getConfigInt", '8, "section.value", "legacy.value"') == ["section.value", "legacy.value"]
        assert literal_paths("getConfigDouble", 'fallback, "section." + field, "legacy." + name') == []
        assert literal_paths("getFirstConfigSection", 'computedPath, "section.literal"') == ["section.literal"]
        assert literal_paths("getFirstConfigSection", '"section." + field') == []
        check_runtime_aliases(root)
    shipped = load_shipped_keys(root)

    java_root = root / "src" / "main" / "java"
    call_sites = 0
    unreachable: list[str] = []
    for path in java_root.rglob("*.java"):
        text_path = str(path)
        if any(part in text_path for part in SKIP_PARTS):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        rel = path.relative_to(root)
        for match in CALL_RE.finditer(text):
            helper = match.group(1)
            raw_args = call_arguments(text, match.end() - 1)
            paths = literal_paths(helper, raw_args)
            if not paths:
                continue  # computed path, not a literal: out of scope
            call_sites += 1
            if not any(p in shipped for p in paths):
                if is_exempt(text, match.start()):
                    continue
                lineno = text[:match.start()].count("\n") + 1
                unreachable.append(f"{rel}:{lineno}: {helper}({', '.join(repr(p) for p in paths)})")

    if not args.quiet:
        print(f"config path check @ {root}")
        print(f"  reachable keys: {len(shipped)} from {', '.join(BUNDLED)} and the main config runtime view")
        print(f"  literal call sites checked: {call_sites}")

    if unreachable:
        print(f"\nno literal path is reachable from shipped config files/runtime aliases ({len(unreachable)}):")
        for item in unreachable:
            print("  " + item)

    print(f"\nproblems: {len(unreachable)}")
    return 1 if unreachable else 0


if __name__ == "__main__":
    raise SystemExit(main())
