import java.io.File as JavaFile
import java.security.MessageDigest
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkConfig

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.skie)
    alias(libs.plugins.detekt)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.maven.publish)
}

val libraryVersion: String = providers.gradleProperty("VERSION_NAME").get()
val webContractVersion: String = providers.gradleProperty("CONTRACT_VERSION").get()

// Every native payload, read once from natives/payloads.json.
//
// One source for three consumers: the generated Kotlin catalogue the runtime
// installs from, the task that bundles payloads into the published jar, and
// whatever a release job needs to know. The libass entry was missing from the
// runtime table for every platform while the archives had been built and
// published for months, and nothing failed — styled subtitles simply drew
// nothing. Two hand-kept copies would have made that twice as likely, not less.
data class NativePayload(
    val kind: String,
    val platform: String,
    val version: String,
    val sha256: String,
    val marker: String,
    val bundle: Boolean,
) {
    val slug: String get() = if (kind == "LIB_ASS") "libass" else "libvlc"
    val platformId: String get() = if (platform == "WINDOWS_X64") "windows-x64" else "linux-x64"
    val fileName: String get() = "$slug-$version-$platformId.tar.gz"
    val repository: String get() = if (kind == "LIB_ASS") "nomercy-libass" else "nomercy-player-core-kmp"
    val tag: String get() = if (kind == "LIB_ASS") "v$version" else "natives-$slug-$version"
}

val nativePayloads: List<NativePayload> = run {
    val source: JavaFile = layout.projectDirectory.file("natives/payloads.json").asFile
    val text: String = source.readText()
    val entries: groovy.json.JsonSlurper = groovy.json.JsonSlurper()

    @Suppress("UNCHECKED_CAST")
    val parsed: Map<String, Any> = entries.parseText(text) as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    (parsed["payloads"] as List<Map<String, Any>>).map { row ->
        NativePayload(
            kind = row["kind"] as String,
            platform = row["platform"] as String,
            version = row["version"] as String,
            sha256 = row["sha256"] as String,
            marker = row["marker"] as String,
            bundle = row["bundle"] as Boolean,
        )
    }
}

val generateNativeCatalogue: TaskProvider<Task> = tasks.register("generateNativeCatalogue") {
    val outputDirectory: Provider<Directory> = layout.buildDirectory.dir("generated/natives/kotlin")
    val payloads: List<NativePayload> = nativePayloads
    val bundleDirectory: Provider<Directory> = layout.buildDirectory.dir("generated/natives/resources")

    dependsOn("bundleNativePayloads")
    inputs.file(layout.projectDirectory.file("natives/payloads.json"))
    inputs.dir(bundleDirectory)
    outputs.dir(outputDirectory)

    doLast {
        val packageDirectory: JavaFile =
            outputDirectory.get().asFile.resolve("tv/nomercy/player/core/natives")
        packageDirectory.mkdirs()

        val digestOf: (JavaFile) -> String = { file ->
            MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { byte: Byte -> "%02x".format(byte) }
        }

        val rows: String = payloads.joinToString("\n") { payload ->
            // A payload BUILT here carries the digest of the archive that was
            // actually produced, because repacking a verified upstream through
            // a different tar and gzip changes the bytes without changing what
            // is inside. Pinning a literal would make the install-time check
            // reject the library's own payload on whichever machine did not
            // build it. The supply-chain anchor is the upstream digest in
            // tools/native-payloads.lock, checked before anything is unpacked.
            val built = JavaFile(bundleDirectory.get().asFile, "tv/nomercy/player/natives/${payload.fileName}")
            val sha: String = if (payload.bundle && built.isFile) digestOf(built) else payload.sha256

            """
            |        NativeArchive(
            |            kind = NativeRuntimeKind.${payload.kind},
            |            platform = HostPlatform.${payload.platform},
            |            version = "${payload.version}",
            |            sha256 = "$sha",
            |            marker = "${payload.marker}",
            |        ),
            """.trimMargin()
        }

        packageDirectory.resolve("NativeCatalogue.kt").writeText(
            """
            |// -----------------------------------------------------------------------------
            |//  Copyright (c) NoMercy Entertainment
            |//
            |//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
            |//
            |//  SPDX-License-Identifier: Apache-2.0
            |// -----------------------------------------------------------------------------
            |
            |package tv.nomercy.player.core.natives
            |
            |// Generated by the `generateNativeCatalogue` Gradle task from
            |// natives/payloads.json. Do not edit: every write is overwritten next build.
            |internal object NativeCatalogue {
            |    val PUBLISHED: List<NativeArchive> = listOf(
            |$rows
            |    )
            |}
            |
            """.trimMargin(),
        )
    }
}

