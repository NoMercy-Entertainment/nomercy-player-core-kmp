// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tv.nomercy.player.core.natives.libmpv.LibMpv
import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider

/**
 * The mpv backend against a real libmpv, headless.
 *
 * Skipped loudly when there is no libmpv on the path — point it at one with
 * `-Djna.library.path=<dir containing libmpv-2.dll>`. A gate that passes
 * silently on a machine without the engine is a gate that says the engine works
 * everywhere it was never run.
 *
 * The fixture is a local file rather than a stream. What is under test here is
 * the CONTRACT — does the playhead move, does duration arrive, do the canonical
 * events come out in the media element's order — and a network fixture puts a
 * connection between the assertion and the thing asserted.
 */
class MpvVideoBackendGateTest {

    private var backend: MpvVideoBackend? = null

    private fun engineOrSkip(): MpvVideoBackend? {
        val reason: String? = MpvVideoEngineProvider.whyUnavailable()
        if (reason != null) {
            println("SKIPPED: ${LibMpv.SONAME} unavailable — $reason")
            return null
        }
        return MpvVideoBackend().also { backend = it }
    }

    @AfterTest
    fun releaseTheEngine() {
        backend?.release()
        backend = null
    }

    @Test
    fun anEngineStartsHeadlessAndAnswersItsOwnProperties() {
        val engine: MpvVideoBackend = engineOrSkip() ?: return

        // Nothing loaded: the honest answers are zero and idle, not a guess.
        assertEquals(0.0, engine.duration())
        assertEquals(1.0, engine.playbackRate())
        assertTrue(engine.state() == BackendState.IDLE || engine.state() == BackendState.LOADING)
    }

    @Test
    fun volumeIsTheContractsScaleNotMpvsOwn() {
        // mpv counts 0..100 and this contract counts 0..1. A backend that
        // forwards its engine's scale reports 100 for "half volume" and every
        // slider above it is wrong by two orders of magnitude.
        val engine: MpvVideoBackend = engineOrSkip() ?: return

        engine.volume(0.5f)

        assertEquals(0.5f, engine.volume(), 0.01f)
    }

    @Test
    fun aFileLoadsPlaysAndReportsTheCanonicalEvents() {
        val engine: MpvVideoBackend = engineOrSkip() ?: return
        val fixture: String = System.getProperty("nomercy.mpv.fixture") ?: run {
            println("SKIPPED: no -Dnomercy.mpv.fixture=<file or url>")
            return
        }

        val seen: MutableList<String> = mutableListOf()
        for (event in listOf(
            BackendEvents.LOAD_START,
            BackendEvents.LOADED_METADATA,
            BackendEvents.CAN_PLAY,
            BackendEvents.PLAY,
            BackendEvents.PLAYING,
            BackendEvents.TIME_UPDATE,
        )) {
            engine.on(event) { synchronized(seen) { if (!seen.contains(event)) seen += event } }
        }

        runBlocking {
            engine.load(fixture, LoadOptions(autoplay = true))
            // Long enough for the demuxer to open and the poll to run several
            // times. A shorter wait reports "no events" on a slow first decode,
            // which reads exactly like a backend that emits nothing.
            delay(SETTLE_MS)
        }

        assertTrue(engine.duration() > 0.0, "no duration: mpv never opened $fixture")
        assertTrue(engine.currentTime() > 0.0, "the playhead never moved")
        assertTrue(
            seen.containsAll(
                listOf(
                    BackendEvents.LOAD_START,
                    BackendEvents.LOADED_METADATA,
                    BackendEvents.CAN_PLAY,
                    BackendEvents.TIME_UPDATE,
                ),
            ),
            "the canonical spine is incomplete: $seen",
        )
    }

    @Test
    fun everyRenditionOfAMasterPlaylistIsSelectable() {
        // The whole reason for the engine swap. libVLC 3 has no call that pins a
        // rendition, so quality(level) returned without doing anything and the
        // menu built from the manifest could never select against it.
        val engine: MpvVideoBackend = engineOrSkip() ?: return
        val master: String = System.getProperty("nomercy.mpv.master") ?: run {
            println("SKIPPED: no -Dnomercy.mpv.master=<hls master url>")
            return
        }

        // Played, not merely loaded. A paused mpv opens the stream lazily, so a
        // rendition list read from a paused engine is read before the demuxer
        // has one — measured: path set, file-format null, track-list empty.
        runBlocking {
            engine.load(master, LoadOptions(autoplay = true))
            delay(SETTLE_MS * 2)
        }

        val levels: List<QualityLevel> = engine.qualityLevels()
        assertTrue(
            levels.size > 1,
            "mpv reported ${levels.size} renditions for a master playlist " +
                "(duration ${engine.duration()}, ${engine.describeRenditions()})",
        )

        // Printed, because "every rung was selectable" over an empty list is the
        // commonest unearned green and the count alone does not say which rungs.
        println("renditions proven selectable: ${levels.map { it.label ?: "${it.width}x${it.height}" }}")

        for (level in levels) {
            engine.quality(level)
            assertEquals(level, engine.quality(), "the engine did not take the rendition it was given")
        }
    }

    private companion object {
        const val SETTLE_MS: Long = 6_000L
    }
}
