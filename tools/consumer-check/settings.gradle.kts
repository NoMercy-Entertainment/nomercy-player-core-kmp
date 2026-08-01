rootProject.name = "nomercy-player-consumer-check"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// mavenLocal first, because before a release that is where the artifact is.
// After one, Central serves the same coordinates and this still works.
//
// There is deliberately no includeBuild and no project(":") here. This build
// exists to answer one question — does somebody who adds the coordinate and
// nothing else get working playback — and a build wired to the source tree
// cannot answer it.
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}
