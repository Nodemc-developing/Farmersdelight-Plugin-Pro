<h1 align="center">
  <img src=".github/assets/farmersdelight-banner.svg" width="640" alt="Farmer's Delight Plugin Pro"><br>
  Farmersdelight-Plugin-Pro
</h1>

<p align="center">Grow, cook and share Farmer's Delight meals on Paper and Folia.</p>

<p align="center">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-blue" alt="AGPL-3.0 license">
  <img src="https://img.shields.io/badge/All_features-Free-3fb950" alt="All features are free">
  <img src="https://img.shields.io/badge/Paper-26.3-5865F2" alt="Paper 26.3">
  <img src="https://img.shields.io/badge/Folia-26.2-8b5cf6" alt="Folia 26.2">
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25 server runtime">
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
| 🏡 Villagers | Custom crop harvesting/replanting, food sharing, breeding rules and configurable trades | Free |
| 📊 Statistics | Persistent player/item statistics, asynchronous SQLite and PlaceholderAPI queries | Free |
| 📖 Recipe discovery | Recipe books, linked recipes and optional Kaleidoscope automatic ingredient filling | Free |
| 🏆 Advancements | UltimateAdvancementAPI integration, automatic layout and incremental updates | Free |

Folia-aware scheduling, sleeping block tickers, asynchronous file/database work and an addon API are included too.

## 🧩 Requirements

| Component | Version | Needed for |
| --- | --- | --- |
| Paper or Folia | **Paper 26.3 / Folia 26.2** | Verified server versions |
| Java | **25** | Server runtime |
| [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) | **Pinned 26.10 snapshot** | Required; [exact build](docs/DEVELOPMENT.md#supported-environment) |
| [FluidCore](https://github.com/Nodemc-developing/FluidCore/releases/tag/v0.1.0-SNAPSHOT.2) | 0.1.0-SNAPSHOT, Build 2 | Fluid recipes and tanks |
| [UltimateAdvancementAPI](https://github.com/Nodemc-developing/UltimateAdvancementAPI/releases/tag/v2.8.1-pro.2) | 2.8.1-pro.2 | Advancements |
| [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) | Optional | Statistics placeholders |

## 🚀 Installation

1. Download the [server bundle](https://github.com/Nodemc-developing/Farmersdelight-Plugin-Pro/releases/latest) or the plugin JAR.
2. Install the pinned CraftEngine build and put this plugin plus the optional dependencies you need into `plugins/`.
3. Start the server and accept its resource pack. Bundled content fills missing definitions; your external packs take priority.

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
| `/ce reload` | Reload CraftEngine content and resources |

Editing, reloading and runtime statistics require administrator permissions.

## 📚 Documentation

- [Configuration & recipes](CONFIGURATION-RECIPES.zh-CN.md) · [Fluid tanks](FLUID-TANK.zh-CN.md)
- [Kaleidoscope integration & flexible recipes](KALEIDOSCOPE-COMPAT.zh-CN.md)
- [Dependencies & corresponding sources](libs/README.md) · [Build & technical notes](docs/DEVELOPMENT.md)
- [1.2.0 release notes & verification scope](RELEASE-NOTES-1.2.0.zh-CN.md) · [Original wiki](https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi)

## 🙏 Credits & license

Original plugin: **HuiDu_OwO (IOVEYOUMC0)**. Fork maintenance: **ydxc2009**. Thanks to **vectorwing** and the [Farmer's Delight](https://github.com/vectorwing/FarmersDelight) contributors for the original mod and its MIT-licensed assets.

This project uses **AGPL-3.0-only**. Use, modification and redistribution, including commercial redistribution, follow [LICENSE](LICENSE). Third-party materials keep their own licenses and attribution: [NOTICE](NOTICE.md) · [THIRD_PARTY](THIRD_PARTY.md).
