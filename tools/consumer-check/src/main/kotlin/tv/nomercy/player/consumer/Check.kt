// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.consumer

import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tv.nomercy.player.core.natives.HostPlatform
import tv.nomercy.player.core.natives.NativeRuntimeKind
import tv.nomercy.player.core.natives.NativeRuntimes
import tv.nomercy.player.core.ports.MpvVideoBackend
import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider

private const val PLAY_TIMEOUT_MS = 30_000L
private const val POLL_MS = 250L

private const val BIND_ONLY = "--bind-only"

fun main(args: Array<String>) {
    val media: File = File(args.firstOrNull { it != BIND_ONLY } ?: "media.mkv").absoluteFile
    report("host platform", HostPlatform.current()?.id ?: "unsupported")
    report("payload", NativeRuntimes.directory(NativeRuntimeKind.LIB_MPV)?.path ?: "none")
    report("payload reason", NativeRuntimes.whyUnavailable(NativeRuntimeKind.LIB_MPV) ?: "-")

    val bound: Long = System.nanoTime()
    val unavailable: String? = MpvVideoEngineProvider.whyUnavailable()
    report("libmpv bind ms", ((System.nanoTime() - bound) / 1_000_000).toString())
    if (unavailable != null) fail("libmpv did not bind: $unavailable")

    if (args.contains(BIND_ONLY)) exitProcess(if (constructed()) 0 else 1)

    if (!media.isFile) fail("no media at ${media.path}")

    exitProcess(if (played(media)) 0 else 1)
}

private fun constructed(): Boolean {
    val backend = MpvVideoBackend()
    report("engine state", backend.state().toString())
    report("tracks", "audio=${backend.audioTracks().size} video=${backend.qualityLevels().size}")
    backend.release()
    return true
}

private fun played(media: File): Boolean = runBlocking {
    val backend = MpvVideoBackend()
    val events: MutableList<String> = mutableListOf()
    listOf("loadstart", "loadedmetadata", "canplay", "playing", "timeupdate", "error")
        .forEach { name -> backend.on(name) { events += name } }

    backend.load(media.toURI().toString())
    backend.play()

    val deadline: Long = System.currentTimeMillis() + PLAY_TIMEOUT_MS
    var advanced = false
    while (System.currentTimeMillis() < deadline && !advanced) {
        delay(POLL_MS)
        advanced = backend.currentTime() > 0.0
    }

    report("events", events.distinct().joinToString(","))
    report("duration", backend.duration().toString())
    report("currentTime", backend.currentTime().toString())
    report("tracks", "audio=${backend.audioTracks().size} video=${backend.qualityLevels().size}")
    backend.release()

    advanced
}

private fun report(label: String, value: String) = println("  $label: $value")

private fun fail(why: String): Nothing {
    println("FAIL $why")
    exitProcess(1)
}
