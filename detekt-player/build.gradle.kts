plugins {
    // No version: the root project already puts the Kotlin plugin on the
    // build classpath, and asking for it again with a version is an error.
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // detekt supplies the api at runtime; a ruleset that bundled it would load
    // two copies and fail to register.
    compileOnly(libs.detekt.api)

    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
