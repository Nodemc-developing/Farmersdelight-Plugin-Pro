"""Validate the independent content delivery against its reviewed player-item contract.

Checks registry definitions, reachable categories, acquisition recipes, template/model
references and model/texture dependencies. This is a static repository check, not a
replacement for CraftEngine runtime or client testing. PyYAML is the only dependency.
"""
from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
VARIABLE = re.compile(r"\$\{([^}]+)}")
CONDITION = re.compile(r"^\$\$(>=|<=|==|!=|>|<)(\d+(?:\.\d+){1,2})$")
RANGE = re.compile(r"^\$\$(\d+(?:\.\d+){1,2})~(\d+(?:\.\d+){1,2})$")
SECTIONS = {"items", "blocks", "block", "recipes", "categories", "templates", "farmersdelight_recipes",
            "images", "translations", "vanilla_loots", "advanced_tags", "placed-features", "furniture",
            "block-state-mappings"}
ITEM_FIELDS = {"material", "data", "model", "legacy_model", "texture", "template", "arguments",
               "behavior", "behaviors", "settings", "events", "custom_model_data", "item_model", "category"}
MODEL_FIELDS = {"type", "path", "generation", "tints", "property", "cases", "fallback", "models",
                "on_true", "on_false", "entries", "threshold", "scale", "index", "normalize",
                "model", "base", "hand_animation_on_swap"}
CE_RECIPE_FIELDS = {"type", "result", "ingredients", "ingredient", "pattern", "category", "group",
                    "experience", "time", "cookingtime", "template-type", "base", "addition"}
CE_RECIPE_TYPES = {"shaped", "shapeless", "smelting", "blasting", "smoking", "campfire_cooking",
                   "smithing_transform", "smithing_trim", "stonecutting"}
CRAFTING_CATEGORIES = {"building", "redstone", "equipment", "misc"}
COOKING_CATEGORIES = {"food", "blocks", "misc"}
NATIVE_RECIPE_FIELDS = {"station", "operation", "input", "output", "fluid", "process", "group", "category"}
EXTERNAL_TEMPLATES = {"default:loot_table/self", "default:loot_table/slab", "default:hardness/none",
                      "default:sound/grass", "default:sound/stem_crop", "default:loot_table/seed_crop",
                      "default:loot_table/crop", "default:block_state/slab", "default:block_state/fence"}
# Action selectors from util/Constants.java are tools predicates, never item IDs.
TOOL_ACTIONS = {"farmersdelight:axe_dig", "farmersdelight:axe_strip",
                "farmersdelight:pickaxe_dig", "farmersdelight:shovel_dig"}


class DuplicateKeyLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f"duplicate YAML key {key!r} on line {key_node.start_mark.line + 1}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


DuplicateKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def version_tuple(value):
    numbers = tuple(int(part) for part in value.split("."))
    return numbers + (0,) * (3 - len(numbers))


def deep_merge(left, right):
    result = copy.deepcopy(left)
    for key, value in right.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def condition_matches(key, version):
    interval = RANGE.fullmatch(key)
    if interval:
        lower, upper = interval.groups()
        return version_tuple(lower) <= version_tuple(version) <= version_tuple(upper)
    match = CONDITION.fullmatch(key)
    if not match:
        raise ValueError(f"unknown version condition {key}")
    operation, target = match.groups()
    a, b = version_tuple(version), version_tuple(target)
    return {">=": a >= b, "<=": a <= b, "==": a == b, "!=": a != b, ">": a > b, "<": a < b}[operation]


def select_version(node, version):
    if isinstance(node, list):
        return [select_version(value, version) for value in node]
    if not isinstance(node, dict):
        return node
    result = {}
    matched = any(condition_matches(str(key), version) for key in node
                  if str(key).startswith("$$") and key != "$$fallback")
    active = [value for key, value in node.items() if str(key).startswith("$$")
              and ((key == "$$fallback" and not matched)
                   or (key != "$$fallback" and condition_matches(str(key), version)))]
    if active and any(not isinstance(value, dict) for value in active):
        if len(active) != 1 or any(not str(key).startswith("$$") for key in node):
            raise ValueError("conditional scalar/list branch cannot merge with another mapping or branch")
        return select_version(active[0], version)
    for key, value in node.items():
        if str(key).startswith("$$"):
            if (key == "$$fallback" and not matched) or (key != "$$fallback" and condition_matches(str(key), version)):
                result = deep_merge(result, select_version(value, version))
        else:
            result[key] = select_version(value, version)
    return result


