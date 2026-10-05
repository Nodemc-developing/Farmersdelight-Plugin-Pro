# Minecraft compatibility — 1.2.2

This version targets **Minecraft 1.21 through 26.3 on Paper and Folia** with CraftEngine **26.9.2** or the verified **26.10 snapshot**. Install the compatible FluidCore build supplied in `libs/` for fluids and UltimateAdvancementAPI **2.8.1-pro.3** for advancements. Upgrading FD alone does not replace those plugins. UltimateAdvancementAPI is independent of CraftEngine.

| Server version | Java runtime | Gameplay and menus |
| --- | --- | --- |
| 1.21 / 1.21.1 | 21 | Original food component format, legacy inventory-slot packets, Bukkit container/recipe menus |
| 1.21.2 / 1.21.3 | 21 | Separate consumable/remainder components, Bukkit tank and recipe-book menus |
| 1.21.4–1.21.11 | 21 | Sparrow virtual menus and modern item models |
| 26.1 / 26.1.1 / 26.1.2 / 26.2 / 26.3 | 25 | Modern menus/models; version-specific native advancement adapters |

Folia support applies to available official server builds. Paper 1.21.2 and 26.1, and Folia 26.3, were unavailable from the official download service during this verification; those exact builds must not be described as tested.

## Presentation differences on old clients

- Before 1.21.4, tanks retain their capacity, fluid, quantity and stored glass color, but clients lack the modern multi-color model format. Explicit legacy models retain the tank shell and all sixteen fill levels, with a fixed blue liquid texture and the original glass color. Dynamic fluid/glass tinting and generated skillet ingredient overlays are unavailable on those clients.
- Before 1.21.2, invisible GUI fillers use a generated empty CraftEngine model with integer CustomModelData instead of `item_model: air`. Tooltips stay disabled through item metadata; no transparent-tooltip resource layer is added.
- Before 1.21.5, the client cannot hide only the durability line through `tooltip_display`. The normal item name and lore remain visible.
- Bundled wild cabbages, onions and tomatoes share the `higher_tripwire` visual pool so they fit alongside CraftEngine's default assets. Custom block IDs, models, loot and generation remain the same. The carrier can affect the client's selection outline; client interaction still requires visual review.

These are client format differences. Bucket/bottle transactions, recipe execution, storage, cooking, automation and owner-thread scheduling use the same business rules. Unknown or contradictory food conversion settings are reported; source pack files remain unchanged.

## Materials provided by Minecraft

Recipes using newer vanilla materials require the material to exist and its feature to be enabled in the world. The pale-oak cabinet's `minecraft:pale_oak_trapdoor` ingredient is normally available from Minecraft 1.21.4. Some earlier builds include it behind the Winter Drop experiment; a registry entry alone does not make it available in ordinary gameplay. Servers lacking that material report the unavailable ingredient and retain the original definition. No substitute ingredient is silently inserted.

## Implementation boundaries

FD gameplay compiles against **Paper 1.21** and emits Java 21 bytecode. FluidCore also compiles against the earliest API and emits Java 21 bytecode. Both supported CraftEngine versions run on Java 21 and supply the version-specific component codecs and block entities.

CraftEngine 26.10 uses its native sleeping ticker list. CraftEngine 26.9.2 uses a compatible ticker that skips sleeping business work; it still receives the host's ticker callback. FluidCore substitutes indexed chunk-load notifications for the newer native subscription API. These differences are selected during initialization, without reflection in each gameplay tick.

| CraftEngine build | Original JAR SHA-256 |
| --- | --- |
| 26.9.2, supplied stable build | `1f9e0935a11e7d6c7a979f2d521ec24efb715375c876a57ef3cc11e9fb9895aa` |
| 26.9.2, earlier official stable build | `19535f1987e8a27ebe8c6d811e7deae9a1f05dbd3ef3359435aff5a3d819e0f9` |
| 26.10-20260929.192451-4 | `46ebe45f31f3e3f0965179a85cb1f308d8729f53281d6c64f4ef2af5c23d99f6` |

The earlier 26.9.2 build passed loading-contract inspection; native verification uses the supplied stable build and the listed snapshot.

Native villager food access links once against either package name. Item metadata and enchantment differences use cached method handles. Inventory packets select the version's real native format. No new per-tick reflection, inventory-wide refresh or benchmark instrumentation is included in the plugin.

Builtin Bukkit potion-effect and sound types are initialized serially during plugin enable before CraftEngine's delayed parallel configuration load. This avoids the API/Craft implementation class-initialization cycles observed during a cold Paper 1.21.3 startup; document parsing remains asynchronous.

Early food components keep nutrition, effects and the complete `using_converts_to` item when preparing full-hunger eating or temporary skewer use. Cancellation restores only owned fields; changes made by other plugins remain intact.