// The payloads that ride inside the published jar.
//
// Bundled rather than fetched at run time, because neither of these could be
// fetched at all. libass comes from a private repository, so a download 404s
// for every consumer. libVLC had no release whatsoever — the URL its digest
// named has never existed — and a stock Windows box has no system VLC either,
// so the desktop engine loaded nothing and drew a black rectangle with not one
// line logged.
//
// Two sources, because the two payloads are made differently. libass is a
// release asset of the repo that builds it. libVLC is BUILT here from the
// upstream VideoLAN redistributable, which is published for every platform and
// needs no release of ours — that is what makes it a build-time dependency
// rather than something we host.
//
// A bundled payload is verified against the same digest on the way out of the
// jar as a downloaded one is off the network, so this changes where the bytes
// come from and nothing about whether they are trusted.
val bundleNativePayloads: TaskProvider<Task> = tasks.register("bundleNativePayloads") {
    val outputDirectory: Provider<Directory> = layout.buildDirectory.dir("generated/natives/resources")
    val bundled: List<NativePayload> = nativePayloads.filter { payload -> payload.bundle }
    val projectRoot: JavaFile = layout.projectDirectory.asFile
    val hostPlatform: String = run {
        val os: String = System.getProperty("os.name").lowercase()
        when {
            os.startsWith("windows") -> "WINDOWS_X64"
            os.contains("linux") -> "LINUX_X64"
            else -> "NONE"
        }
    }

    inputs.file(layout.projectDirectory.file("natives/payloads.json"))
    inputs.file(layout.projectDirectory.file("tools/native-payloads.lock"))
    outputs.dir(outputDirectory)

    // Self-contained on purpose: the configuration cache cannot serialise a
    // reference to a helper declared in the script, so everything this needs is
    // either captured as a value above or declared inside the action.
    doLast {
        val digestOf: (JavaFile) -> String = { file ->
            MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { byte: Byte -> "%02x".format(byte) }
        }

        val target: JavaFile = outputDirectory.get().asFile.resolve("tv/nomercy/player/natives")
        target.mkdirs()

        for (payload in bundled) {
            val destination = JavaFile(target, payload.fileName)
            if (destination.isFile && digestOf(destination) == payload.sha256) continue

            // libVLC is built from upstream; libass is fetched from its release.
            //
            // The build regenerates plugins.dat with vlc-cache-gen, which is a
            // native binary for the platform being packaged, so a payload can
            // only be produced ON its own target. Each platform's runner
            // produces its own and the host's is always available locally —
            // which is what matters here, because the machine running this is
            // the machine whose desktop has to play.
            val command: List<String> = if (payload.kind == "LIB_VLC") {
                if (payload.platform != hostPlatform) {
                    logger.lifecycle(
                        "bundleNativePayloads: skipping ${payload.fileName} — " +
                            "vlc-cache-gen is native to ${payload.platformId} and this host is not it. " +
                            "Its own runner produces it.",
                    )
                    continue
                }
                listOf("bash", "tools/build-native-payload.sh", "libvlc", payload.platformId, "build/payloads")
            } else {
                listOf(
                    "gh", "release", "download", payload.tag,
                    "--repo", "NoMercy-Entertainment/${payload.repository}",
                    "--pattern", payload.fileName,
                    "--output", destination.absolutePath,
                    "--clobber",
                )
            }

            val process: Process = ProcessBuilder(command)
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()

            val output: String = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "could not obtain ${payload.fileName}: $output" }

            // The build script writes where it was told; move it into the
            // resource tree the loader reads from.
            if (payload.kind == "LIB_VLC") {
                JavaFile(projectRoot, "build/payloads/${payload.fileName}").copyTo(destination, overwrite = true)
            }

            // A FETCHED payload must match the digest we pinned — that is the
            // supply-chain check and it is not negotiable.
            //
            // A BUILT one cannot be pinned that way and pretending otherwise is
            // a false contract: repacking the same verified upstream through a
            // different tar and gzip produces different bytes, so the archive
            // digest depends on which shell ran the script. Measured here —
            // git-bash gives c159cf42, the shell Gradle spawns gives 7421e397,
            // from identical inputs. Its trust anchor is the UPSTREAM digest,
            // which tools/native-payloads.lock pins and the script verifies
            // before it unpacks anything.
            if (payload.kind != "LIB_VLC") {
                val actual: String = digestOf(destination)
                check(actual == payload.sha256) {
                    "digest mismatch for ${payload.fileName}: expected ${payload.sha256} but got $actual"
                }
            }
        }
    }
}

