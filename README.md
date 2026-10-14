<h1 align="center">
  <img src=".github/assets/farmersdelight-banner.svg" width="640" alt="Farmer's Delight Plugin Pro"><br>
  Farmersdelight-Plugin-Pro
</h1>

<p align="center">Grow, cook and share Farmer's Delight meals on Paper and Folia.</p>

<p align="center">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-blue" alt="AGPL-3.0 license">
  <img src="https://img.shields.io/badge/All_features-Free-3fb950" alt="All features are free">
  <img src="https://img.shields.io/badge/Paper-1.21--26.3-5865F2" alt="Paper 1.21–26.3">
  <img src="https://img.shields.io/badge/Folia-supported-8b5cf6" alt="Folia supported">
  <img src="https://img.shields.io/badge/Java-21%20%2F%2025-orange" alt="Java 21 / 25 server runtime">
</p>

<p align="center">
  <a href="https://github.com/Nodemc-developing/Farmersdelight-Plugin-Pro/releases/latest">Download</a> ·
  <a href="#-documentation">Documentation</a> ·
  <a href="README.zh-cn.md">简体中文</a>
</p>

## 🌱 About

An independently maintained **hard fork** of [Farmersdelight-Plugin](https://github.com/IOVEYOUMC0/Farmersdelight-Plugin), originally created by **HuiDu_OwO (IOVEYOUMC0)**. This fork is maintained by **ydxc2009**.

**Forever free. Forever open source.** Every feature, release and future update is free. CraftEngine provides the custom items, blocks and resource pack; this plugin handles gameplay, menus and automation.

## ✨ Features

| Feature | Included | Price |
| --- | --- | --- |
| 🍲 Cooking pots | Heat sources, cooking progress, containers, hopper automation and custom pot groups | Free |
| 🔪 Cutting boards | Required tools, multiple outputs, output chances, sounds and automation | Free |
| 🍳 Stoves & handheld cooking | Placed cooking, skillet flips, skewers and configurable particles | Free |
| 🌾 Farming | Single, double and climbing crops, wild plants, rich soil, compost and mushroom colonies | Free |
| 🧺 Storage & blocks | Baskets, collection, redstone locking, ropes, paired blocks, comparators and temperature damage | Free |
| 🍽️ Food & effects | Nourishment, Comfort, full-hunger eating, effect removal/upgrades and configured teleportation | Free |
| 🛠️ Recipe editor | In-game editing, station selection, search, paging and return to the previous menu | Free |
| 🥕 Flexible ingredients | Equivalent ingredients, nested groups, tags, components, seasonings, ratios and priorities | Free |
| 📦 Content packs | CraftEngine recipe loading, external-pack priority, merged categories and source-file saving | Free |
| 🫙 Fluids | Normal/glass tanks, dyeing, filling, emptying, soaking, hopper processing and preserved tank contents | Free |
| 🏡 Villagers | Harvesting/replanting, seed reserves, food sharing, composting, bone meal, inventory management and configurable trades | Free |
| 📊 Statistics | Persistent player/item statistics, asynchronous SQLite and PlaceholderAPI queries | Free |
| 📖 Recipe discovery | Recipe books, linked recipes and optional Kaleidoscope automatic ingredient filling | Free |
| 🏆 Advancements | UltimateAdvancementAPI integration, automatic layout and incremental updates | Free |

Folia-aware scheduling, sleeping block tickers, asynchronous file/database work and an addon API are included too.

## 🧩 Requirements

**1.2.9** completes English browser categories and tank descriptions, retaining the content and gameplay fixes from 1.2.8. Install the content pack manually; startup and reload do not extract or update it. See the [release notes](RELEASE-NOTES-1.2.9.en-US.md) and [recipe guide](CONFIGURATION-RECIPES.zh-CN.md).

**English setup:** set `language: en_us` in `plugins/Farmersdelight-Plugin-Pro/config.yml`. For fluids, set `default-language: en` in `plugins/FluidCore/lang.yml`. Reload all FD configuration or restart, and let players accept the updated resource pack. Client item names and advancements follow the player's language; server-defined admin messages and some menus use the configured server language.

| Component | Version | Needed for |
| --- | --- | --- |
| Paper or Folia | **Minecraft 1.21–26.3** | [Version coverage & limitations](docs/COMPATIBILITY.md); Folia needs an available official build |
| Java | **21 for 1.21.x; 25 for 26.x** | Server runtime; JDK 25 builds the plugin |
| [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) | **26.9.2 / 26.10 snapshot** | Required; [versions and APIs](docs/DEVELOPMENT.md#supported-environment) |
| [FluidCore](https://github.com/Nodemc-developing/FluidCore) | Compatible 0.1.0-SNAPSHOT from `libs/` | Fluid recipes and tanks; use the supplied Java 21 build |
| [UltimateAdvancementAPI](https://github.com/Nodemc-developing/UltimateAdvancementAPI) | 2.8.1-pro.3 from `libs/` | Advancements across the supported version range |
| [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) | Optional | Statistics placeholders |

## 🚀 Installation

1. Download the [server bundle](https://github.com/Nodemc-developing/Farmersdelight-Plugin-Pro/releases/latest) or the plugin JAR.
2. Install a supported CraftEngine build and put this plugin plus the optional dependencies you need into `plugins/`.
3. Extract the matching `*-content-pack.zip` into `plugins/CraftEngine/resources/`, with `farmersdelight/` and `farmersdelight_fluids/` directly inside that directory.
4. Start the server, generate and accept the CraftEngine resource pack. Content installation is manual: startup and reload never extract, update or restore pack files. Back up your edits before updating.

Recipes live in enabled CraftEngine content packs. Old fluid-library world/item data is **not migrated**. See the [setup guide](CONFIGURATION-RECIPES.zh-CN.md) before upgrading an existing server.

## 🎮 Commands

| Command | Action |
| --- | --- |
| `/fd help` | Show available commands |
| `/fd recipe` | Browse recipes |
| `/fd recipe book` | Open the recipe book |
| `/fd recipe edit` | Select a station and edit its recipes |
| `/fd reload` | Reload plugin settings and recipes |
| `/fd stats` | View runtime statistics |
| `/fd villager inventory` | Inspect or edit a nearby villager's backpack |
| `/ce reload` | Reload CraftEngine content and resources |

Editing, reloading and runtime statistics require administrator permissions.

## 📚 Documentation

- [Configuration & recipes](CONFIGURATION-RECIPES.zh-CN.md) · [Fluid tanks](FLUID-TANK.zh-CN.md)
- [Kaleidoscope integration & flexible recipes](KALEIDOSCOPE-COMPAT.zh-CN.md)
- [Dependencies & corresponding sources](libs/README.md) · [Build & technical notes](docs/DEVELOPMENT.md)
- [Integrated villager features and switches](VILLAGER.zh-CN.md)
- [1.2.9 English localization patch](RELEASE-NOTES-1.2.9.en-US.md) · [1.2.8 changes](RELEASE-NOTES-1.2.8.zh-CN.md) · [Original wiki](https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi)

## 🙏 Credits & license

Original plugin: **HuiDu_OwO (IOVEYOUMC0)**. Fork maintenance: **ydxc2009**. Thanks to **vectorwing** and the [Farmer's Delight](https://github.com/vectorwing/FarmersDelight) contributors for the original mod and its MIT-licensed assets.

This project uses **AGPL-3.0-only**. Use, modification and redistribution, including commercial redistribution, follow [LICENSE](LICENSE). Third-party materials keep their own licenses and attribution: [NOTICE](NOTICE.md) · [THIRD_PARTY](THIRD_PARTY.md).
