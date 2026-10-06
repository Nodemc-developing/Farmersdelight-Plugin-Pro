import java.util.zip.ZipFile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("java")
    // Use the Shadow release that supports Gradle 9 and relocates the generated Java classes.
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.huidu.farmersdelight"
version = "1.2.3-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenLocal()
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.momirealms.net/snapshots/") {
        content { includeGroup("net.momirealms") }
    }
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

// The latest published stable Maven API is the compilation baseline; use ceJar for 26.9.2.
val ceVersion = providers.gradleProperty("ceVersion").getOrElse("26.9.1")
require(ceVersion == "26.9.1" || ceVersion == "26.9.2" || ceVersion.startsWith("26.10")) {
    "Compile with CraftEngine 26.9.1 Maven APIs, a local 26.9.2 JAR, or the verified 26.10 snapshot."
}
val ceJar = providers.gradleProperty("ceJar").orElse(providers.environmentVariable("FARMERSDELIGHT_CE_JAR"))
val fluidCoreJar = providers.gradleProperty("fluidCoreJar").orElse(
    layout.projectDirectory.file("libs/FluidCore-0.1.0-SNAPSHOT.jar").asFile.absolutePath)
val ceLibraries = providers.gradleProperty("ceLibraries")
val ceSnapshotProxy = layout.buildDirectory.file("ceSnapshot/craft-engine-proxy.jar")
val ceSnapshotPlugin = layout.buildDirectory.file("ceSnapshot/craft-engine.jar")
val ceSnapshotLibraryDirectory = layout.buildDirectory.dir("ceSnapshot/libraries")
val extractCraftEngineProxy = tasks.register("extractCraftEngineProxy") {
    onlyIf { ceJar.isPresent }
    if (ceJar.isPresent) inputs.file(ceJar.get())
    if (ceLibraries.isPresent) inputs.files(fileTree(ceLibraries.get()) { include("**/*-remapped.jar") })
    outputs.file(ceSnapshotProxy)
    outputs.file(ceSnapshotPlugin)
    outputs.dir(ceSnapshotLibraryDirectory)
    doLast {
        val target = ceSnapshotProxy.get().asFile
        target.parentFile.mkdirs()
        file(ceJar.get()).copyTo(ceSnapshotPlugin.get().asFile, overwrite = true)
        if (ceLibraries.isPresent) copy {
            from(fileTree(ceLibraries.get()) { include("**/*-remapped.jar") })
            into(ceSnapshotLibraryDirectory)
        }
        ZipFile(file(ceJar.get())).use { jar ->
            val entry = jar.getEntry("proxy.jarinjar")
                ?: throw GradleException("CraftEngine snapshot is missing proxy.jarinjar")
            jar.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
}
val ceSnapshotFiles = files(ceSnapshotProxy).builtBy(extractCraftEngineProxy)
val ceSnapshotPluginFiles = files(ceSnapshotPlugin).builtBy(extractCraftEngineProxy)
val ceSnapshotLibraryFiles = files(fileTree(ceSnapshotLibraryDirectory) { include("**/*.jar") }).builtBy(extractCraftEngineProxy)
// CraftEngine remaps these public libraries at startup. Build the same API namespace for
// compilation and tests when a local remapped-library directory is not supplied.
val ceCompileApiLibraries by configurations.creating { isTransitive = false }
val prepareCeCompileApi by tasks.registering(ShadowJar::class) {
    archiveFileName.set("ce-compile-api.jar")
    destinationDirectory.set(layout.buildDirectory.dir("ceSnapshot/compile-api"))
    configurations = listOf(ceCompileApiLibraries)
    relocate("org.snakeyaml.engine", "net.momirealms.craftengine.libraries.snakeyaml.engine")
    relocate("net.momirealms.sparrow.nbt", "net.momirealms.craftengine.libraries.nbt")
    relocate("com.github.benmanes.caffeine", "net.momirealms.craftengine.libraries.caffeine")
    relocate("net.kyori.adventure", "net.momirealms.craftengine.libraries.adventure")
}
val ceCompileApiFiles = files(prepareCeCompileApi.flatMap { it.archiveFile }).builtBy(prepareCeCompileApi)

dependencies {
    add(ceCompileApiLibraries.name, "org.snakeyaml:snakeyaml-engine:3.1.1")
    add(ceCompileApiLibraries.name, "net.momirealms:sparrow-util:0.124")
    add(ceCompileApiLibraries.name, "com.github.ben-manes.caffeine:caffeine:3.3.0")
    add(ceCompileApiLibraries.name, "net.kyori:adventure-api:5.2.0")
    add(ceCompileApiLibraries.name, "net.kyori:adventure-key:5.2.0")
    if (!ceLibraries.isPresent) {
        compileOnly(ceCompileApiFiles)
        testImplementation(ceCompileApiFiles)
    }
    // Compile shared gameplay against the oldest supported server API.
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")
    // Already provided by Paper; only the transport API is needed for per-player display packets.
    compileOnly("io.netty:netty-transport:4.1.135.Final")

    // CraftEngine from the official Maven repository.
    if (ceJar.isPresent) {
        // Allows compatibility checks against unpublished snapshots without bundling the server plugin.
        compileOnly(ceSnapshotPluginFiles)
        compileOnly(ceSnapshotFiles)
        if (ceLibraries.isPresent) {
            compileOnly(ceSnapshotLibraryFiles)
        }
    } else {
        compileOnly("net.momirealms:craft-engine-bukkit:$ceVersion")
        compileOnly("net.momirealms:craft-engine-core:$ceVersion")
        // CE 26.9.1 keeps proxy classes in its jar-in-jar proxy artifact.
        compileOnly("net.momirealms:craft-engine-bukkit-proxy:$ceVersion")
    }

    compileOnly("me.clip:placeholderapi:2.11.6")
    // AntiGriefLib: unified protection facade over 24+ land/claim plugins (MIT). Bundled and relocated:
    // Bukkit plugin classloaders are NOT isolated for legacy plugin.yml plugins (PluginClassLoader falls
    // back to the other plugins' loaders), so an un-relocated copy is shared server-wide and whichever
    // plugin loads first decides the version everyone gets. isTransitive=false skips its compile-only
    // annotations. Its per-plugin providers load only when the matching land plugin is present.
    implementation("net.momirealms:antigrieflib:1.0.11") { isTransitive = false }
    // bStats metrics (Maven Central). Relocated for the same reason, which is also what bStats itself
    // requires of every plugin that bundles it.
    implementation("org.bstats:bstats-bukkit:3.2.1")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0") { isTransitive = false }
    implementation("net.momirealms:sparrow-yaml:1.0.22")
    implementation("net.momirealms:sparrow-ui:beta.38") { isTransitive = false }
    // UltimateAdvancementAPI: separate server plugin; vendored only for offline compile against its API.
    compileOnly(files("libs/UltimateAdvancementAPI-Plugin-2.8.1-pro.3.jar"))
    // FluidCore remains a separate optional plugin and owns these runtime types.
    compileOnly(files(fluidCoreJar.get()))
    testImplementation(files(fluidCoreJar.get()))
    // Unit fixtures exercise modern metadata; production compilation still uses the 1.21 API above.
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    if (ceJar.isPresent) {
        testImplementation(ceSnapshotPluginFiles)
        testImplementation(ceSnapshotFiles)
        if (ceLibraries.isPresent) {
            testImplementation(ceSnapshotLibraryFiles)
        }
    } else {
        testImplementation("net.momirealms:craft-engine-bukkit:$ceVersion")
        testImplementation("net.momirealms:craft-engine-core:$ceVersion")
        testImplementation("net.momirealms:craft-engine-bukkit-proxy:$ceVersion")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.netty:netty-transport:4.1.135.Final")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.all {
    resolutionStrategy {
        // Force patched transitive dependency versions for compilation only; they are not bundled.
        force("org.codehaus.plexus:plexus-utils:4.0.3")
        force("org.apache.commons:commons-lang3:3.18.0")
    }
}

val debugToolsBuild = providers.gradleProperty("debugTools")
    .map { it.equals("true", ignoreCase = true) }
    // Debug tools require -PdebugTools=true. Runtime statistics are available through /fd stats.
    .orElse(false)
val pluginArchiveBaseName = "Farmersdelight-Plugin-Pro"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    useJUnitPlatform()
    dependsOn("shadowJar", "apiJar")
    inputs.file(tasks.shadowJar.flatMap { it.archiveFile }).withPropertyName("pluginJar")
    doFirst {
        systemProperty("fd.pluginJar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("fd.apiJar", tasks.named<Jar>("apiJar").get().archiveFile.get().asFile.absolutePath)
    }
    // ApiDocsDriftTest reads the api pages from the wiki repository (checked out beside this repository's
    // parent locally, under wiki/ in CI). Declaring them as inputs keeps the task from staying "up to date"
    // when only a page changed, which is exactly the drift the test exists to catch. Only a checkout that
    // is actually present can be declared: a directory input has to exist.
    listOf(
        file("api-docs"),
        file("wiki/api-docs"),
        file("../../FarmersdelightPluginWiKi/api-docs"),
    ).filter { it.isDirectory }.forEach { docs ->
        inputs.dir(docs).withPropertyName("apiDocs:${docs.name}")
    }
    doLast {
        // Incomplete JUnit reports must not turn a test-listener failure into a successful build.
        val skipped = Regex("(?m)^\\s*<skipped(?:\\s|/|>)")
        val reportsWithSkips = reports.junitXml.outputLocation.get().asFile.walkTopDown()
            .filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }
            .filter { skipped.containsMatchIn(it.readText()) }.toList()
        check(reportsWithSkips.isEmpty()) {
            "Tests were skipped or not fully reported: ${reportsWithSkips.joinToString { it.name }}"
        }
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("pluginVersion", project.version.toString())
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version.toString())
    }
}

sourceSets {
    named("main") {
        java.srcDir(layout.buildDirectory.dir("generated/sources/buildFlags"))
        if (debugToolsBuild.get()) {
            java.srcDir("src/debugTools/java")
        }
    }
}

val writeBuildFlags = tasks.register("writeBuildFlags") {
    val outputDir = layout.buildDirectory.dir("generated/sources/buildFlags/com/huidu/farmersdelight")
    inputs.property("debugTools", debugToolsBuild)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("BuildFlags.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.huidu.farmersdelight;

            public final class BuildFlags {

                public static final boolean DEBUG_TOOLS = ${debugToolsBuild.get()};

                private BuildFlags() {
                }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
    }
}

tasks.compileJava {
    dependsOn(writeBuildFlags)
    doFirst {
        if (!debugToolsBuild.get()) {
            delete(layout.buildDirectory.dir("classes/java/main/com/huidu/farmersdelight/debug"))
        }
    }
}

tasks.shadowJar {
    archiveBaseName.set(pluginArchiveBaseName)
    // Only the debug build, which never leaves the development machine, carries a classifier.
    archiveClassifier.set(if (debugToolsBuild.get()) "debug" else "")
    if (!debugToolsBuild.get()) {
        exclude("com/huidu/farmersdelight/debug/**")
    }
    manifest {
        attributes(
            "Implementation-Title" to "Farmersdelight-Plugin-Pro",
            "Implementation-Version" to project.version
        )
    }
    relocate("org.bstats", "com.huidu.farmersdelight.libs.bstats")
    relocate("net.momirealms.antigrieflib", "com.huidu.farmersdelight.libs.antigrieflib")
    relocate("net.momirealms.sparrow.yaml", "com.huidu.farmersdelight.libs.sparrow.yaml")
    relocate("net.momirealms.sparrow.ui", "com.huidu.farmersdelight.libs.sparrow.ui")
    from("LICENSE") {
        into("META-INF/licenses")
        rename { "Farmersdelight-Plugin-Pro-AGPL-3.0.txt" }
    }
}

tasks.jar {
    enabled = false
}

// api-only jar: just com.huidu.farmersdelight.api.** — for addons to compile against (compileOnly) without
// exposing internal packages. Addons reference only api.**, so this is all they need; the real
// FD plugin provides the implementation at runtime. Output: build/libs/<base>-<version>-api.jar.
tasks.register<Jar>("apiJar") {
    group = "build"
    description = "Builds an api-only jar (com.huidu.farmersdelight.api.**) for addon development."
    dependsOn(tasks.classes)
    archiveClassifier.set("api")
    from(sourceSets.main.get().output) {
        include("com/huidu/farmersdelight/api/**")
    }
    from("LICENSE") {
        into("META-INF/licenses")
        rename { "Farmersdelight-Plugin-Pro-AGPL-3.0.txt" }
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
