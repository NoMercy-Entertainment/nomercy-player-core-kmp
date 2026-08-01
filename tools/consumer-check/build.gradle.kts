plugins {
    kotlin("jvm") version "2.2.21"
    application
}

kotlin {
    jvmToolchain(21)
}

// The whole point of this file is how short it is.
//
// One coordinate for the player and one for coroutines, because the backend's
// load is a suspend function and a consumer needs a way to call it. No native
// library, no download step, no "install VLC first" — if anything else has to
// appear here, the library has not been made to work out of the box.
dependencies {
    implementation("tv.nomercy:nomercy-player-core-kmp:${property("playerVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

application {
    mainClass.set("tv.nomercy.player.consumer.CheckKt")
}

tasks.named<JavaExec>("run") {
    // The file to play, and how long to wait for it to be playing.
    args = (findProperty("media")?.toString()?.let(::listOf) ?: emptyList())
    // Inherited so the check can be pointed at a staged payload, run offline,
    // or given a cache directory of its own.
    systemProperties(
        System.getProperties()
            .filterKeys { key -> key.toString().startsWith("nomercy.player.natives") }
            .mapKeys { entry -> entry.key.toString() },
    )
}
