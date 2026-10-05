# 1.2.1 native verification

The fixed local review artifacts were checked on 2026-10-05. [manifest.json](manifest.json) records exact Paper/Folia builds, Java versions, CraftEngine checksums, production JAR checksums and per-run evidence. Each original native JSON is retained byte-for-byte and identified by SHA-256. Separate loading reports contain the two checks of actual CE parser diagnostics.

All fourteen combinations passed: Paper 1.21, 1.21.3, 1.21.4, 1.21.11 and 26.3; Folia 1.21.4 and 26.2; each with CE 26.9.2 and 26.10-20260929.192451-4. There were 266 native checks and 28 loading checks. Explicit `SKIP` entries are excluded from passing counts.

This is headless correctness evidence. It does not verify real-client rendering, delivery to a connected player, manual inventory clicks, a placed-tank world save/restart, real cross-region player transactions or performance. Detached bucket/bottle transfers use real server ItemStacks and native serialization, without a player inventory. Version-limited pale-oak content is separately reported.

Seven registered acceptance profiles were reused serially. Original plugin hashes and registry fields were restored, no new server was created and no owned verification JVM remained. Daily shared servers, their worlds and other tasks were not changed. Full console logs, controller metadata and restoration proof remain in the local review evidence directory; the public records omit machine-specific paths.

See [compatibility details](../../docs/COMPATIBILITY.md) and [release notes](../../RELEASE-NOTES-1.2.1.zh-CN.md). Neighboring patch versions and unavailable official builds are implementation targets, not individually verified by these fourteen runs. UltimateAdvancementAPI's independent 21-run matrix is included in its corresponding source archive.
