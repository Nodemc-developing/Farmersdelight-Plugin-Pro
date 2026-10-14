# External resource conflict regression

The previous managed layer rejected differing external files before CraftEngine could apply its resource conflict rules. Ordinary folder/ZIP combinations containing separate pack icons could abort asset caching. Metadata, fonts and language keys had the same failure mode.

The layer now applies bundled ownership independently of external merging. External collisions execute the host's current typed conditional resolutions with original source paths, including ZIP filesystem paths. Inputs retain the host's folder-before-ZIP order; configured extension exclusions also apply. Unmatched conflicts retain the first external file and report their origins, matching the host's fallback behavior. Only languages that need bundled fallback are parsed by FD; unrelated single language files pass through unchanged.

Output remains staged, original inputs stay read-only, and source revisions are checked before and after native resolutions read them. Changes to the host's rules are captured on the next build. A failed candidate does not replace a completed generation. All resolution work remains in resource loading; gameplay scheduling is unchanged.

Validation:

- 924 unit tests passed against each supplied CE 26.9.2 and 26.10 JAR. The Maven 26.9.1 compilation baseline used by CI also passed 924 tests; this does not expand the declared runtime support range.
- Eleven new tests exercise native CE icon selection, metadata/font/language/sound merges, first-match rule precedence, source ordering, exclusions, reload changes, bundled fallback and failed-generation cleanup. The existing external-duplicate test now checks the host's nonfatal fallback; invalid ZIP traversal remains rejected.
- Five repository contract checks passed.
- Existing registered Paper 1.21.8 / Java 21 and Paper 26.3 / Java 25 acceptance profiles each passed 41 native checks and two loading-log checks with CE 26.9.2. Each run includes initial caching, two full configuration/resource reloads, three actual native resource-pack generations and browser-title checks. Folder/ZIP font markers occur exactly once, both language/sound sources and both metadata overlays survive, and excluded files stay excluded.
- Original plugin JARs, pre-existing FD configuration/cache and CE configuration were restored; temporary fixture inputs were archived after confirming they were unchanged. Existing worlds and databases were preserved. No complete server was created, and no daily shared server was used.

Limits: external-plugin outputs were represented by deterministic directory and ZIP fixtures, without running CustomNameplates itself. No Leaf core, live client, remote customer configuration or performance benchmark was included. These checks do not establish the cause of the separately reported remote `/ce` null-title exception. Paper 26.3 logged existing Windows performance-counter/system-information warnings; no managed-resource generation, handheld-model preparation or CE asset-cache failures appeared.

The local delivery contains an uncommitted source snapshot and the exact CE 26.9.2-built runtime exercised by both native profiles. No GitHub release or push is part of this delivery.
