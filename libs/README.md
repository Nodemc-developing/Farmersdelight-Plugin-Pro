# UltimateAdvancementAPI dependency

`UltimateAdvancementAPI-Plugin-2.8.1-pro.3.jar` is used only for compilation. Farmersdelight-Plugin-Pro does not shade it into its plugin or API JAR. To enable advancements, install this JAR separately in the server's `plugins/` directory, replacing any previous UltimateAdvancementAPI JAR.

## Compatibility

- Single distribution: Minecraft 1.21 through 26.3, with Paper and Folia scheduling. Use Java 21 for 1.21.x and Java 25 for 26.x.
- Verified integration: Paper 26.3 build 140 and Folia 26.2 build 7, with CraftEngine 26.10-SNAPSHOT and all 23 Farmersdelight advancement nodes.
- This JAR includes ten native adapter families. See the source archive's COMPATIBILITY.md for the exact implemented and individually verified versions.
- No official Folia 26.3 build was available on 2026-10-01. Its native protocol adapter is verified on Paper 26.3; Folia 26.3 verification is pending an official server build.
- The existing runtime loader downloads CommandAPI 12.1.0 and the configured database driver. Compilation uses the local dependency without downloading the API JAR.

Farmersdelight uses ordinary incremental advancement updates during gameplay, and `forceUpdateAdvancements(player)` after resource-pack reloads to restore the client's definitions. Namespace rebuilds retain enough client history to remove obsolete nodes.

Automatic layout uses iterative vanilla-style subtree spacing. Paper datapack reload completion resets client snapshots and resends definitions; normal updates remain incremental. Command mutations run on the target player's scheduler, with a native command fallback when CommandAPI is unavailable.

Toast titles and descriptions retain their translation components. Announcement sentences use vanilla client translation keys, so the message and title follow each receiving player's language.

## Provenance and sources

The optimized API is based on [Nodemc-developing/UltimateAdvancementAPI](https://github.com/Nodemc-developing/UltimateAdvancementAPI), revision `67d9576ae5e4ec55701ac653194bb77c5f1e708c`. It retains the original API package names and the credits of fren_gor, EscanorTargaryen and the upstream contributors. This modified distribution adds ydxc2009 to the plugin's authors.

The published base source is [revision 8ffb53d](https://github.com/Nodemc-developing/UltimateAdvancementAPI/tree/8ffb53d175bc03c585341e30d0ee9373c6f58d8c). The pro.3 compatibility revision is distributed through [v2.8.1-pro.3](https://github.com/Nodemc-developing/UltimateAdvancementAPI/releases/tag/v2.8.1-pro.3); its complete corresponding source is also provided in the archive below. Iterative tidy-tree layout and the native command fallback adapt changes from [IOVEYOUMC0/UltimateAdvancementAPI, revision 14b8895](https://github.com/IOVEYOUMC0/UltimateAdvancementAPI/tree/14b88955db9e27b2ff7aa829fe4f0763a393212a), with original license notices retained.

Corresponding source, build scripts, Gradle wrapper and original license notices are included in `UltimateAdvancementAPI-2.8.1-pro.3-sources.zip`. Extract it and follow its `MODERN_BUILD.md`; `./gradlew build` produces the plugin and source archive. The changes include ordered and batched persistence, team-cache synchronization, owner-thread scheduling, incremental packets and the 26.3 adapter.

UltimateAdvancementAPI retains **LGPL-3.0-or-later** licensing. The original license texts are copied as `UltimateAdvancementAPI-LGPL-3.0.txt` and `UltimateAdvancementAPI-GPL-3.0.txt`, and are also present in the JAR and source archive. Farmersdelight's own licensing is described in the repository's `LICENSE` and `NOTICE.md`.

## SHA-256

| Artifact | SHA-256 |
| --- | --- |
| `UltimateAdvancementAPI-Plugin-2.8.1-pro.3.jar` | `23c42974685fbdbed4b6be6e29903df4be11bed89b1e46cb0136603bdec8a589` |
| `UltimateAdvancementAPI-2.8.1-pro.3-sources.zip` | `6615355a61681a213b929812749e8e0dceeac4601be7baf24393a9ab13fe54dc` |

## Bundled GPL dependency source

The plugin bundles Sparrow YAML 1.0.22. Its actual corresponding source, full fixed upstream repository archive, original GPL text, POM/module metadata and the embedded SnakeYAML fork's source are supplied in [source/sparrow-yaml-1.0.22](source/sparrow-yaml-1.0.22/README.md). That directory includes offline file verification and explicitly disclosed build-only preparation instructions. The delivered source has been checked; an exact binary rebuild has not been performed.

## FluidCore dependency

`FluidCore-0.1.0-SNAPSHOT.jar` is the compile-only dependency used by this version of Farmersdelight-Plugin-Pro. It is not shaded into either Farmersdelight JAR. Install it separately in the server's `plugins/` directory to enable fluid recipes and tanks. It targets Java 21 bytecode and supports CraftEngine 26.9.2 and the existing 26.10 API branch. The compatible build supplied here must replace older Java 25-only FluidCore builds when running Minecraft 1.21.x.

FluidCore is maintained by **ydxc20091**. Its implementation modules are **GPL-3.0-only**, while its API module is **Apache-2.0**. The complete corresponding source, build scripts, Gradle wrapper, dependency source and original notices are included in `FluidCore-0.1.0-SNAPSHOT-sources.zip`. The two license texts are also provided as `FluidCore-GPL-3.0.txt` and `FluidCore-API-Apache-2.0.txt`.

This compatible distribution is available as [Build 3](https://github.com/Nodemc-developing/FluidCore/releases/tag/v0.1.0-SNAPSHOT.3). Extract the source archive and supply both the stable compile baseline and optional native-adapter compile input: `./gradlew distribution -PceJar=/path/to/craft-engine-paper-plugin-26.9.2.jar -PceNativeJar=/path/to/craft-engine-paper-plugin-26.10-SNAPSHOT.jar`. Only one CraftEngine JAR is installed at runtime. See the archive's README for installation and API guidance. The default Farmersdelight build uses the JAR in this directory; `-PfluidCoreJar=<path>` selects a different local build.

| Artifact | SHA-256 |
| --- | --- |
| `FluidCore-0.1.0-SNAPSHOT.jar` | `ae4c584cf18c1c11d716fed395bb8cdcdf05a204d1fcb68b784128220933f04a` |
| `FluidCore-0.1.0-SNAPSHOT-sources.zip` | `332ef7bd7919ef8989242c25e9baf2e7600f6c0b7b5251ce034406466bdbbfe3` |

The runtime remains the Build 3 distribution. The current source archive also includes the version/API build validator update: same-version CE rebuilds do not need an artifact hash allowlist. The digests above identify dependency files and do not restrict which compatible CE build can be installed.