def substitute(node, arguments):
    if isinstance(node, dict):
        return {substitute(key, arguments): substitute(value, arguments) for key, value in node.items()}
    if isinstance(node, list):
        return [substitute(value, arguments) for value in node]
    if not isinstance(node, str):
        return node
    exact = VARIABLE.fullmatch(node)
    if exact and exact.group(1) in arguments:
        return copy.deepcopy(arguments[exact.group(1)])
    return VARIABLE.sub(lambda match: str(arguments.get(match.group(1), match.group(0))), node)


def factory_documents(document):
    ordinary = {key: value for key, value in document.items() if key != "config_factory"}
    output = [ordinary]
    factory = document.get("config_factory")
    if factory is None:
        return output
    if not isinstance(factory, dict) or set(factory) - {"blueprint", "instances"}:
        raise ValueError("config_factory requires blueprint and instances; unknown fields are unsupported")
    if not isinstance(factory.get("blueprint"), dict) or not isinstance(factory.get("instances"), list):
        raise ValueError("config_factory blueprint must be a mapping and instances a list")
    for instance in factory["instances"]:
        if not isinstance(instance, dict):
            raise ValueError("config_factory instance must be an argument mapping")
        output.append(substitute(factory["blueprint"], instance))
    return output


class Check:
    def __init__(self, root, contract, version):
        self.root, self.contract, self.version = Path(root), contract, version
        self.errors = []
        self.registries = {key: {} for key in ("items", "blocks", "recipes", "categories", "templates")}
        self.sources = {}
        self.native = []
        self.assets = {}
        self.generated = {}
        self.loaded_documents = set()
        self.model_count = 0
        self.texture_count = 0

    def error(self, source, message):
        self.errors.append(f"{source}: {message}")

    def read_yaml(self, path):
        try:
            value = yaml.load(path.read_text(encoding="utf-8"), Loader=DuplicateKeyLoader)
            if not isinstance(value, dict):
                raise ValueError("document must be a mapping")
            return value
        except (ValueError, yaml.YAMLError, OSError) as exc:
            self.error(path.relative_to(self.root), str(exc))
            return {}

    def load(self):
        for pack_name in self.contract["packs"]:
            pack = self.root / pack_name
            if not (pack / "pack.yml").is_file():
                self.error(pack_name, "missing pack.yml")
                continue
            for path in sorted((pack / "resourcepack/assets").rglob("*")):
                if path.is_file():
                    relative = path.relative_to(pack / "resourcepack/assets").as_posix()
                    if relative in self.assets and self.assets[relative].read_bytes() != path.read_bytes():
                        self.error(relative, "different assets share a resource path across delivery packs")
                    self.assets[relative] = path
            for path in sorted((pack / "configuration").rglob("*.yml")):
                self.loaded_documents.add(path.resolve())
                try:
                    selected = select_version(self.read_yaml(path), self.version)
                    for document in factory_documents(selected):
                        for key, definitions in document.items():
                            section = str(key).split("#", 1)[0]
                            if section not in SECTIONS:
                                self.error(path.relative_to(self.root), f"unknown configuration section {key}")
                                continue
                            if section == "block":
                                section = "blocks"
                            if section == "farmersdelight_recipes":
                                self.native.append((path.relative_to(self.root).as_posix(), definitions))
                            if section not in self.registries:
                                continue
                            if not isinstance(definitions, dict):
                                self.error(path.relative_to(self.root), f"{key} must be a mapping")
                                continue
                            for identifier, definition in definitions.items():
                                if identifier in self.registries[section]:
                                    self.error(path.relative_to(self.root), f"duplicate {section} definition {identifier}")
                                self.registries[section][identifier] = definition
                                self.sources[(section, identifier)] = path.relative_to(self.root).as_posix()
                except ValueError as exc:
                    self.error(path.relative_to(self.root), str(exc))
        for relative in self.contract.get("source_recipe_files", []):
            path = self.root / relative
            if not path.is_file():
                path = self.root / "farmersdelight/configuration" / Path(relative).name
            if not path.is_file():
                self.error(relative, "missing separately packaged source recipe file")
            else:
                if path.resolve() in self.loaded_documents:
                    continue
                self.loaded_documents.add(path.resolve())
                doc = self.read_yaml(path)
                for section, definitions in doc.items():
                    if str(section).split("#", 1)[0] == "farmersdelight_recipes":
                        self.native.append((relative, definitions))

    def expand(self, node, identifier, trail=(), arguments=None):
        if not isinstance(node, (dict, list)):
            return substitute(node, arguments or {})
        if isinstance(node, list):
            return [self.expand(value, identifier, trail, arguments) for value in node]
        namespace, local = str(identifier).split(":", 1) if ":" in str(identifier) else ("farmersdelight", str(identifier))
        context = {"__NAMESPACE__": namespace, "__ID__": local, **(arguments or {})}
        context.update(substitute(node.get("arguments", {}), context))
        merged = {}
        references = node.get("template", [])
        references = references if isinstance(references, list) else [references]
        for reference in references:
            reference = substitute(reference, context)
            if not isinstance(reference, str):
                raise ValueError(f"template reference must be a string: {reference!r}")
            if reference in trail:
                raise ValueError(f"template cycle {' -> '.join(trail + (reference,))}")
            if reference in self.registries["templates"]:
                inherited = self.expand(self.registries["templates"][reference], identifier, trail + (reference,), context)
                merged = deep_merge(merged, inherited)
            elif reference in EXTERNAL_TEMPLATES:
                merged = deep_merge(merged, self.external_template(reference, context))
            else:
                raise ValueError(f"missing or unsupported template {reference}")
        direct = {key: value for key, value in node.items() if key not in ("template", "arguments")}
        merged = deep_merge(merged, substitute(direct, context))
        return {key: self.expand(value, identifier, trail, context) for key, value in merged.items()}

    def external_template(self, reference, arguments):
        # These are dependency specifications, not copies of CE's state machinery.
        if reference == "default:block_state/slab":
            names = ("slab_model", "planks_model", "slab_texture_bottom", "slab_texture_side", "slab_texture_top")
            missing = [name for name in names if name not in arguments]
            if missing:
                raise ValueError(f"{reference} missing dependency arguments {missing}")
            return {"dependency_models": [arguments["slab_model"], str(arguments["slab_model"]) + "_top", arguments["planks_model"]],
                    "dependency_textures": [arguments[name] for name in names[2:]]}
        if reference == "default:block_state/fence":
            names = ("fence_post_item", "fence_side_item")
            missing = [name for name in names if name not in arguments]
            if missing:
                raise ValueError(f"{reference} missing dependency arguments {missing}")
            return {"dependency_items": [arguments[name] for name in names]}
        return {}

    def registry_reference(self, value, kind, source):
        if not isinstance(value, str):
            self.error(source, f"{kind} reference must be a string: {value!r}")
        elif "${" in value:
            self.error(source, f"unresolved {kind} reference {value}")
        elif value.startswith("farmersdelight:") and value not in self.registries[kind]:
            self.error(source, f"missing {kind} reference {value}")

    def asset_key(self, value, kind, source):
        if not isinstance(value, str) or "${" in value:
            self.error(source, f"unresolved or invalid {kind} reference {value!r}")
            return None
        namespace, name = value.split(":", 1) if ":" in value else ("minecraft", value)
        if namespace == "minecraft":
            return None
        if namespace != "farmersdelight":
            self.error(source, f"unreviewed external {kind} namespace {namespace}")
            return None
        suffix = ".json" if kind == "models" else ".png"
        return f"{namespace}/{kind}/{name}{suffix}"

    def texture(self, value, source):
        key = self.asset_key(value, "textures", source)
        if key and key not in self.assets:
            self.error(source, f"missing texture {value}")
        self.texture_count += 1

    def model(self, value, source, trail=(), overrides=None):
        key = self.asset_key(value, "models", source)
        if key is None:
            return
        if key in trail:
            self.error(source, f"model parent cycle {' -> '.join(trail + (key,))}")
            return
        if key not in self.assets and key not in self.generated:
            self.error(source, f"missing model {value}")
            return
        try:
            model = self.generated[key] if key in self.generated else json.loads(self.assets[key].read_text(encoding="utf-8"))
        except (ValueError, OSError) as exc:
            self.error(source, f"invalid model {value}: {exc}")
            return
        if not isinstance(model, dict) or not isinstance(model.get("textures", {}), dict):
            self.error(source, f"model {value} and its textures must be mappings")
            return
        bindings = {**model.get("textures", {}), **(overrides or {})}
        for name, texture in bindings.items():
            resolved, seen = texture, set()
            while isinstance(resolved, str) and resolved.startswith("#"):
                token = resolved[1:]
                if token in seen or token not in bindings:
                    self.error(source, f"unresolved texture alias {texture} in {value}")
                    resolved = None
                    break
                seen.add(token)
                resolved = bindings[token]
            if resolved:
                self.texture(resolved, f"{source} -> {value}.{name}")
        parent = model.get("parent")
        if parent:
            self.model(parent, source, trail + (key,), bindings)
        self.model_count += 1

    def model_spec(self, spec, source):
        if isinstance(spec, list):
            for child in spec:
                self.model_spec(child, source)
            return
        if isinstance(spec, str):
            self.model(spec, source)
            return
        if not isinstance(spec, dict):
            self.error(source, f"model specification must be a string or mapping: {spec!r}")
            return
        unknown = set(spec) - MODEL_FIELDS - {"x", "y", "uvlock", "weight"}
        if unknown:
            self.error(source, f"unknown model fields {sorted(unknown)}")
        model_type = spec.get("type", "minecraft:model")
        if model_type in ("minecraft:model", "model"):
            if "path" not in spec:
                self.error(source, "model path is missing")
            else:
                self.model(spec["path"], source)
        elif model_type == "minecraft:composite":
            if not isinstance(spec.get("models"), list):
                self.error(source, "composite models must be a list")
            for child in spec.get("models", []):
                self.model_spec(child, source)
        elif model_type in ("minecraft:select", "minecraft:range_dispatch", "minecraft:condition"):
            for branch in spec.get("cases", []) + spec.get("entries", []):
                if "model" not in branch:
                    self.error(source, "model branch has no model")
                else:
                    self.model_spec(branch["model"], source)
            for key in ("fallback", "on_true", "on_false"):
                if key in spec:
                    self.model_spec(spec[key], source)
        else:
            self.error(source, f"unsupported model type {model_type}")

    def collect_generations(self, node, source):
        if isinstance(node, dict):
            if "generation" in node:
                key = self.asset_key(node.get("path"), "models", source)
                generation = node["generation"]
                if not isinstance(generation, dict):
                    self.error(source, "model generation must be a mapping")
                elif key:
                    self.generated[key] = generation
            for value in node.values():
                self.collect_generations(value, source)
        elif isinstance(node, list):
            for value in node:
                self.collect_generations(value, source)

    def references(self, node, source):
        if isinstance(node, dict):
            for key, value in node.items():
                if key in ("model", "legacy_model"):
                    self.model_spec(value, source)
                elif key == "texture":
                    self.texture(value, source)
                elif key == "block" and isinstance(value, str):
                    self.registry_reference(value, "blocks", source)
                elif key in ("dependency_models", "dependency_textures", "dependency_items"):
                    for ref in value:
                        if key == "dependency_models":
                            self.model(ref, source)
                        elif key == "dependency_textures":
                            self.texture(ref, source)
                        else:
                            self.registry_reference(ref, "items", source)
                elif key in ("entity_renderer", "entity-renderer"):
                    for renderer in value if isinstance(value, list) else [value]:
                        if isinstance(renderer, dict) and "item" in renderer:
                            self.registry_reference(renderer["item"], "items", source)
                if key not in ("model", "legacy_model", "generation"):
                    self.references(value, source)
        elif isinstance(node, list):
            for value in node:
                self.references(value, source)

    def recipe_ids(self, node, source, output=False, tools=False):
        result = set()
        if isinstance(node, str):
            if "|" in node:
                for choice in node.split("|"):
                    result.update(self.recipe_ids(choice, source, output, tools))
                return result
            if tools and node in TOOL_ACTIONS:
                return result
            if not node.startswith(("#", "advtag:", "tag:")):
                self.registry_reference(node, "items", source)
                result.add(node)
        elif isinstance(node, list):
            for value in node:
                result.update(self.recipe_ids(value, source, output, tools))
        elif isinstance(node, dict):
            if "id" in node or "item" in node:
                unknown = set(node) - {"id", "item", "items", "container", "tools", "choices", "count", "amount",
                                       "chance", "components", "exact-components", "nbt", "strict-nbt", "consume"}
                if unknown:
                    self.error(source, f"unsupported ingredient/output fields {sorted(unknown)}")
                result.update(self.recipe_ids(node.get("id", node.get("item")), source, output, tools))
                for key in ("items", "container", "tools", "choices"):
                    if key in node:
                        result.update(self.recipe_ids(node[key], source, output, key == "tools"))
            elif output:
                self.error(source, f"output has no id/item field: {sorted(node)}")
            else:
                for key, value in node.items():
                    if key in ("items", "container", "tools", "choices") or len(key) == 1:
                        result.update(self.recipe_ids(value, source, output, key == "tools"))
                    elif key not in ("count", "amount", "components", "exact-components", "nbt", "strict-nbt"):
                        self.error(source, f"unsupported ingredient field {key}")
        else:
            self.error(source, f"invalid recipe item shape {node!r}")
        return result

    def reachable_items(self, expanded):
        reached, visiting = set(), set()
        self._reachable_categories = set()

        def visit(identifier):
            if identifier in visiting:
                self.error(identifier, "category reference cycle")
                return
            category = self.registries["categories"].get(identifier)
            if not isinstance(category, dict):
                self.error(identifier, "missing reachable category")
                return
            visiting.add(identifier)
            self._reachable_categories.add(identifier)
            for entry in category.get("list", []):
                if not isinstance(entry, str):
                    self.error(identifier, f"unsupported category entry {entry!r}")
                elif entry.startswith("#"):
                    visit(entry[1:])
                else:
                    self.registry_reference(entry, "items", identifier)
                    reached.add(entry)
            if "icon" in category:
                self.registry_reference(category["icon"], "items", identifier)
            visiting.remove(identifier)

        for identifier in self.contract["category_roots"]:
            visit(identifier)
        for (kind, identifier), definition in expanded.items():
            if kind != "items" or "category" not in definition:
                continue
            categories = definition["category"]
            categories = categories if isinstance(categories, list) else [categories]
            for category in categories:
                if not isinstance(category, str) or category not in self.registries["categories"]:
                    self.error(identifier, f"missing injected category {category!r}")
                elif category in self._reachable_categories:
                    reached.add(identifier)
        return reached

    def run(self):
        self.load()
        expanded = {}
        for kind in ("items", "blocks"):
            for identifier, node in self.registries[kind].items():
                source = self.sources[kind, identifier] + ":" + str(identifier)
                try:
                    if not isinstance(node, dict):
                        raise ValueError("definition must be a mapping")
                    if kind == "items" and set(node) - ITEM_FIELDS:
                        self.error(source, f"unknown item fields {sorted(set(node) - ITEM_FIELDS)}")
                    definition = self.expand(node, identifier)
                    expanded[kind, identifier] = definition
                    self.collect_generations(definition, source)
                except ValueError as exc:
                    self.error(source, str(exc))
        for (kind, identifier), definition in expanded.items():
            self.references(definition, self.sources[kind, identifier] + ":" + str(identifier))
        outputs = set()
        for identifier, definition in self.registries["recipes"].items():
            source = self.sources["recipes", identifier] + ":" + str(identifier)
            if not isinstance(definition, dict):
                self.error(source, "CE recipe must be a mapping")
                continue
            unknown = set(definition) - CE_RECIPE_FIELDS
            if unknown:
                self.error(source, f"unknown recipe fields {sorted(unknown)}")
            if definition.get("type") not in CE_RECIPE_TYPES:
                self.error(source, f"unsupported CE recipe type {definition.get('type')!r}")
            recipe_type = definition.get("type")
            allowed_categories = (CRAFTING_CATEGORIES if recipe_type in ("shaped", "shapeless")
                                  else COOKING_CATEGORIES if recipe_type in ("smelting", "blasting", "smoking", "campfire_cooking")
                                  else None)
            if "category" in definition and allowed_categories is not None:
                category = definition["category"]
                if not isinstance(category, str) or category.lower() not in allowed_categories:
                    self.error(source, f"invalid {recipe_type} recipe category {category!r}; expected {sorted(allowed_categories)}")
            outputs.update(self.recipe_ids(definition.get("result"), source, True))
            for key in ("ingredients", "ingredient", "base", "addition"):
                if key in definition:
                    self.recipe_ids(definition[key], source)
        native_ids = {}
        for path, definitions in self.native:
            if not isinstance(definitions, dict):
                self.error(path, "native recipe registry must be a mapping")
                continue
            for identifier, definition in definitions.items():
                source = path + ":" + str(identifier)
                canonical_id = str(identifier) if ":" in str(identifier) else "farmersdelight:" + str(identifier)
                if canonical_id in native_ids:
                    self.error(source, f"duplicate farmersdelight_recipes definition {canonical_id}; first in {native_ids[canonical_id]}")
                else:
                    native_ids[canonical_id] = source
                if not isinstance(definition, dict):
                    self.error(source, "recipe must be a mapping")
                    continue
                unknown = set(definition) - NATIVE_RECIPE_FIELDS
                if unknown:
                    self.error(source, f"unknown native recipe fields {sorted(unknown)}")
                if definition.get("station") not in ("cutting_board", "cooking_pot", "fluid_tank"):
                    self.error(source, f"unsupported native recipe station {definition.get('station')!r}")
                outputs.update(self.recipe_ids(definition.get("output"), source, True))
                if "input" in definition:
                    self.recipe_ids(definition["input"], source)
        reached = self.reachable_items(expanded)
        for identifier, requirements in self.contract["player_items"].items():
            if "since" in requirements and version_tuple(self.version) < version_tuple(requirements["since"]):
                if identifier in self.registries["items"]:
                    self.error(identifier, f"version-gated item present before {requirements['since']}")
                continue
            if identifier not in self.registries["items"]:
                self.error(identifier, "required player item is missing")
            elif not any(field in expanded.get(("items", identifier), {}) for field in ("model", "texture")):
                self.error(identifier, "required player item has no custom model or texture")
            if requirements.get("category", True) and identifier not in reached:
                self.error(identifier, "player item is absent from reachable categories")
            if requirements.get("block", False) and identifier not in self.registries["blocks"]:
                inline = expanded.get(("items", identifier), {}).get("behavior", {})
                if not isinstance(inline, dict) or not isinstance(inline.get("block"), dict):
                    self.error(identifier, "required block is missing")
            if requirements.get("recipe", False) and identifier not in outputs:
                self.error(identifier, "required acquisition recipe output is missing")
        active_count = sum("since" not in requirements or version_tuple(self.version) >= version_tuple(requirements["since"])
                           for requirements in self.contract["player_items"].values())
        return {"version": self.version, "player_items": active_count,
                "contract_player_items": len(self.contract["player_items"]),
                "items": len(self.registries["items"]), "blocks": len(self.registries["blocks"]),
                "recipes": len(self.registries["recipes"]) + sum(len(r) for _, r in self.native if isinstance(r, dict)),
                "model_visits": self.model_count, "texture_checks": self.texture_count,
                "errors": sorted(set(self.errors))}


