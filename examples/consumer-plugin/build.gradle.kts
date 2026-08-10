// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

plugins {
    kotlin("jvm") version "2.2.21"
    application
}

application {
    mainClass.set("tv.nomercy.player.example.watchcount.InspectorDemoKt")
}

// The same coordinates `tools/consumer-check` proves resolve out of the box —
// the plain KMP root coordinate, variant-aware Gradle Module Metadata resolves
// it to the jvm target for a Kotlin/JVM (not Multiplatform) consumer.
// `settings.gradle.kts`'s `includeBuild` substitutes these with the local
// checkout for co-dev; a real consumer keeps this block unchanged and gets the
// published jars from Maven Central.
dependencies {
    implementation("tv.nomercy:nomercy-player-core-kmp:0.1.0")
    // `implementation`, not `testImplementation`: `InspectorDemo` (src/main)
    // runs the shipped fakes outside a test, the same way a consumer's own
    // debug tooling would.
    implementation("tv.nomercy:nomercy-player-core-kmp-testing:0.1.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
