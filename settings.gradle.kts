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
    // PREFER, not FAIL. The Kotlin/Wasm Gradle plugin adds the Binaryen
    // distribution as a PROJECT repository at task-config time (to run
    // wasm-opt on the wasmJs production build), which FAIL_ON_PROJECT_REPOS
    // rejects outright as "added by unknown code" — confirmed live in CI the
    // day this repo's wasmJs target landed. PREFER_SETTINGS still means only
    // repositories explicitly declared here are ever consulted; it just stops
    // rejecting a project-added repo that duplicates one already declared
    // below, which is exactly this plugin's own case. Same fix
    // nomercy-app-kmp's settings.gradle.kts already carries for the same
    // plugin behaviour (Node/Yarn/Binaryen), applied here for the same reason.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()

        // Node, downloaded by the Kotlin/Wasm plugin to run wasmJsBrowserTest
        // (Karma needs a Node toolchain). Same class of project-repo addition
        // as Binaryen below.
        ivy("https://nodejs.org/dist") {
            name = "Node Distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }

        // Yarn, downloaded by the same plugin and for the same reason.
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn Distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }

        // Binaryen, downloaded by the Kotlin/Wasm plugin to run wasm-opt on a
        // wasmJs production build. See the repositoriesMode comment above.
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen Distributions"
            patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
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

// The Compose surfaces that belong to the engine rather than to either player.
// Its own module because the Compose toolchain has no business in the engine's
// own build, and its own artifact because an application drawing its own chrome
// should be able to take the engine and leave this.
include(":ui-compose")

// The plugin a consumer copies. In the build because a scaffold that does not
// compile is a scaffold whose first act is to waste somebody's afternoon.
// Deliberately not published: it is source to copy, not a dependency to add.
include(":templates:plugin")
