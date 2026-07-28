import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

// The stand-ins, shipped.
//
// Every consumer that tests against this player needs a backend that does not
// decode anything, and every one of them was writing their own. Three fakes for
// one interface is three answers to "what does this engine do when a seek lands
// past the end", and the library's own suite is the only one whose answer is
// checked.
//
// Its own artifact rather than a package inside the engine: a consumer shipping
// a player should not carry the fakes into production, and the web trio draws
// the same line with its ./testing export.
kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()

    androidLibrary {
        namespace = "tv.nomercy.player.testing"
        compileSdk = 36
        minSdk = 29

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

    // The same Apple targets the engine has. A fake that existed everywhere
    // except the platform whose conformance is hardest to run would leave that
    // platform writing its own again.
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    tvosArm64()
    tvosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}
