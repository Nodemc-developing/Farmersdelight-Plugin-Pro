# Repeated CraftEngine input regression

The priority filter previously rejected two entries with the same ID and file/section before the native template parser ran. A failed resource load could then leave the item browser title uninitialized.

Identical declarations from the same source now load once. Definition bodies, expansion arguments (including null versus empty), namespace and bundled ownership must agree. Different sources or changed declarations still fail with their original locations. Map, list and scalar templates remain unexpanded until CraftEngine processes them. Loading copies preserve source documents.

- 903 tests passed against both CE 26.9.2 and 26.10; 13 new regressions cover duplicates, conflicting content, arguments, pending definitions, mappings and two real native template-parser loading cycles.
- Five repository contract checks passed.
- On the existing registered Paper 26.3 acceptance profile with CE 26.9.2, 32 native checks passed, including two full configuration/resource/recipe reloads and actual browser-title initialization before and after each reload. The loading log contained no resource-load, template-conflict or bundled-definition errors.
- Original plugin files, FD configuration and content cache were restored. No server was created, and no world or database was replaced. The local runner's artifact fingerprint case comparison was corrected before restoring the files.

This does not constitute real-client menu testing, a performance benchmark or validation of recovery after unrelated fatal CE loading errors with edited cached definitions. Install the corrected JAR with a server restart to clear failed-load inputs already retained by the host.

Host lifecycle reviewed against official CE revision `e826194921bb21ff6cd6ee5d9661cce0856b4c3f`: [template parser](https://github.com/Xiao-MoMi/craft-engine/blob/e826194921bb21ff6cd6ee5d9661cce0856b4c3f/core/src/main/java/net/momirealms/craftengine/core/plugin/config/template/TemplateManagerImpl.java), [resource reload](https://github.com/Xiao-MoMi/craft-engine/blob/e826194921bb21ff6cd6ee5d9661cce0856b4c3f/core/src/main/java/net/momirealms/craftengine/core/plugin/CraftEngine.java).
