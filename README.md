# Farmersdelight-Plugin-Pro

**English** | [中文](README.zh-cn.md)

A CraftEngine-powered Farmer's Delight plugin for Paper and Folia: grow crops, prepare ingredients, cook meals and manage recipes in game.

This project is a maintained optimization fork of [Farmersdelight-Plugin](https://github.com/IOVEYOUMC0/Farmersdelight-Plugin). Original author: HuiDu_OwO (IOVEYOUMC0). Fork maintenance and optimization: ydxc2009.

**Forever free and permanently open source.** All features, published builds and future updates are free. Third-party dependencies retain their own licenses and distribution policies.

## Features

| Feature | What it provides | Cost |
| --- | --- | --- |
| Cooking pot | Cooking progress, heat sources, containers and custom pot groups | Free |
| Cutting board | Tool requirements, multiple outputs, output chances and sounds | Free |
| Stove and skillet | Placed and handheld cooking, effects and configurable particles | Free |
| Crops and farmland | Crops, wild plants, rich soil and configurable growth | Free |
| Blocks and items | CraftEngine models, resource packs, loot, ropes, mushroom colonies and storage | Free |
| Food effects | Nourishment, Comfort and configurable food effects | Free |
| Recipe editor | `/fd recipe edit`, station selection, search, paging and return to the previous menu | Free |
| Fuzzy recipes | Equivalent ingredients, food groups, ideal ratios, seasonings and priorities | Free |
| Advanced ingredient groups | Nested groups, tag references and alternative ingredients | Free |
| Content-pack recipes | Loading from enabled CraftEngine packs; editing writes back to the source node | Free |
| Fluid recipes | Filling, emptying, soaking and ID/tag/alternative/component conditions with FluidCore | Free |
| Fluid tank | Normal/glass tanks, capacity hints, menu, hopper processing, dyeing and content-preserving carried items/drops | Free |
| Handheld cooking | Skillet jump/landing flips and meat/vegetable skewers | Free |
| Villagers | Custom crop harvesting/replanting, seed/food pickup, sharing, native breeding rules and configurable trades | Free |
| Persistent statistics | Asynchronous SQLite, player/item details and cached PlaceholderAPI queries | Free |
| Generic crops | Single/double/rope crops, farmland, rich soil, compost and wild rice | Free |
| Generic block behaviors | Basket settings, rope reels/bells, paired/double blocks, comparators and temperature damage | Free |
| Configured functions | Full-hunger eating, effect removal/upgrades, advancements and safe teleportation | Free |
| Presentation settings | Display offsets/overrides, sounds, chances, heat trays, density throttling and container intervals | Free |
| Recipe discovery | Recipe books, information cards and links between recipes | Free |
| Kaleidoscope integration | Optional recipe-book filling into cooking pots | Free |
| Advancements | Optional UltimateAdvancementAPI integration and changed-node synchronization | Free |
| Scheduling and performance | Folia owner-thread access, asynchronous file work, cached configuration and sleeping pot tickers | Free |
| Addon API | `com.huidu.farmersdelight.api` and optional gameplay addons | Free |

## Installation

- This release targets **Paper 26.3** and **Folia 26.2**; consult the matching build's local acceptance results.
- Java 21 for the plugin; the Minecraft 26.3 server requires **Java 25**.
- **CraftEngine 26.10-SNAPSHOT build 26.10-20260929.192451-4** is pinned. Startup verifies the original JAR digest; other builds need adaptation and validation.

Install CraftEngine first, then put the Farmersdelight-Plugin-Pro JAR in `plugins/` and start the server. Use `/fd reload` after changing plugin configuration, and `/ce reload` after changing CraftEngine resources.

Fluid features require **FluidCore** as a separate plugin; its API is not bundled. Other features remain available without it. Recipes load from enabled CraftEngine content packs; old fluid-library world/item data is not migrated. Recognized legacy payloads are protected rather than overwritten as empty tanks. See the [configuration and recipe guide](CONFIGURATION-RECIPES.zh-CN.md).

External content takes precedence; bundled content fills missing definitions. Duplicate category/full-ID definitions between external packs are reported. Models/textures are selected by path and language JSON by key. Generated resources use a managed overlay without rewriting external asset directories.

Standard browsing categories use one root with nine groups: tools, ingredients, crops, processed food, food, feasts, decorations, wild plants and pet food. Available items are combined without duplicates; normal and glass tanks have separate entries. Set `craftengine-resources.unified-categories: false` to retain the original category trees. This changes the loading copy, not source-pack files.

Advancements require **UltimateAdvancementAPI** as a separate plugin. `libs/` includes the optimized **2.8.1-pro.2** JAR, its corresponding source archive and build instructions. This build contains the 26.2/26.3 adapters, verified on Paper 26.3 and Folia 26.2; older servers need an API distribution matching their version. See [dependency details](libs/README.md). Resource-pack reloads resend definitions; ordinary updates synchronize changed nodes.

bStats 3.2.1 uses plugin ID **34448** (registered as **FarmersDelightPro**). To opt out, set `enabled: false` in `plugins/bStats/config.yml` and restart the server. Metrics use asynchronous transport, support Folia and stop when the plugin is disabled.

## Documentation

- [Configuration and recipes](CONFIGURATION-RECIPES.zh-CN.md)
- [Fluid tank setup and use (Chinese)](FLUID-TANK.zh-CN.md)
- [Kaleidoscope integration and fuzzy recipes](KALEIDOSCOPE-COMPAT.zh-CN.md)
- [Dependency artifacts and sources](libs/README.md)
- [1.2.0 changes and verification scope (Chinese)](RELEASE-NOTES-1.2.0.zh-CN.md)
- [Player, server and addon wiki](https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi)

## Building

Build with **JDK 25**; FD itself emits Java 21 bytecode. The current FluidCore 26.3 platform modules and test servers run on Java 25.

```text
./gradlew build
```

Pass `-PceJar=<pinned-snapshot-jar>`, `-PceLibraries=<CraftEngine/libs-directory>` and `-PfluidCoreJar=<FluidCore-jar>` for local dependencies. The CE Maven dependency is restricted to 26.10 snapshots; runtime must still match the pinned build.

Cooking pots and fluid tanks use the pinned snapshot's native sleeping/waking tickers. Addons compile against the generated API-only JAR and must be checked out beside this repository. Performance reports identify measured environments and comparable scenarios; untested cases are not advertised as improvements.

## Credits

Thanks to **vectorwing** and the [Farmer's Delight](https://github.com/vectorwing/FarmersDelight) contributors for the original mod and its MIT-licensed assets, including the golden apple and golden carrot crate models and textures. Original plugin development: **HuiDu_OwO (IOVEYOUMC0)**. This fork is maintained by **ydxc2009**.

Bundled third-party assets retain their source and license notices. External packs are supplied by server owners; their additional assets require their own distribution permission.

## License

GNU Affero General Public License v3.0 only. See [LICENSE](LICENSE).

The complete plugin source, including the recipe editor, recipe links and handheld skillet, is available in this repository. You may use, modify and redistribute it, including for a fee, subject to AGPL-3.0: preserve copyright and license notices, provide the complete corresponding source, license modified versions under AGPL-3.0, and offer source to users who interact with a modified version over a network.

Third-party content and libraries retain their own notices and licenses. See [NOTICE.md](NOTICE.md), [THIRD_PARTY.md](THIRD_PARTY.md) and the notices shipped in the JAR. Upstream projects and other forks are maintained by their respective authors.
