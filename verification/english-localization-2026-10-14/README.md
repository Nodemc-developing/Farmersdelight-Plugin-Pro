# English localization verification — 1.2.9

The patch replaces fixed Chinese browser and base tank labels with translation keys and removes an unbound English diagnostic placeholder. Operator category names and custom translations remain intact.

| Check | Result |
| --- | --- |
| Java suite | 1,001 passed, no failures/errors/skips |
| Language-checker cases | 7 passed |
| Content-checker cases | 31 passed |
| Paper 26.3 / CE 26.9.2 | 49 native checks passed |
| Paper 26.3 / CE 26.10 snapshot | 49 native checks passed |

The native runs used `language: en_us` for FD and `default-language: en` for FluidCore. They checked actual FD/CE translations, English regional fallback, reload-message parameter substitution, all generated category labels, tank names/instructions and bound bucket/bottle and fluid-quantity text in both English and Chinese. CE server translations use indexed MiniMessage arguments; Minecraft client JSON retains its native `%s` format.

The helper retains the previous crop, rope, basket, recipe, content-installation and support-state checks. The two CE versions were rotated serially in the same registered dedicated Paper 26.3 profile; original plugins, content/config inputs and the FluidCore language file were restored. The daily shared server was not modified.

`results.json` contains the exact checks and artifact digests. Report identifiers reference retained local raw records; they do not expose private file paths or imply that raw local console logs are included here. The early failed ratio check was retained locally and prompted correction of CE's parameter syntax before the final passing runs.

This is headless correctness verification. It does not simulate a physical English-language player or claim client screenshots, a new complete Minecraft/Folia matrix or performance measurements. Existing client/menu and gameplay acceptance limits remain documented in `docs/COMPATIBILITY.md`.
