# Build & technical notes

[README](../README.md) · [中文](DEVELOPMENT.zh-cn.md)

## Supported environment

- Verified targets: **Paper 26.3** and **Folia 26.2**. The release notes identify the tested builds and verification scope; Folia 26.3 has not been verified.
- Server runtime and build toolchain: **Java 25**. Farmersdelight-Plugin-Pro itself emits Java 21 bytecode.
- CraftEngine is pinned to **26.10-SNAPSHOT build `26.10-20260929.192451-4`**. Startup verifies the original JAR digest. Other builds require adaptation and validation; the Maven snapshot range does not imply runtime support for every snapshot.
- Fluid mechanics require a separate **FluidCore 0.1.0-SNAPSHOT, Build 2** installation. Advancements require **UltimateAdvancementAPI 2.8.1-pro.2**, which contains the 26.2/26.3 adapters. Neither dependency is bundled into the plugin or its API JAR.

Dependency artifacts, corresponding sources, build instructions and licenses are listed in [libs/README.md](../libs/README.md).

## Build

Use JDK 25. On a fresh checkout, obtain the public API documentation required by the verification tests:

```text
git clone https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi.git wiki
```

Then build:

```text
./gradlew build
```

To supply local dependencies:

```text
./gradlew build -PceJar=<pinned-snapshot-jar> -PceLibraries=<CraftEngine/libs-directory> -PfluidCoreJar=<FluidCore-jar>
```

`ceLibraries` supplies the pinned snapshot's remapped runtime libraries. Without a local JAR, the CE Maven dependency is restricted to 26.10 snapshots; the server still needs the exact pinned build.

The build produces the plugin JAR and an API-only JAR for addon compilation. The API package is `com.huidu.farmersdelight.api`; use the API JAR as `compileOnly`. The existing addon build arrangement expects addon checkouts beside this repository.

## Content loading

Recipes load from enabled CraftEngine content packs. Editing saves to the original file, root node and recipe ID while preserving unmanaged fields.

External content takes precedence; bundled content fills missing definitions. Duplicate category/full-ID definitions between external packs are reported. Models and textures are selected by resource path; language JSON is merged by key. Generated resources use a managed overlay without rewriting external asset directories.

Standard browsing categories use one root with nine groups: tools, ingredients, crops, processed food, food, feasts, decorations, wild plants and pet food. Loaded items are combined without duplicates; normal and glass tanks have separate entries. Set `craftengine-resources.unified-categories: false` to retain the original category trees. This changes the loading copy rather than source-pack files.

Old fluid-library world/item data is not migrated. Recognized legacy payloads are protected rather than overwritten as empty tanks. See [configuration and recipes](../CONFIGURATION-RECIPES.zh-CN.md) and [fluid tanks](../FLUID-TANK.zh-CN.md).

## Scheduling & updates

World, player and inventory access stays on the owning server thread. File parsing and database work run asynchronously. Cooking pots and fluid tanks use the pinned CraftEngine snapshot's native sleeping/waking tickers.

Ordinary advancement updates synchronize changed nodes. Resource-pack reloads resend definitions. Details and adapter limits are in [the dependency guide](../libs/README.md).

Performance reports identify measured environments and comparable scenarios. Untested cases are not advertised as performance improvements. The current correctness and client-verification scope is recorded in [the release notes](../RELEASE-NOTES-1.2.0.zh-CN.md).

## Metrics

bStats 3.2.1 uses plugin ID **34448**, registered as **FarmersDelightPro**. To opt out globally, set `enabled: false` in `plugins/bStats/config.yml` and restart the server. Metrics use asynchronous transport, support Folia and stop when the plugin is disabled.

## Source & licenses

All plugin features, including the recipe editor, recipe links and handheld skillet, are present in this repository. AGPL-3.0-only permits use, modification and redistribution, including for a fee, subject to its conditions: preserve copyright and license notices, provide complete corresponding source, release modified versions under AGPL-3.0, and offer source to users interacting with a modified version over a network.

Farmer's Delight assets retain vectorwing's MIT license, including the golden apple and golden carrot crate models/textures. Bundled materials retain their source notices; additional assets supplied by external pack owners need their own distribution permission. Dependencies retain their own licenses and distribution policies. See [LICENSE](../LICENSE), [NOTICE](../NOTICE.md), [THIRD_PARTY](../THIRD_PARTY.md) and the notices shipped in the JAR.
