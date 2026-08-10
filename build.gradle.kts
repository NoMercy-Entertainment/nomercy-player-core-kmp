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
    jacoco
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
    val slug: String get() = if (kind == "LIB_ASS") "libass" else "libmpv"
    val platformId: String get() = when (platform) {
        "WINDOWS_X64" -> "windows-x64"
        "MACOS_ARM64" -> "macos-arm64"
        "ANDROID_ARM64" -> "android-arm64"
        "ANDROID_ARM" -> "android-arm"
        else -> "linux-x64"
    }

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
    // Which bash, said explicitly, because on Windows `bash` is not the one we
    // mean. System32/bash.exe is the WSL launcher, it comes first on PATH, and
    // on a runner with no distribution installed it answers "Windows Subsystem
    // for Linux has no installed distributions" — which arrives as a payload
    // that could not be obtained and reads like a missing release.
    //
    // Git ships one and every Windows host that can clone this repo has it.
    val bashExecutable: String = run {
        val candidates: List<String> = listOfNotNull(
            System.getenv("NOMERCY_BASH"),
            "C:/Program Files/Git/bin/bash.exe",
            "C:/Program Files (x86)/Git/bin/bash.exe",
        )
        candidates.firstOrNull { path -> JavaFile(path).isFile } ?: "bash"
    }

    // The same problem one tool along: a launchd-started CI runner inherits no
    // login shell, so Homebrew's directories are not on its PATH and `gh` --
    // which is how a fetched payload is downloaded from a private release --
    // fails as "Cannot run program gh ... No such file or directory". The
    // workflow already calls brew by absolute path and the payload script
    // already looks for gtar this way; this is the third tool in the same
    // situation and the last one that assumed PATH.
    val ghExecutable: String = run {
        val candidates: List<String> = listOfNotNull(
            System.getenv("NOMERCY_GH"),
            "/opt/homebrew/bin/gh",
            "/usr/local/bin/gh",
            "/usr/bin/gh",
        )
        candidates.firstOrNull { path -> JavaFile(path).isFile } ?: "gh"
    }

    // Whether this machine has a Docker that answers, which is what the Linux
    // payload is actually built by.
    //
    // `docker info` rather than the binary on PATH: a macOS runner with the CLI
    // installed and no daemon behind it would pass a file check and then fail
    // the build thirty seconds later, which is the shape of failure this is here
    // to stop reporting as a broken commit.
    // Docker is asked what it serves, which is the only thing that decides this.
    //
    // Two rules were written before this one and both inferred it. The first
    // listed the host/payload pairings that had already failed, so a macOS
    // runner tried to fetch a WINDOWS mpv package. The second said "anything
    // that is not Windows can run a Linux container", and GitHub's macOS
    // runners have no Docker at all -- and a Windows desktop with Docker in
    // Linux mode, measured here, runs them perfectly well. Neither the host
    // family nor the presence of the binary answers the question; `OSType` is
    // the question, spelled out.
    //
    // Through providers.exec rather than ProcessBuilder: the configuration
    // cache refuses an external process started at configuration time outright,
    // and the refusal is a build failure rather than a warning.
    val dockerServesLinux: Boolean = try {
        val probe = providers.exec {
            commandLine("docker", "info", "--format", "{{.OSType}}")
            isIgnoreExitValue = true
        }
        probe.result.get().exitValue == 0 &&
            probe.standardOutput.asText.get().trim() == "linux"
    } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") missing: RuntimeException) {
        false
    }

    val hostPlatform: String = run {
        val os: String = System.getProperty("os.name").lowercase()
        when {
            os.startsWith("windows") -> "WINDOWS_X64"
            os.contains("linux") -> "LINUX_X64"
            os.contains("mac") -> "MACOS_ARM64"
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

        // Anything no longer in payloads.json goes.
        //
        // This directory is only ever added to, so removing an engine from the
        // catalogue left its archive sitting here and it kept shipping: after
        // libVLC was deleted, libvlc-3.0.23-windows-x64.tar.gz was still in the
        // jar — sixty megabytes of an engine nothing can construct.
        val wanted: Set<String> = bundled.map { payload -> payload.fileName }.toSet()
        target.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in wanted) {
                logger.lifecycle("bundleNativePayloads: dropping ${file.name} — no longer in payloads.json")
                file.delete()
            }
        }

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
            // libmpv is built here too, from the upstream dev package: one DLL
            // out of a 7z, no plugin tree and no cache, so unlike libVLC it can
            // be produced on any host rather than only its own.
            // A macOS payload can only be assembled on macOS: otool and
            // install_name_tool are the tools that read and rewrite a Mach-O
            // dependency list, and neither exists anywhere else. Its own runner
            // produces it.
            //
            // The Linux and Android payloads are assembled in a Linux
            // container, so a Windows host cannot make them either: Docker
            // there runs Windows containers and answers "no matching manifest
            // for windows/amd64", which arrives as a payload that could not be
            // obtained. Each runner makes what it can and the host's own is
            // always among them, which is what matters — the machine running
            // this is the machine whose desktop has to play.
            // A host builds its OWN payload, and the Linux family when it can
            // run a Linux container.
            //
            // Stated as one rule rather than as a list of the cases that have
            // failed so far, because the list was the bug. It named macOS
            // payloads off macOS and Linux payloads on Windows, and every other
            // pairing fell through to "buildable" — so a macOS runner tried to
            // fetch the WINDOWS mpv development package and the job died on a
            // download for a platform it was never going to assemble.
            //
            // Each runner produces what it can and the host's own is always
            // among them, which is the property that matters: the machine
            // running this is the machine whose desktop has to play.
            val family: String = payload.platform.substringBefore('_')
            val hostFamily: String = hostPlatform.substringBefore('_')

            val canRunLinuxContainer: Boolean = dockerServesLinux

            // A host builds its OWN family and nothing else.
            //
            // ANDROID used to ride along with LINUX because both are assembled
            // in a Linux container. That is true and it is not a reason: the
            // Android payload is a cross-compile that takes tens of minutes on a
            // cold cache, and nothing in another repo's CI needs it — video's
            // Linux job wants libass, and it was failing on
            // "building nomercy/libmpv-android:arm64" instead. An artifact that
            // long-running belongs to a release, not to every consumer build
            // that happens to resolve this project.
            val unbuildableHere: String? = when {
                payload.kind != "LIB_MPV" -> null
                family != hostFamily -> "a $family payload is not assembled on a $hostFamily host"
                family == "LINUX" && !canRunLinuxContainer -> "the linux payload is assembled in a container"
                else -> null
            }

            if (unbuildableHere != null) {
                logger.lifecycle(
                    "bundleNativePayloads: skipping ${payload.fileName} — " +
                        "$unbuildableHere and this host is $hostPlatform.",
                )
                continue
            }

            val command: List<String> = if (payload.kind == "LIB_MPV") {
                listOf(bashExecutable, "tools/build-native-payload.sh", "libmpv", payload.platformId, "build/payloads")
            } else {
                listOf(
                    ghExecutable, "release", "download", payload.tag,
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
            // Both BUILT payloads land where the script was told to write and
            // are moved into the resource tree the loader reads from. Only the
            // fetched one arrives at its destination directly.
            if (payload.kind == "LIB_MPV") {
                // Checked here, with the script's output in hand.
                //
                // `copyTo` on a missing source raises "the source file doesn't
                // exist" and nothing else — and because the script exited 0 its
                // output was discarded, so a macOS run reported a missing file
                // and not one word about why it was missing.
                val built = JavaFile(projectRoot, "build/payloads/${payload.fileName}")
                check(built.isFile) {
                    "the build script reported success and produced no ${payload.fileName}:\n$output"
                }
                built.copyTo(destination, overwrite = true)
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
            if (payload.kind != "LIB_MPV") {
                val actual: String = digestOf(destination)
                check(actual == payload.sha256) {
                    "digest mismatch for ${payload.fileName}: expected ${payload.sha256} but got $actual"
                }
            }
        }
    }
}

