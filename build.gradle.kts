import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.kover)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.pitest)
}

// `conformance/` is the cross-language fixture set, and a KMP `commonTest` cannot read files on Native. The
// JSON is baked into a generated Kotlin constant so every target's tests read the same bytes.
val generateConformanceFixtures by tasks.registering {
    val source = layout.projectDirectory.dir("conformance")
    val output = layout.buildDirectory.dir("generated/conformance/kotlin")
    inputs.dir(source).withPropertyName("fixtures")
    outputs.dir(output).withPropertyName("sources")

    doLast {
        // Each fixture is a raw string, so its own line lengths are the generated file's line lengths and the
        // result satisfies ktlint without an exclusion. `prependIndent` sets up the `trimIndent` that follows.
        val entries =
            source.asFile
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .sortedBy { it.invariantSeparatorsPath }
                .joinToString(",\n") { file ->
                    val path = file.relativeTo(source.asFile).invariantSeparatorsPath
                    val body = file.readText().trimEnd('\n')
                    require(!body.contains("\"\"\"")) { "$path contains a triple quote" }
                    val indented = body.replace("$", "\${'$'}").prependIndent("            ")
                    "        \"$path\" to\n            \"\"\"\n$indented\n            \"\"\".trimIndent()"
                }

        val target = output.get().file("ConformanceFixtures.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            |// Generated from conformance/ by the generateConformanceFixtures task — do not edit.
            |package com.sierranevadalabs.systemone.sdk.conformance
            |
            |internal val FIXTURE_FILES: Map<String, String> =
            |    mapOf(
            |$entries,
            |    )
            |
            """.trimMargin(),
        )
    }
}

// The README's fenced ```kotlin blocks and the ones inside our own KDoc are both compiled, so a snippet that no
// longer matches the public API breaks the build instead of the reader. This is what Python's doc tests do with
// Sybil and what the JS repo lacks. Each block must be self-contained and must carry the imports a reader would
// copy. Kotlin allows `import` only at file level, so the imports are hoisted to the top of the generated file —
// which also makes a stale package or class name in a README import line a compile error.
//
// A KDoc block is already written from inside the SDK's own package, so its snippets are generated into that
// package (nothing to import) and take the client as a parameter: what the snippet documents is a call on a
// client the reader already has, so there is no `val client = …` to copy. README snippets keep their own package
// and construct their own client, so they stay no-argument functions.
val generateDocSnippets by tasks.registering {
    val readme = layout.projectDirectory.file("README.md")
    val sources = layout.projectDirectory.dir("src")
    val output = layout.buildDirectory.dir("generated/docs/kotlin")
    inputs.file(readme).withPropertyName("readme")
    inputs.dir(sources).withPropertyName("kdocSources")
    outputs.dir(output).withPropertyName("sources")

    doLast {
        val fence = Regex("```kotlin\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

        fun blocksIn(text: String): List<String> =
            fence
                .findAll(text)
                .map { it.groupValues[1].trimIndent().trimEnd() }
                .toList()

        /** A KDoc body keeps the leading ` * ` on every line, so that marker is stripped before compiling. */
        fun kdocBlocksIn(text: String): List<String> =
            fence
                .findAll(text)
                .map { match ->
                    match.groupValues[1]
                        .lineSequence()
                        .joinToString("\n") { line -> line.trimStart().removePrefix("*").removePrefix(" ") }
                        .trim()
                }.toList()

        /** The blocks as compilable bodies, plus the `import` lines hoisted to the caller's file level. */
        fun render(
            blocks: List<String>,
            signature: (Int) -> String,
        ): Pair<String, String> {
            val imports =
                blocks
                    .flatMap { block -> block.lineSequence().filter { it.startsWith("import ") } }
                    .distinct()
                    .sorted()
                    .joinToString("\n")
            val bodies =
                blocks.mapIndexed { index, block ->
                    val body =
                        block
                            .lineSequence()
                            .filterNot { it.startsWith("import ") }
                            .toList()
                            .dropWhile { it.isBlank() }
                            .dropLastWhile { it.isBlank() }
                            .joinToString("\n") { line -> if (line.isBlank()) "" else "    " + line.trimEnd() }
                    "${signature(index)} {\n$body\n}"
                }
            return imports to bodies.joinToString("\n\n")
        }

        fun source(
            packageName: String,
            origin: String,
            imports: String,
            bodies: String,
        ): String {
            val header =
                "// Generated from $origin by generateDocSnippets — do not edit.\n" +
                    "// Compiling this file is the doc test: a snippet that no longer matches the public API fails the build.\n" +
                    "package $packageName"
            return buildString {
                append(header)
                if (imports.isNotEmpty()) append("\n\n").append(imports)
                if (bodies.isNotEmpty()) append("\n\n").append(bodies)
                append("\n")
            }
        }

        val readmeBlocks = blocksIn(readme.asFile.readText())
        // Without this, a README with no blocks would compile an empty file and the doc test would be vacuous.
        check(readmeBlocks.isNotEmpty()) { "README.md has no ```kotlin blocks to compile" }

        val kdocBlocks =
            sources.asFile
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.invariantSeparatorsPath }
                .flatMap { kdocBlocksIn(it.readText()) }
                .toList()
        // Without this, deleting the sole KDoc block would silently compile an empty file and the doc test would be vacuous.
        check(kdocBlocks.isNotEmpty()) { "src/ has no Kotlin KDoc blocks to compile" }

        val (readmeImports, readmeBodies) = render(readmeBlocks) { "internal suspend fun readmeSnippet${it + 1}()" }
        val (kdocImports, kdocBodies) = render(kdocBlocks) { "internal suspend fun kdocSnippet${it + 1}(client: SystemOneClient)" }

        output.get().asFile.mkdirs()
        output
            .get()
            .file("ReadmeSnippets.kt")
            .asFile
            .writeText(source("com.sierranevadalabs.systemone.sdk.docs", "README.md's ```kotlin blocks", readmeImports, readmeBodies))
        output
            .get()
            .file("KdocSnippets.kt")
            .asFile
            .writeText(source("com.sierranevadalabs.systemone.sdk", "the ```kotlin blocks in src/**/*.kt KDoc", kdocImports, kdocBodies))
    }
}

kotlin {
    // AGP 9 requires com.android.kotlin.multiplatform.library; androidTarget() is going away. Host (unit)
    // tests are off by default in this plugin and must be opted into, or commonTest never runs on Android.
    android {
        namespace = "com.sierranevadalabs.systemone.sdk"
        compileSdk = 36 // what Ktor's own Android artifacts are built with
        minSdk = 28 // Ktor's floor as of 3.5.x; see the AAR-metadata check in CI
        withHostTestBuilder {}.configure {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    linuxX64()
    linuxArm64()

    // Node runs the jsTest suite, which drives MockEngine only and needs no browser. A browser caller keeps
    // the Fetch engine and supplies the API key through its own proxy, so the browser is never a test host.
    js {
        nodejs()
    }

    // macosX64 is deliberately absent. Kotlin deprecated the x86_64 macOS target in 2.3.20 and KGP 2.3.21
    // raises the declaration to an error, so it cannot be compiled against our pinned toolchain. Apple desktop
    // coverage is macosArm64. iosX64 is Tier 3 but not deprecated, so it stays as a compile-only simulator
    // target. See the toolchain ticket for the corrected tier list.

    // The default hierarchy template builds appleMain/appleTest, iosMain, macosMain, nativeMain, linuxMain
    // (linuxX64 + linuxArm64), jsMain and jsTest from the target list above. Do not add manual dependsOn()
    // edges — one of them silently disables the whole template. JVM and Android cannot share an intermediate
    // source set, and neither can js and native.

    compilerOptions {
        // The consumer floor is Kotlin 2.3, and the dependency chain sets it, not this pin: Ktor 3.5.2 and
        // kotlinx-serialization 1.11.0 ship KLIB ABI 2.3.0 and JVM metadata 2.3.0. The KLIB ABI is
        // one-directional, so no consumer below 2.3 can resolve the native targets. Matching language and API
        // version to that floor keeps one advertised number instead of claiming reach the artifact lacks.
        // JVM metadata one language version ahead is readable, so JVM consumers on 2.2.x+ work.
        // `verifyConsumerFloor` fails if a toolchain move changes either floor.
        languageVersion = KotlinVersion.KOTLIN_2_3
        apiVersion = KotlinVersion.KOTLIN_2_3
        explicitApi = ExplicitApiMode.Strict
    }

    sourceSets {
        commonMain.dependencies {
            // `JsonElement` is part of the public surface (questions, answers, state), so consumers need it on
            // their compile classpath rather than only at runtime.
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
        }
        commonTest {
            kotlin.srcDir(generateConformanceFixtures)
            kotlin.srcDir(generateDocSnippets)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.mock)
            }
        }
        // The default engines, so a caller configures nothing. Ktor publishes all four as plain JVM JARs for
        // Android as well as the JVM, which is why androidMain can reuse the OkHttp artifact.
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        jvmTest.dependencies {
            // Tier 2 binds CIO to a loopback socket, and CIO is the engine the ticket names for it.
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        appleMain.dependencies { implementation(libs.ktor.client.darwin) }
        linuxMain.dependencies { implementation(libs.ktor.client.cio) }
        jsMain.dependencies { implementation(libs.ktor.client.js) }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "systemone-kmp"
        description = "Kotlin Multiplatform SDK for the System One API"
        inceptionYear = "2026"
        url = "https://github.com/snevadalabs/systemone-kmp"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "snevadalabs"
                name = "Sierra Nevada Labs"
                url = "https://github.com/snevadalabs"
            }
        }
        scm {
            url = "https://github.com/snevadalabs/systemone-kmp"
            connection = "scm:git:git://github.com/snevadalabs/systemone-kmp.git"
            developerConnection = "scm:git:ssh://git@github.com/snevadalabs/systemone-kmp.git"
        }
    }
}

// `explicitApi(ExplicitApiMode.Strict)` does **not** require KDoc: an undocumented public declaration compiles
// clean, which the typed-question prototype measured by deleting a doc comment. Dokka is the gate that does
// catch it. `reportUndocumented` logs `Undocumented: <signature>` once per declaration Dokka would publish
// undocumented, and the publication's `failOnWarning` turns any Dokka warning into a build failure. It reuses
// the Dokka run the Apple CI lane already performs, so it needs no new dependency and no new task, and the
// warning list is the work list. Do not turn either flag off to make a build pass.
dokka {
    dokkaSourceSets.configureEach {
        reportUndocumented.set(true)
    }
    dokkaPublications.configureEach {
        failOnWarning.set(true)
    }
}

// The version and the changelog are two sources of truth that must not drift. SNAPSHOT versions require an
// Unreleased heading; release versions must name themselves at the top.
val checkVersion by tasks.registering {
    group = "verification"
    description = "Asserts the gradle.properties version matches the top CHANGELOG.md heading."

    val changelog = layout.projectDirectory.file("CHANGELOG.md")
    val sources = layout.projectDirectory.dir("src")
    val declaredVersion = version.toString()
    inputs.file(changelog)
    inputs.dir(sources)
    inputs.property("version", declaredVersion)

    doLast {
        val headings = changelog.asFile.readLines().filter { it.startsWith("## ") }
        check(headings.isNotEmpty()) { "CHANGELOG.md has no '## ' headings" }
        val top = headings.first().removePrefix("## ").trim()

        if (declaredVersion.endsWith("-SNAPSHOT")) {
            check(top.contains("Unreleased", ignoreCase = true)) {
                "version $declaredVersion is a snapshot but CHANGELOG.md's top heading is '$top'"
            }
        } else {
            check(top.contains(declaredVersion)) {
                "version $declaredVersion does not match CHANGELOG.md's top heading '$top'"
            }
        }

        // The wire header `X-TypeSafe-SDK` carries a version, and a Kotlin constant cannot read
        // gradle.properties. Assert the two agree rather than letting them drift.
        val expected = declaredVersion.removeSuffix("-SNAPSHOT")
        val declaredInSource =
            sources.asFile
                .walkTopDown()
                .filter { it.extension == "kt" }
                .mapNotNull { file ->
                    // `explicitApi(Strict)` forces an explicit return type on the public constant, so the type
                    // annotation is optional here rather than absent.
                    Regex("SDK_VERSION\\s*(?::\\s*\\w+)?\\s*=\\s*\"([^\"]+)\"").find(file.readText())?.groupValues?.get(1)
                }.toList()
        check(declaredInSource == listOf(expected)) {
            "SDK_VERSION in src/ is $declaredInSource, expected [$expected] from gradle.properties"
        }
    }
}

// The signing key arrives from the environment and never from the repository, so the wrong key still produces
// valid, verifiable signatures — under someone else's name. That happened once, on a release where every other
// check passed. This reads the issuer out of every signature for the version being released, and refuses to
// publish unless each one names the key this project publishes under.
val expectedSigningKeyId = "594129A6F5E2E4E5"

val checkSigningKey by tasks.registering {
    group = "verification"
    description = "Asserts every produced signature names the project's Maven Central key."

    val declaredVersion = version.toString()
    // Signatures for the artifacts, plus the staged layout the Central plugin zips and uploads — which is
    // where the `.pom` and `.module` signatures live, and what a consumer's build actually reads.
    val signatureRoots =
        listOf(layout.buildDirectory.dir("signatures"), layout.buildDirectory.dir("publishing/mavenCentral"))
    val base = layout.projectDirectory.asFile
    dependsOn(tasks.matching { it.name.startsWith("sign") && it.name.endsWith("Publication") })
    inputs.files(signatureRoots)

    doLast {
        // Each root keeps every version ever built here, so compare against this one only.
        val forThisVersion = Regex("-" + Regex.escape(declaredVersion) + "[.-]")
        val signatures =
            signatureRoots
                .flatMap { root ->
                    root
                        .get()
                        .asFile
                        .walkTopDown()
                        .filter {
                            it.extension == "asc" &&
                                forThisVersion.containsMatchIn(it.name) &&
                                !it.name.contains("-SNAPSHOT")
                        }.toList()
                }.sorted()
        check(signatures.isNotEmpty()) {
            "no signatures for $declaredVersion under build/signatures — sign a publication first"
        }

        val wrong =
            signatures.filterNot { file ->
                val packets =
                    try {
                        ProcessBuilder("gpg", "--list-packets", file.absolutePath)
                            .redirectErrorStream(true)
                            .start()
                            .inputStream
                            .bufferedReader()
                            .readText()
                    } catch (cause: java.io.IOException) {
                        throw GradleException("gpg is required to read the signing key id: ${cause.message}", cause)
                    }
                packets.contains("issuer key ID $expectedSigningKeyId")
            }

        check(wrong.isEmpty()) {
            "these signatures do not name the project key $expectedSigningKeyId:\n" +
                wrong.joinToString("\n") { "  ${it.relativeTo(base)}" }
        }
        logger.lifecycle("checkSigningKey: ${signatures.size} signature(s) name $expectedSigningKeyId")
    }
}

// A publish is the only moment a wrong key can be caught for free, so every publish task depends on the check.
tasks
    .matching {
        it.name.startsWith("publish") && (it.name.contains("MavenCentral") || it.name.contains("MavenLocal"))
    }.configureEach { dependsOn(checkSigningKey) }

// Java 8 bytecode is the level the pinned toolchain promises consumers, and `jvmTarget` alone does not prove it.
// The class file's major version does: 52 is Java 8.
val checkJvmBytecode by tasks.registering {
    group = "verification"
    description = "Asserts the compiled JVM classes are Java 8 (class file major version 52)."

    val classesDir = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    dependsOn("compileKotlinJvm")
    inputs.dir(classesDir)

    doLast {
        val classes =
            classesDir
                .get()
                .asFile
                .walkTopDown()
                .filter { it.extension == "class" }
                .toList()
        check(classes.isNotEmpty()) { "no compiled JVM classes under $classesDir" }
        for (classFile in classes) {
            val bytes = classFile.readBytes()
            val major = (bytes[6].toInt() and 0xFF shl 8) or (bytes[7].toInt() and 0xFF)
            check(major == 52) { "$classFile is class file major version $major, expected 52 (Java 8)" }
        }
    }
}

// The advertised Kotlin consumer floor. The dependency chain sets it, not this module: Ktor 3.5.2 and
// kotlinx-serialization 1.11.0 ship KLIB ABI 2.3.0 and JVM metadata 2.3.0. KLIB ABI compatibility is
// one-directional, so no consumer below 2.3 can resolve the native targets. This task reads the built
// artifacts rather than the DSL: a compiler or language-version move changes the stamp and fails the check
// until the floor, the README and the build comment are updated on purpose.
val kotlinFloor = "2.3"
val jvmOnlyFloor = "2.2"

val verifyConsumerFloor by tasks.registering {
    group = "verification"
    description = "Asserts the built artifacts still declare the advertised Kotlin consumer floor ($kotlinFloor)."

    val classesDir = layout.buildDirectory.dir("classes/kotlin/jvm/main")

    // The commonMain metadata klib is host-neutral, unlike an Apple klib, so every host can read the KLIB ABI.
    val metadataManifest = layout.buildDirectory.file("classes/kotlin/metadata/commonMain/default/manifest")
    dependsOn("compileKotlinJvm", "compileCommonMainKotlinMetadata")
    inputs.dir(classesDir)
    inputs.file(metadataManifest)
    inputs.property("floor", kotlinFloor)

    doLast {
        // The JVM metadata version equals the language version; a consumer reads one version ahead of it.
        val classFile =
            classesDir
                .get()
                .asFile
                .walkTopDown()
                .firstOrNull { it.name == "SystemOneResponse.class" }
                ?: error("no compiled SystemOneResponse.class under $classesDir")
        val javapName = if (System.getProperty("os.name").startsWith("Win")) "javap.exe" else "javap"
        val javap = File(System.getProperty("java.home"), "bin/$javapName")
        val process =
            ProcessBuilder(javap.absolutePath, "-v", "-p", classFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "${javap.absolutePath} failed on $classFile:\n$output" }
        val jvmVersion =
            Regex("""mv=\[(\d+),(\d+)""")
                .find(output)
                ?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
                ?: error("no kotlin.Metadata mv in $classFile")
        check(jvmVersion == kotlinFloor) {
            "JVM metadata is $jvmVersion, expected $kotlinFloor. The language version moved, so JVM " +
                "consumers on $jvmOnlyFloor.x would stop reading the artifact. Update the floor deliberately."
        }

        // The KLIB ABI comes from the compiler, not the language version, and it is one-directional.
        val abi =
            Regex("""(?m)^abi_version=(.+)$""")
                .find(metadataManifest.get().asFile.readText())
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?: error("no abi_version in $metadataManifest")
        val abiVersion = abi.split(".").take(2).joinToString(".")
        check(abiVersion == kotlinFloor) {
            "KLIB ABI is $abi, expected $kotlinFloor. The compiler moved, so no consumer below " +
                "$abiVersion can resolve the native targets. Update the floor deliberately."
        }
        logger.lifecycle("Kotlin consumer floor $kotlinFloor (JVM-only $jvmOnlyFloor): JVM metadata $jvmVersion, KLIB ABI $abi")
    }
}

tasks.named("check") {
    // The coverage floor, the KDoc gate and the consumer-floor guard are part of the check, not reports
    // someone remembers to open. `dokkaGenerate` is host-neutral here: it compiles Kotlin metadata only,
    // never an Apple klib.
    dependsOn(checkVersion, checkJvmBytecode, "koverVerify", "dokkaGenerate", verifyConsumerFloor)
}

// Opt-in empirical proof of the JVM floor above. `verifyConsumerFloor` reads the artifact stamp; this task
// compiles a tiny consumer with older compilers in a subprocess, so an old compiler never loads into the
// Gradle daemon. A consumer reads JVM metadata one language version ahead, so Kotlin 2.2 must compile the
// consumer and Kotlin 2.1 must reject it. It downloads two compilers, so it is not part of `check`.
val consumerProofCases = listOf("2.2.21" to true, "2.1.21" to false)

val consumerProofCompilers: Map<String, Configuration> =
    consumerProofCases.associate { (version, _) ->
        val name = "consumerProofCompiler${version.replace(".", "")}"
        configurations.create(name) {
            isCanBeConsumed = false
            isCanBeResolved = true
            description = "The Kotlin $version compiler that proves the JVM consumer floor."
        }
        dependencies.add(name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:$version")
        version to configurations.getByName(name)
    }

val verifyConsumerJvmFloor by tasks.registering {
    group = "verification"
    description = "Compiles a tiny consumer with Kotlin 2.2 (must pass) and Kotlin 2.1 (must fail)."

    val jvmMain =
        kotlin.targets
            .getByName("jvm")
            .compilations
            .getByName("main")
    val consumerClasspath = files(jvmMain.output.classesDirs, jvmMain.compileDependencyFiles)
    val workDir = layout.buildDirectory.dir("consumer-jvm-floor")

    dependsOn("compileKotlinJvm")

    doLast {
        val dir = workDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val source = File(dir, "Consumer.kt")
        source.writeText(
            """
            import com.sierranevadalabs.systemone.sdk.ChoiceAnswer
            import com.sierranevadalabs.systemone.sdk.SystemOneClient
            import com.sierranevadalabs.systemone.sdk.SystemOneConfig
            import com.sierranevadalabs.systemone.sdk.choice
            import com.sierranevadalabs.systemone.sdk.systemOne

            suspend fun consumer(apiKey: String): String {
                val client = SystemOneClient(SystemOneConfig(apiKey = apiKey))
                val category =
                    choice("category", "What is this about?", mapOf("billing" to null, "technical" to null))
                client.use {
                    val response = it.systemOne("I was charged twice.", category)
                    val answer: ChoiceAnswer = response[category]
                    return answer.choice
                }
            }
            """.trimIndent() + "\n",
        )

        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        consumerProofCases.forEach { (version, mustPass) ->
            val compilerJars =
                consumerProofCompilers
                    .getValue(version)
                    .files
                    .joinToString(File.pathSeparator) { it.absolutePath }
            val builder =
                ProcessBuilder(
                    java,
                    "-cp",
                    compilerJars,
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib",
                    "-classpath",
                    consumerClasspath.asPath,
                    "-d",
                    File(dir, "out-$version").absolutePath,
                    source.absolutePath,
                )
            val process = builder.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (mustPass) {
                check(exit == 0) { "Kotlin $version must compile a consumer of this artifact but failed:\n$output" }
                logger.lifecycle("consumer compiled on Kotlin $version, as expected")
            } else {
                check(exit != 0) { "Kotlin $version compiled a consumer of this artifact: the JVM floor is too low." }
                check(output.contains("incompatible version of Kotlin")) {
                    "Kotlin $version failed for a reason other than the metadata version:\n$output"
                }
                val rejection = output.lineSequence().firstOrNull { it.contains("error:") }?.trim() ?: output.trim()
                logger.lifecycle("consumer rejected on Kotlin $version, as expected: $rejection")
            }
        }
    }
}

// The live tier is opt-in through this property; a default `check` can never reach the network or spend money.
// The API key is checked inside the tests, so `-Ptypesafe.live=true` with no key fails rather than skips.
tasks.named<Test>("jvmTest") {
    systemProperty("typesafe.live", providers.gradleProperty("typesafe.live").getOrElse("false"))
}

// Coverage over the SDK source, measured by the JVM tests: Kover supports JVM and Android only, so this is the
// JVM compilation of commonMain + jvmMain (Apple and Linux source sets are not measurable). The code measures
// 94.6% line coverage; the floor is set at what it actually achieves. It is a gate, not a target — do not lower
// it to make a build pass.
kover {
    reports {
        verify {
            rule {
                minBound(94)
            }
        }
    }
}

// Mutation testing is an on-demand task, deliberately outside `check`: it re-runs the JVM suite once per mutant.
// The `info.solidsoft.pitest` plugin registers its own task only under the `java` plugin, which a KMP module
// never applies, so `PitestTask` is registered here against the JVM compilation's own classpath. Nothing here
// reaches a published artifact: it is the `pitest` configuration and one verification task.
// A survivor is a behaviour no test observes: read every one before trusting a run.
val jvmMainCompilation =
    kotlin.targets
        .getByName("jvm")
        .compilations
        .getByName("main")
val jvmTestCompilation =
    kotlin.targets
        .getByName("jvm")
        .compilations
        .getByName("test")

dependencies {
    add("pitest", libs.pitest.command.line)
}

tasks.register<info.solidsoft.gradle.pitest.PitestTask>("pitestJvm") {
    group = "verification"
    description = "Runs PIT mutation analysis over the JVM compilation of commonMain. On demand; not in check."
    dependsOn(jvmTestCompilation.compileTaskProvider)

    targetClasses.set(setOf("com.sierranevadalabs.systemone.sdk.*"))
    targetTests.set(setOf("com.sierranevadalabs.systemone.sdk.*"))
    sourceDirs.from(
        layout.projectDirectory.dir("src/commonMain/kotlin"),
        layout.projectDirectory.dir("src/jvmMain/kotlin"),
    )
    mutators.set(setOf("STRONGER"))

    // This filter is scoped to the method name only, and it removes ALL mutants inside an inlined lambda
    // body, not just the unobservable Unit-returning ones. 21 of the 40 mutants it excludes were detected
    // before the filter was added (16 killed, 5 timed out), and are now unmeasured — including the retry
    // status predicate, the exception-retry predicate, and the retry-count header (RetryKt.applyPolicy$lambda$0..2,
    // Retry.kt:111-113), the delay function (RetryKt.applyPolicy$lambda$3, Retry.kt:114-117), and both
    // per-call and client timeout wiring (Transport.request$lambda$0$1/$0$2, TransportKt.createTransport$lambda$3$1,
    // Transport.kt:70-71, 151-152). `avoidCallsTo` and an `*$*` class glob were both tried and rejected as too
    // coarse for Kotlin, since they suppress every mutant on a line that merely mentions `runCatching`/`Intrinsics`
    // (which includes whole one-expression functions such as `parseBody`) and every mutant inside a suspend body.
    excludedMethods.set(setOf("*lambda*"))

    verbosity.set("VERBOSE")
    timestampedReports.set(false)
    outputFormats.set(setOf("XML", "HTML"))
    threads.set(Runtime.getRuntime().availableProcessors())
    failWhenNoMutations.set(true)

    // PIT is launched from the `pitest` configuration and analyses the classes the JVM tests already run
    // against: the main output plus jvmTest's full runtime classpath.
    launchClasspath.from(configurations.named("pitest"))
    mutableCodePaths.from(jvmMainCompilation.output.classesDirs)
    additionalClasspath.from(
        jvmMainCompilation.output.classesDirs,
        jvmTestCompilation.output.classesDirs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
    useAdditionalClasspathFile.set(true)
    // PIT writes this file itself and does not create its parent, so it stays at the build-directory root.
    additionalClasspathFile.set(layout.buildDirectory.file("pitClasspath"))
    defaultFileForHistoryData.set(layout.buildDirectory.file("pitHistory.txt"))
    reportDir.set(layout.buildDirectory.dir("reports/pitest"))

    // The live tier is gated on this property; PIT's minions must see it off, never inherited from a shell.
    childProcessJvmArgs.add("-Dtypesafe.live=false")
}