// Both versions reach Kotlin through generation, never through a hand-typed
// literal. A literal and its assertion drift together silently — the exact
// defect that let the web contract generator claim 2.0.0 after 2.0.1 shipped,
// with a green test agreeing with it.
val generateKitVersion: TaskProvider<Task> = tasks.register("generateKitVersion") {
    val outputDirectory: Provider<Directory> = layout.buildDirectory.dir("generated/kitVersion/kotlin")
    val version: String = libraryVersion
    val contractVersion: String = webContractVersion

    inputs.property("version", version)
    inputs.property("contractVersion", contractVersion)
    outputs.dir(outputDirectory)

    doLast {
        val packageDirectory: java.io.File = outputDirectory.get().asFile.resolve("tv/nomercy/player/core")
        packageDirectory.mkdirs()
        packageDirectory.resolve("KitVersion.kt").writeText(
            """
            // -----------------------------------------------------------------------------
            //  Copyright (c) NoMercy Entertainment
            //
            //  Licensed under the Apache License, Version 2.0. See LICENSE for details.
            //
            //  SPDX-License-Identifier: Apache-2.0
            // -----------------------------------------------------------------------------

            package tv.nomercy.player.core

            /**
             * This library's own published version. Plugins gate on it through
             * `minCoreVersion`, exactly as `KIT_VERSION` does on the web trio.
             *
             * Generated by the `generateKitVersion` Gradle task from VERSION_NAME in
             * gradle.properties. Do not edit: every write is overwritten next build.
             */
            public const val KIT_VERSION: String = "$version"

            /**
             * The published web trio release whose contract this library implements.
             * Conformance checks read it to pick the contract they measure against.
             *
             * Generated from CONTRACT_VERSION in gradle.properties.
             */
            public const val CONTRACT_VERSION: String = "$contractVersion"

            """.trimIndent(),
        )
    }
}

kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()

    androidLibrary {
        namespace = "tv.nomercy.player.core"
        compileSdk = 36
        minSdk = 29

        // Android host tests exist from day one so the first `actual` written for
        // Android has somewhere to be tested. Device tests come with the first
        // thing that genuinely needs a device.
        withHostTestBuilder {}.configure {}

        // The engine gate runs on a device, because an ExoPlayer that
        // decodes on a JVM stub proves nothing about the one shipping.
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
            }
        }
    }

    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
            }
        }
    }

    // One xcframework for both Apple platforms, matching how nomercy-app-kmp's
    // `shared` module already ships to the Tuist project.
    val playerCoreXcf: XCFrameworkConfig = XCFramework("NoMercyPlayerCore")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
        tvosArm64(),
        tvosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "NoMercyPlayerCore"
            isStatic = true
            // Without this the linker warns and falls back to the bundle name,
            // which collides with any other framework that also went unnamed.
            binaryOption("bundleId", "tv.nomercy.player.core")
            playerCoreXcf.add(this)
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateKitVersion)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                // A backend's callbacks arrive on whatever thread the engine
                // uses — VLCJ's native thread, AVPlayer's main queue — and
                // kotlin.synchronized does not exist in commonMain.
                implementation(libs.kotlinx.atomicfu)
            }
        }
        // androidx.startup captures the application Context at process start so
        // it never has to appear in a common signature.
        androidMain.dependencies {
            implementation(libs.androidx.startup)
            // The Android engine. Media3 is what every Android client
            // already uses, and reimplementing its buffering would be
            // worse than anything gained.
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.common)
            // The system transport. One Media3 session owns the lock screen,
            // the notification and whatever a car is showing, and it is built
            // here rather than in an app so there is exactly one of it.
            implementation(libs.androidx.media3.session)
            // The data source Media3 reads manifests through, so the sanitizer
            // can sit in front of it. Media3's default source has no place to
            // put an interceptor.
            implementation(libs.androidx.media3.datasource.okhttp)
            implementation(libs.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }
        // The desktop engine. libVLC decodes practically everything, which is
        // what a desktop client needs when the file came off a disc rip, and it
        // is bound through its own C API rather than through a wrapper — see
        // tv.nomercy.player.core.natives.libvlc. JNA is what calls it.
        jvmMain {
            kotlin.srcDir(generateNativeCatalogue)
            resources.srcDir(bundleNativePayloads)
            dependencies {
                implementation(libs.jna)
            }
        }
        getByName("androidHostTest").dependencies {
            // A real HTTP exchange for the interceptor, because the plumbing it
            // adds over the rule is exactly what a fake transport cannot check.
            implementation(libs.okhttp.mockwebserver)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
        }
        commonTest.dependencies {
            // The shipped fakes, used by the library's own suite.
            //
            // The point of publishing them is that a consumer and this library
            // test against the same stand-in. Keeping a private copy here would
            // make that two stand-ins immediately, and the published one would
            // be the untested of the pair.
            implementation(project(":testing"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Test-only, deliberately. The method-surface gate asks the player
            // class what it exposes, and a reflection library in the shipped
            // artifact would be a megabyte every consumer carries so that a
            // build-time question could be answered.
            implementation(kotlin("reflect"))
        }
    }
}

skie {
    // SKIE uploads build analytics to Touchlab by default. Off: nothing about
    // this build leaves the machine that ran it.
    analytics {
        enabled.set(false)
    }
}

// The advisory pack runs against this library too. It is guidance for a
// consumer and a hard failure for us: we are the ones writing the examples
// everybody else reads.
dependencies {
    detektPlugins(project(":detekt-player"))
}

