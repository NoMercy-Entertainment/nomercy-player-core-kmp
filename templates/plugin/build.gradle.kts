import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.detekt)
}

// The plugin a consumer copies.
//
// A real module rather than a folder of snippets, because a scaffold that does
// not compile is a scaffold whose first act is to waste somebody's afternoon.
// It builds, it is tested with the shipped harness, and the advisory rule pack
// runs over it — so the template is proof the blessed path is followed rather
// than a description of it.
//
// Deliberately not published. It is source to copy, not a dependency to add:
// a plugin that arrived as an artifact would be one a consumer cannot edit,
// which is the opposite of what a template is for.
kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()

    androidLibrary {
        namespace = "tv.nomercy.player.template"
        compileSdk = 36
        minSdk = 29

        withHostTestBuilder {}.configure {}

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
            }
        }
    }

    jvm {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
            }
        }
    }

    // The same Apple targets the engine has. A plugin that compiled everywhere
    // except the two platforms with the least tooling would be a template that
    // teaches the wrong lesson about where to check.
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    tvosArm64()
    tvosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // A consumer writes `implementation("tv.nomercy:nomercy-player-core-kmp:<version>")`
            // here. Inside this repository the project path is the same library
            // before it is published.
            api(project(":"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // The shipped fakes and harness:
            // testImplementation("tv.nomercy:nomercy-player-core-kmp-testing:<version>")
            implementation(project(":testing"))
        }
    }
}

dependencies {
    detektPlugins(project(":detekt-player"))
}

// The advisory pack, on the template.
//
// This is the part a consumer copies with the rest: the rules say at the call
// site what the blessed path is, and a template that shipped without them would
// be teaching the shape while leaving out the thing that keeps it.
detekt {
    source.setFrom("src/commonMain/kotlin", "src/commonTest/kotlin")
    config.setFrom("detekt.yml")
    buildUponDefaultConfig = true
}
