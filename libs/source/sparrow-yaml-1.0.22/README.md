# Sparrow YAML 1.0.22 corresponding source

This directory delivers upstream source alongside the Sparrow YAML dependency bundled into Farmersdelight-Plugin-Pro and FluidCore. The original authors and license texts remain intact. This is dependency source, not a claim that the dependency was written by this project.

| Component | Delivered preferred-form source | License |
| --- | --- | --- |
| `net.momirealms:sparrow-yaml:1.0.22` | Official Maven sources JAR and complete official repository archive at `e325884f1e92dc4a86b35cdbc90bf0183458a7a6`, including `common`, `minecraft`, tests, build scripts and Gradle wrapper | Original GPL v3 text, `Sparrow-YAML-GPL-3.0.txt` |
| Embedded `snakeyaml-engine:3.1-SNAPSHOT-forked` | Complete source and Maven build at the actual fork maintainer's repository, `Catnies/snakeyaml-engine`, commit `30e9499fcd6e1569b81518db13e12d900c5da2eb` | Apache-2.0, `SnakeYAML-Engine-Apache-2.0.txt` |

The Sparrow archive is downloaded from [its official fixed commit](https://github.com/Xiao-MoMi/sparrow-yaml/tree/e325884f1e92dc4a86b35cdbc90bf0183458a7a6). Its 109 main Java files match the [official Maven 1.0.22 sources](https://repo.momirealms.net/releases/net/momirealms/sparrow-yaml/1.0.22/sparrow-yaml-1.0.22-sources.jar) after line-ending normalization. There is no version tag; the commit, source artifact, POM and module metadata identify the fixed version. The original archives are unmodified.

The fork archive is from [the maintainer's fixed commit](https://github.com/Catnies/snakeyaml-engine/tree/30e9499fcd6e1569b81518db13e12d900c5da2eb). Its POM matches the POM embedded in Sparrow's engine binary after line-ending normalization, and all 224 engine class files name an available preferred-form source file among the fork's 136 main Java files. The same maintainer committed that engine binary into Sparrow, with its build timestamp between the fork commit and its inclusion in Sparrow. These are source/version identification checks; an exact bytecode rebuild has not been performed.

`source-manifest.json` records the fixed commits, official download URLs, file sizes, SHA-256 values and actual verification scope. It also identifies the corresponding-source delivery for other GPL-family components and distinguishes compile-only or benchmark dependencies from bundled runtime code.

## Verify the delivery

With Python 3.10 or newer, run from this directory:

```text
python verify.py
```

This checks the delivered file hashes, published-source equivalence, embedded fork POM, and class-to-source-file coverage without accessing the network or building either plugin. The SHA-256 of the officially downloaded Sparrow binary is `8373d0821e7e49ca76e9dc7d24a4c93eeb8d0bc801cf386ece2a568431770c1c`; it matches the dependency actually resolved for the plugin build. The binary itself is resolved through the existing plugin build, and this source directory does not replace it.

## Prepare a separate source build

The official Sparrow archive retains two upstream build limitations: it uses Shadow 9.4.1 while its wrapper selects Gradle 8.11, and its root archive banner runs `git rev-parse` although a source ZIP has no Git metadata. Shadow 9.3 and later require Gradle 9.0 or later according to [the plugin's compatibility table](https://gradleup.com/shadow/). The preparation script discloses and applies two build-only adjustments in a **new or empty work directory**: Gradle 9.1.0 with the official distribution checksum, and the fixed `e325884f` output banner. It does not modify any Java file, dependency version, original source archive, or Farmersdelight/FluidCore build.

```text
python prepare-build.py /absolute/path/to/new-work-directory
```

The output contains both extracted source repositories and `build-preparation.json`, including the exact adjustments and before/after build-script hashes. Use JDK 25 to run the fixed build tools; Sparrow's own Java target remains 17, and the engine's remains 11.

To rebuild Sparrow with its original upstream-provided engine binary, enter the printed Sparrow directory and run:

```text
gradlew.bat :common:shadowJar :common:sourcesJar
```

On Linux/macOS, make the delivered wrapper executable and use `./gradlew` instead. The bundled library artifact is `common/build/libs/common-1.0.22.jar`; the root project's separately relocated archive is not the Maven `common` library used by these plugins. Do not run publishing tasks; no publishing credentials are required for compilation.

To also rebuild the engine from source, enter the printed SnakeYAML directory and run its fixed Maven 3.9.12 wrapper:

```text
mvnw.cmd -DskipTests package
```

Use `./mvnw` on Linux/macOS. Copy `target/snakeyaml-engine-3.1-SNAPSHOT-forked.jar` into the **prepared** Sparrow directory's `libs/` folder, then run the Sparrow build above. The original engine binary remains preserved inside the unchanged official Sparrow source archive. The JVM/compiler, generated metadata and ZIP timestamps can affect byte-for-byte output, so the source checks do not promise an identical binary hash.

Build tools and Maven/Gradle dependencies require network access unless already cached. The build-only preparation was executed and checked: all 401 Java files across both repositories remain byte-for-byte unchanged, and only the two disclosed build files changed. An offline build and both rebuild commands have **not been executed** as part of this delivery. This directory provides the actual source, licenses and build instructions; it does not report an unperformed rebuild as passed.

## Other GPL-family source deliveries

For Farmersdelight, the separately installed UltimateAdvancementAPI dependency retains LGPL-3.0-or-later. Its matching source, original licenses, build scripts and wrapper are supplied in `libs/UltimateAdvancementAPI-2.8.1-pro.2-sources.zip`; see `libs/README.md` and the archive's `MODERN_BUILD.md`. It is compile-only and is not shaded into Farmersdelight.

FluidCore's GPL-3.0-only implementation source is supplied in its full project source distribution. Its Apache-2.0 API remains separately licensed. CraftEngine and the FluidCore API/server dependency are installed separately and are not shaded into Farmersdelight. JMH is a GPL-2.0-with-Classpath-Exception benchmark dependency and is not present in either released server/API JAR. Sparrow UI is Apache-2.0, SQLite JDBC is Apache-2.0 with its preserved native notices, and bStats/AntiGriefLib are MIT. Their license notices remain part of the plugin distributions.