Exact runs and their limitations are recorded with the local verification results. This compatibility revision does not make a new performance comparison claim.

## Native-format verification — 1.2.2, 2026-10-05

FD 1.2.2 uses the native `farmersdelight_recipes` root and configuration version 4. Its 836-test full suite passed against the supplied stable CE API and the default Maven compile API. After the final FluidCore dependency refresh, all 65 FD fluid tests passed. The refreshed FluidCore platform suite passed 221 tests; its unchanged core retains the recorded 73-test result.

| Server | Exact build | CE | Result |
| --- | --- | --- | --- |
| Paper 26.3 | 140 / `977da0d` | 26.9.2 | 19 native + 2 loading checks passed |
| Folia 26.2 | 7 / `14b7fee` | 26.10-20260929.192451-4 | 19 native + 2 loading checks passed |

Both runs used the same FD JAR, SHA-256 `b9c892f3df897965a408399f20b772737ca7b5884d4c9af98b4389265d6155b1`, and FluidCore SHA-256 `ae4c584cf18c1c11d716fed395bb8cdcdf05a204d1fcb68b784128220933f04a`. The UAA JAR remained unchanged. Actual native loading confirmed configuration version 4, the independent recipe root, exactly 28/107/24 workstation recipes and absence of the removed compatibility registrations. Source data for all 159 bundled recipes was compared without changing item IDs or gameplay rules.

The [verification manifest](../verification/native-format-1.2.2/manifest.json) retains results, fixed artifact hashes and exclusions. Original files, cache, plugins and registry entries were restored; the [restoration proof](../verification/native-format-1.2.2/restore-proof.json) records those checks. This round did not repeat the full-version matrix or performance benchmarks. Real-client rendering, manual menus and the other excluded interactive scenarios remain untested.

## Historical native verification — 1.2.1, 2026-10-05

The same FD and dependency JARs passed **14 combinations**, using both listed CraftEngine builds on every server below. Each combination ran 19 native correctness checks and two checks of actual CraftEngine loading diagnostics: **266 native checks and 28 loading checks passed**. The final FD unit suite passed **846 tests**.

Exact JAR checksums, core builds and byte-for-byte native reports are included in the [verification manifest](../verification/compatibility-1.2.1/manifest.json), with [scope and exclusions](../verification/compatibility-1.2.1/README.md).

| Server | Exact core build | Java | CE 26.9.2 | CE 26.10 snapshot |
| --- | --- | --- | --- | --- |
| Paper 1.21 | 130 / `b1b5d4c` | 21 | Passed | Passed |
| Paper 1.21.3 | 83 / `d6c81da` | 21 | Passed | Passed |
| Paper 1.21.4 | 232 / `12d8fe0` | 21 | Passed | Passed |
| Paper 1.21.11 | 132 / `c5eb079` | 21 | Passed | Passed |
| Paper 26.3 | 140 / `977da0d` | 25 | Passed | Passed |
| Folia 1.21.4 | 6 / `b785bcc` | 21 | Passed | Passed |
| Folia 26.2 | 7 / `14b7fee` | 25 | Passed | Passed |

Checks covered plugin enablement, region ownership, loaded recipes and common items, native wild-generation definitions, version-limited pale-oak recipes, the sleeping ticker bridge, food conversion items, invisible menu fillers, slots 0/8/40 and display-packet codec round trips, temporary skewer metadata restoration, native villager food access and tank model-data preservation. Detached bucket/bottle simulation and execution conserved items and fluid; native item-byte serialization preserved water bottles and tank contents, capacity, identity and stored glass color. Incomplete buckets and full destinations rejected transfers without consumption.

Cold-start verification also found and corrected Bukkit sound class/interface differences, empty potion-effect lists in Paper 1.21 and boolean tooltip settings interpreted as component names. Compatibility handling preserves source configurations and real potion effects.

This was headless verification. Real client appearance, packet delivery to a connected player, manual inventory clicks, a full placed-tank save/restart and cross-region player transactions were not tested by this suite. Pale-oak ingredients were unavailable on 1.21 and remained experimental and disabled in the 1.21.3 test world. Other exact patch versions are covered by the compatibility implementation, not by these 14 runs.

| Verified artifact | SHA-256 |
| --- | --- |
| FD 1.2.1 | `e2dfdd1222951b6cbeb6e39e0db10e383079feb604130f17018a6e479e12a932` |
| FluidCore 0.1.0-SNAPSHOT | `cb5ae27460a4857202b840e31133ccb95c52e7684904a6af8942dfca15a16f91` |
| UltimateAdvancementAPI 2.8.1-pro.3 | `23c42974685fbdbed4b6be6e29903df4be11bed89b1e46cb0136603bdec8a589` |
