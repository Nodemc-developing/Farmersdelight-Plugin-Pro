# 1.2.9 — English localization fixes

**Free and open source.** This patch keeps the gameplay and restored content from 1.2.8 and fixes untranslated default labels.

## Compatibility

| Component | Version |
| --- | --- |
| Paper / Folia | Minecraft **1.21–26.3**; Folia requires an available server build |
| CraftEngine | **26.9.2 / 26.10 snapshot**, with the required APIs |
| Java | **21** for 1.21.x; **25** for 26.x |
| FluidCore | Included **0.1.0-SNAPSHOT (Build 3)** |
| UltimateAdvancementAPI | Included **2.8.1-pro.3** |

CraftEngine is installed separately. PlaceholderAPI is optional.

## Changes

- Browser categories and their generated fallbacks use translation keys instead of fixed Chinese names. Custom category names remain unchanged.
- Glass and normal tank base names and instructions use translation keys. Tank capacity, interaction and fluid-display strings include English and Chinese values.
- English reload diagnostics no longer display an unresolved `{issues}` placeholder.
- Added an English configuration guide. Server language settings and client language have different roles; both are documented below.

## English setup

In `plugins/Farmersdelight-Plugin-Pro/config.yml`:

```yaml
language: en_us
```

When using FluidCore, in `plugins/FluidCore/lang.yml`:

```yaml
default-language: en
```

Use `/fd reload all` after editing the FD configuration, or restart after changing both plugins' settings. Set the client language to English and accept the updated content resource pack. Item names and advancements use client translations; server-defined admin messages and some menus use the configured server language. Existing custom translations and category names continue to take precedence.

## Installation

- The server bundle includes the main JAR, FluidCore, UltimateAdvancementAPI, manual content packs, corresponding sources, licenses and an English installation guide.
- Place server JARs in `plugins/`, keeping one version of each plugin.
- Extract the content-pack ZIP into `plugins/CraftEngine/resources/`, directly creating `farmersdelight/` and `farmersdelight_fluids/`. Content installation and updates are manual.
- The API JAR is for addon development, not server installation.

Back up your settings and content packs, then merge these localization changes. Upgrading the JAR alone will not update an existing manual content pack. World, item and container data formats are unchanged.

## Verification scope

**1,001 Java tests**, **7 language-checker cases** and **31 content-checker cases** passed. Paper 26.3 passed **49 native checks with CE 26.9.2** and **49 with the CE 26.10 snapshot**, including real English/Chinese translations, English regional fallback, reload-message parameters, category labels and tank unit conversions. The four native 1.2.8 combinations remain the wider gameplay baseline; this patch does not claim that every supported server patch was retested. See the [compatibility guide](docs/COMPATIBILITY.md) for the matrix, older-client limits and outstanding real-client acceptance items. Folia 26.3 is not covered by those native runs.