def load_contract(path):
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    unknown = set(document) - {"schema", "description", "packs", "category_roots", "source_recipe_files", "player_items", "versions"}
    if unknown or document.get("schema") != 1:
        raise ValueError(f"unsupported contract schema or fields: {sorted(unknown)}")
    for identifier, requirements in document["player_items"].items():
        if set(requirements) - {"block", "category", "recipe", "since"}:
            raise ValueError(f"unknown item contract fields for {identifier}")
        for field in ("block", "category", "recipe"):
            if field in requirements and not isinstance(requirements[field], bool):
                raise ValueError(f"item contract {identifier}.{field} must be boolean")
    return document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT / "src/main/resources/craftengine")
    parser.add_argument("--contract", type=Path, default=ROOT / "tools/content-pack-contract.json")
    parser.add_argument("--version", action="append", help="Minecraft version; defaults to all reviewed version boundaries")
    parser.add_argument("--json", type=Path, help="write the complete read-only check report")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()
    try:
        contract = load_contract(args.contract)
        reports = [Check(args.root, contract, version).run() for version in (args.version or contract["versions"])]
    except (OSError, ValueError, KeyError) as exc:
        print(f"Content contract check failed: {exc}", file=sys.stderr)
        return 1
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for report in reports:
        for error in report["errors"]:
            print(f"[{report['version']}] {error}", file=sys.stderr)
        if not args.quiet:
            print(f"Minecraft {report['version']}: {report['player_items']} contracted player items, "
                  f"{report['items']} definitions, {report['recipes']} recipes, {len(report['errors'])} errors")
    return 1 if any(report["errors"] for report in reports) else 0


if __name__ == "__main__":
    sys.exit(main())
