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
import tv.nomercy.player.core.ports.VlcjVideoBackend

private const val PLAY_TIMEOUT_MS = 30_000L
private const val POLL_MS = 250L

// A consumer, and nothing but a consumer.
//
// It resolves the published coordinate, constructs the desktop engine and plays
// a file. It never names libVLC, never looks for an install, and never asks the
// machine for anything — because that is exactly what somebody adding this
// library to their own project does, and it is the only thing that can prove
// the library works out of the box.
fun main(args: Array<String>) {
    val media: File = File(args.firstOrNull() ?: "media.mkv").absoluteFile
    report("host platform", HostPlatform.current()?.id ?: "unsupported")
    report("payload", NativeRuntimes.directory(NativeRuntimeKind.LIB_VLC)?.path ?: "none")
    report("payload reason", NativeRuntimes.whyUnavailable(NativeRuntimeKind.LIB_VLC) ?: "-")

    val bound: Long = System.nanoTime()
    val unavailable: String? = VlcjVideoBackend.whyUnavailable()
    report("libvlc bind ms", ((System.nanoTime() - bound) / 1_000_000).toString())
    if (unavailable != null) fail("libVLC did not bind: $unavailable")
    if (!media.isFile) fail("no media at ${media.path}")

    exitProcess(if (played(media)) 0 else 1)
}

private fun played(media: File): Boolean = runBlocking {
    val backend = VlcjVideoBackend()
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
