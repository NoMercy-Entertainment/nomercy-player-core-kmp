// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

// A standalone consumer build — its own Gradle root, not a sub-project of the
// library. That is what makes "only the shipped surface is reachable" true by
// construction rather than by discipline: this build cannot `import` an
// internal class the library forgot to make public, because it never sees the
// library's source tree as its own.
//
// `includeBuild("../..")` is the local-dev substitution (the Kotlin/Gradle
// analog of `npm link`) — Gradle resolves `tv.nomercy:nomercy-player-core-kmp`
// and `tv.nomercy:nomercy-player-core-kmp-testing` to this checkout's build
// instead of Maven Central. A real consumer outside this monorepo drops that
// block and gets the published coordinates from `mavenCentral()` unchanged.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "consumer-plugin"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("tv.nomercy:nomercy-player-core-kmp")).using(project(":"))
        substitute(module("tv.nomercy:nomercy-player-core-kmp-testing")).using(project(":testing"))
    }
}
