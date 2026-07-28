import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.maven.publish)
}

// The Compose surfaces that belong to the engine rather than to either player.
//
// The debug overlay is the first of them and the reason this module exists: the
// inspector it renders is core's, so an overlay living in the video chrome
// would be a video feature the music player has to copy. Copying it is how the
// two chromes end up with different debug tools and one of them stops being
// maintained.
//
// Android and JVM only, like the other two Compose modules. Apple gets SwiftUI:
// a Compose surface on iOS fights the native app it would be embedded in.
kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()

    androidLibrary {
        namespace = "tv.nomercy.player.ui"
        compileSdk = 36
        minSdk = 29

        withHostTestBuilder {
        }.configure {
            isIncludeAndroidResources = true
        }

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

    sourceSets {
        commonMain.dependencies {
            api(project(":"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.compose.ui.test)
            implementation(project(":testing"))
        }
        jvmTest.dependencies {
            // Skiko's native runtime, without which the desktop Compose test
            // host dies in its static initializer. currentOs picks the right
            // platform artifact, which is what lets this run on a Windows dev
            // box and a Linux runner from the same line.
            implementation(compose.desktop.currentOs)
        }
    }
}

// Compose Desktop's tests render through Skiko, which reaches for a GPU and a
// display. A CI runner has neither, and the failure is not an error — it is a
// test task that sits there until the job's timeout.
tasks.withType<Test>().configureEach {
    systemProperty("skiko.renderApi", "SOFTWARE")
    systemProperty("java.awt.headless", "true")

    timeout.set(Duration.ofMinutes(8))
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

// Its own coordinate, so an application drawing its own chrome can take the
// engine and leave this.
mavenPublishing {
    publishToMavenCentral()

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}
