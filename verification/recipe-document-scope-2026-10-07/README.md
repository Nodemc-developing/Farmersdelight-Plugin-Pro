# Recipe document ownership regression

Recipe preparation previously constructed every YAML document in enabled CE configuration folders before checking whether it contained FD definitions. Duplicate translation keys in an unrelated language document could therefore abort the entire recipe reload.

Preparation now identifies owned roots from YAML nodes, including quoted/flow keys, section suffixes, root merges and aliases, before constructing a recipe document. Comments, scalar text and nested translation keys do not claim ownership. Native recipes, existing auxiliary roots and factory documents retain strict parsing. Managed files remain strict even when emptied or extended. Errors include the source file; both stages use the same captured text and retain revision checks.

- 913 tests passed against CE 26.9.2 and 26.10, including 10 new regressions. Duplicate keys in actual FD documents still fail. Unknown fields reach the existing schema validator unchanged.
- Five repository contract checks passed.
- On the registered Paper 26.3 / CE 26.9.2 acceptance profile, a temporary unrelated language document contained duplicate translation keys. Actual asynchronous preparation and owner-thread recipe publication succeeded. All 33 native checks and two loading-log checks passed; two full CE resource reloads and browser-title initialization also succeeded. CE's own duplicate-key warning remained visible, with no FD recipe-reload warning.
- Original plugin files, FD configuration and cache were restored, and the temporary fixture was removed. No server was created and no world or database was replaced.

The separate remote `/ce` null-title report still requires the affected server's GUI settings and full loading log. Its cause cannot be established from this asynchronously caught recipe warning. Real-client interaction and performance benchmarks were outside this regression.
