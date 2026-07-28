pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nomercy-player-core-kmp"

// The advisory rule pack. Its own JVM module because a detekt ruleset is a
// plain jar detekt loads, not part of the multiplatform library it checks.
include(":detekt-player")

// The shipped fakes. Its own module because a consumer shipping a player should
// not carry a stand-in backend into production, and its own artifact because
// that is the line the web trio draws with ./testing.
include(":testing")