detekt {
    // Hand-written sources only. Generated code is not something a human can fix.
    source.setFrom(
        "src/commonMain/kotlin",
        "src/commonTest/kotlin",
        "src/androidMain/kotlin",
        "src/appleMain/kotlin",
        "src/iosMain/kotlin",
        "src/tvosMain/kotlin",
        "src/jvmTest/kotlin",
        "src/jvmMain/kotlin",
        // The testing kit is a separate Gradle module and the same codebase.
        // It is also the module consumers read to learn what good use of this
        // library looks like, so holding it to a lower standard than the
        // library would be exactly backwards.
        "testing/src/commonMain/kotlin",
        "testing/src/commonTest/kotlin",
        "ui-compose/src/commonMain/kotlin",
        "ui-compose/src/jvmTest/kotlin",
    )
    config.setFrom("config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    // No baseline file, deliberately. A baseline is a list of violations that
    // are allowed to stay, and this repo has no history to grandfather in.
}

// The GO/NO-GO gate, on its own so it can be read as a verdict.
//
// These tests already run inside jvmTest, so a red gate blocks `build` whether
// or not this task exists. What it adds is time and a name: it answers "has the
// native port drifted from the ecosystem contract" in seconds, before the
// multiplatform build spends ten minutes arriving at the same answer under a
// heading that says nothing.
// Named for what it now is. It began as a skeleton over five events and covers
// the whole surface plus the behavioural runners, and a task called "skeleton"
// is one people assume is the cheap subset of something more thorough.
tasks.register<Test>("parityConformance") {
    group = "verification"
    description = "Checks the port against the vendored contract: events, error codes and behaviour."

    val jvmTest: Test = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath

    filter {
        includeTestsMatching("tv.nomercy.player.core.conformance.*")
        includeTestsMatching("tv.nomercy.player.conformance.*")
        includeTestsMatching("tv.nomercy.player.core.events.CoreEventsRegistryTest")
    }

    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

@OptIn(kotlinx.validation.ExperimentalBCVApi::class)
apiValidation {
    // The rule pack is a build-tool jar detekt loads through a service file, not
    // something application code links against. Its real contract is the ruleset
    // id and the six rule ids, and PlayerRulesTest asserts those exactly. A dump
    // of its class signatures would churn on every rule without guarding anyone.
    ignoredProjects.add("detekt-player")

    // Nor is the template. It is source a consumer copies and edits, not an
    // artifact anyone resolves, so it has no public surface to keep stable —
    // a dump of it would be a file that has to be regenerated every time the
    // example changes, guarding nobody.
    ignoredProjects.add("plugin")

    // On covers Apple. Kotlin/Native compiles klibs on any host — only linking a
    // framework needs macOS — so the iOS and tvOS public surface is checked on
    // Windows and Linux too, not just on the Mac runner.
    klib {
        enabled = true
        strictValidation = true
    }
}

mavenPublishing {
    publishToMavenCentral()

    // Signed where there is a key, which is CI and nowhere else.
    //
    // Unconditional signAllPublications() takes publishToMavenLocal down with
    // it — "no configured signatory" — and that is the one command that proves
    // the module metadata carries every target before anything reaches Central.
    // The signature is still mandatory where it matters: the workflow sets
    // signingInMemoryKey from the repository secret, so a release without one
    // is a release that never ran through CI.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}

// The conformance fixtures are inputs to the tests that read them.
//
// They are read off disk by path rather than loaded as test resources, so
// without this Gradle sees no change when one is edited and reports the whole
// suite UP-TO-DATE. A vendored scenario nudged until a port passes it would
// then never even be measured — the gate does not fail, it does not run, and
// the build is green either way.
//
// Found by editing one on purpose and watching nothing happen.
tasks.withType<Test>().configureEach {
    // Where a native engine lives on the machine running the tests, and which
    // fixtures the engine gates should open.
    //
    // Forwarded rather than read from the environment inside the test: a Gradle
    // test JVM does not inherit the shell's -D flags, so a property set on the
    // command line reached nothing and the gates skipped themselves while
    // reporting green.
    for (name in listOf("jna.library.path", "nomercy.mpv.fixture", "nomercy.mpv.master", "nomercy.mpv.fixtures", "nomercy.mpv.openMs")) {
        providers.gradleProperty(name).orNull?.let { value -> systemProperty(name, value) }
        System.getProperty(name)?.let { value -> systemProperty(name, value) }
    }

    inputs.files(
        fileTree("contract") { include("*.json") },
        fileTree("scenarios") { include("*.json") },
    )
        .withPropertyName("conformanceFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