// One bundle, split per target, because a target must not ship another's
// payload.
//
// The archives all land in one directory because one task builds them, but the
// jar and the AAR are different products: a desktop consumer downloading a
// 14 MB Android payload is waste, and an APK carrying Windows DLLs is a hundred
// and fifty megabytes of a phone's storage that can never be loaded on it. The
// resource path inside each is identical, so the loader is unchanged.
// Ant patterns rather than a predicate on the payload list: a Kotlin lambda
// declared in a build script captures the script itself, and the configuration
// cache refuses to serialise that. The name a payload is written under always
// ends in its platform id, so a pattern says the same thing.
val androidPayloadPattern = "**/*-android-*.tar.gz"

val desktopNativePayloads: TaskProvider<Sync> = tasks.register<Sync>("desktopNativePayloads") {
    from(bundleNativePayloads)
    exclude(androidPayloadPattern)
    into(layout.buildDirectory.dir("generated/natives/desktop"))
}

val androidNativePayloads: TaskProvider<Sync> = tasks.register<Sync>("androidNativePayloads") {
    from(bundleNativePayloads)
    include(androidPayloadPattern)
    into(layout.buildDirectory.dir("generated/natives/android"))
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

    // The cast-web receiver's target. No libmpv/Media3 here — a browser
    // sandbox has no native codec access, so the eventual backend is
    // MediaSource + <video>, the same decode boundary nomercy-video-player's
    // existing JS library already uses. This target carries the state
    // machine / ABR / event contract only; the browser owns decode.
    wasmJs {
        browser()
        binaries.executable()
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
        // Everything libmpv, shared by the JVM and Android targets.
        //
        // Android's only AVC decoders stop at High profile — measured off a
        // phone: `c2.mtk.avc.decoder profile/levels: [ 8/32768 (High/5.1) ]` —
        // so Hi10P, which is profile 110, cannot be decoded by anything the OS
        // provides. Every 10-bit anime file in a library needs a decoder we
        // bring ourselves, and the one we already ship on the desktop is the
        // one to bring. Duplicating the binding per target would mean two
        // bindings drifting apart against one C API.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir(generateNativeCatalogue)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        // androidx.startup captures the application Context at process start so
        // it never has to appear in a common signature.
        androidMain {
            dependsOn(jvmSharedMain)
            resources.srcDir(androidNativePayloads)
            dependencies {
                // JNA's Android build, which carries its own dispatch library
                // per ABI. The plain jar carries desktop ones and would load on
                // nothing.
                implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
            }
        }
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
        // The desktop engine. libmpv decodes practically everything, which is
        // what a desktop client needs when the file came off a disc rip, and it
        // is bound through its own C API rather than through a wrapper — see
        // tv.nomercy.player.core.natives.libmpv. JNA is what calls it.
        jvmMain {
            dependsOn(jvmSharedMain)
            resources.srcDir(desktopNativePayloads)
            dependencies {
                implementation(libs.jna)
                implementation(libs.jna.platform)
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
        "src/jvmSharedMain/kotlin",
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

// P30.9 — the coverage half of the parity CI gate.
//
// kotlinx-kover was tried first and rejected: 0.9.1 crashes at configuration
// time against this repo's AGP 9 `com.android.kotlin.multiplatform.library`
// plugin — its KMP/Android variant locator requires the classic `android`
// extension `com.android.library` registers, which the newer KMP library
// plugin does not (`KoverCriticalException: Kover requires extension with
// name 'android'`). JaCoco needs no such variant location — it instruments
// whatever classpath a JVM test task already ran on — so it is applied
// directly to `jvmTest`, the same task `parityConformance` above borrows its
// classpath from. Scoped there for the same reason: jvmTest is where the
// vendored contract/scenario suite runs, so it answers "does the behavioural
// suite actually exercise this port" — an Android/Apple report would measure
// a different, mostly-untested-by-design surface (platform actuals covered
// by their own device/instrumented suites, not by this floor).
jacoco {
    toolVersion = "0.8.13"
}

tasks.named<Test>("jvmTest") {
    // The jvm-agent hook JaCoco's own plugin config normally wires
    // automatically for a plain `java`/`kotlin-jvm` project. This project has
    // neither, so it is wired by hand onto the one JVM test task the gate
    // measures against.
    extensions.configure(JacocoTaskExtension::class) {
        isEnabled = true
    }
}

tasks.register<JacocoReport>("jacocoJvmTestReport") {
    group = "verification"
    description = "Line coverage over jvmTest — the same classpath parityConformance runs on."
    dependsOn("jvmTest")

    val jvmTestTask: Test = tasks.named<Test>("jvmTest").get()
    executionData.setFrom(jvmTestTask.extensions.getByType(JacocoTaskExtension::class).destinationFile)
    sourceDirectories.setFrom(files("src/commonMain/kotlin", "src/jvmMain/kotlin"))
    classDirectories.setFrom(
        files(layout.buildDirectory.dir("classes/kotlin/jvm/main")).asFileTree.matching {
            // Generated/testing-only surface — measuring it tells us nothing
            // about whether the port's own behaviour is covered.
            exclude("tv/nomercy/player/core/testing/**", "tv/nomercy/player/core/BuildInfo*")
        },
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.register<JacocoCoverageVerification>("jacocoJvmTestVerify") {
    group = "verification"
    description = "Fails if jvmTest line coverage over the port's own source drops under the floor."
    dependsOn("jacocoJvmTestReport")

    val reportTask: JacocoReport = tasks.named<JacocoReport>("jacocoJvmTestReport").get()
    executionData.setFrom(reportTask.executionData)
    sourceDirectories.setFrom(reportTask.sourceDirectories)
    classDirectories.setFrom(reportTask.classDirectories)

    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.60".toBigDecimal()
            }
        }
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
    // Every `nomercy.` property, by prefix rather than by name. The list this
    // replaces had to be edited every time a gate learned a new switch, and the
    // failure mode of forgetting was not an error — it was the gate skipping
    // itself and the build staying green, which is the one shape that never
    // gets investigated.
    val forwarded: Map<String, String> =
        providers.gradlePropertiesPrefixedBy("nomercy.").get() +
            System.getProperties()
                .stringPropertyNames()
                .filter { name -> name.startsWith("nomercy.") }
                .associateWith { name -> System.getProperty(name) }

    forwarded.forEach { (name, value) -> systemProperty(name, value) }

    // Not `nomercy.`-prefixed, and the one every native gate needs: it is JNA's
    // own name for where to find the library, so it cannot be renamed into the
    // scheme above.
    for (name in listOf("jna.library.path")) {
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
