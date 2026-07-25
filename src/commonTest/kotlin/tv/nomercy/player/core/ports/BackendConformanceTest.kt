// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// The ruler is measured before anything is measured with it. An engine gate
// built on a matcher nobody checked would pass whatever it was given.
class BackendConformanceTest {

    @Test
    fun theSpineIsFoundWithTheEnginesOwnNoiseInterleaved() {
        val recorded = listOf(
            "loadstart", "waiting", "loadedmetadata", "canplay",
            "play", "playing", "timeupdate", "timeupdate", "pause",
        )

        assertCanonicalSubsequence(recorded, CanonicalBackendEvent.PLAY_PAUSE_SPINE)
    }

    @Test
    fun anOutOfOrderStreamFailsAndTheMessageNamesWhereItStopped() {
        // play before loadstart: an engine describing a different lifecycle.
        val recorded = listOf("play", "loadstart", "canplay", "timeupdate", "pause")

        val failure = assertFailsWith<AssertionError> {
            assertCanonicalSubsequence(recorded, CanonicalBackendEvent.PLAY_PAUSE_SPINE)
        }

        assertTrue(failure.message.orEmpty().contains("stopped looking for"))
    }

    @Test
    fun aMissingEventFailsRatherThanBeingForgiven() {
        val recorded = listOf("loadstart", "canplay", "play", "pause")

        assertFailsWith<AssertionError> {
            assertCanonicalSubsequence(recorded, CanonicalBackendEvent.PLAY_PAUSE_SPINE)
        }
    }

    @Test
    fun theRecorderCapturesEmissionOrderOffTheBus() {
        val bus = StringEventBus()
        val recorder = BackendEventRecorder(bus)

        bus.emit(CanonicalBackendEvent.LOAD_START, "url")
        bus.emit(CanonicalBackendEvent.CAN_PLAY)
        bus.emit(CanonicalBackendEvent.PLAY)

        assertEquals(listOf("loadstart", "canplay", "play"), recorder.names())
    }

    @Test
    fun theBusDeliversPayloadsAndStopsAfterOff() {
        val bus = StringEventBus()
        val seen = mutableListOf<Any?>()
        val listener: (Any?) -> Unit = { seen.add(it) }

        bus.on(CanonicalBackendEvent.TIME_UPDATE, listener)
        bus.emit(CanonicalBackendEvent.TIME_UPDATE, 12.5)
        bus.off(CanonicalBackendEvent.TIME_UPDATE, listener)
        bus.emit(CanonicalBackendEvent.TIME_UPDATE, 20.0)

        assertEquals(listOf<Any?>(12.5), seen)
    }

    @Test
    fun aListenerThatUnsubscribesItselfDoesNotDisturbTheEmitInFlight() {
        val bus = StringEventBus()
        val seen = mutableListOf<String>()
        lateinit var first: (Any?) -> Unit
        first = { seen.add("first"); bus.off(CanonicalBackendEvent.PLAY, first) }
        bus.on(CanonicalBackendEvent.PLAY, first)
        bus.on(CanonicalBackendEvent.PLAY) { seen.add("second") }

        bus.emit(CanonicalBackendEvent.PLAY)
        bus.emit(CanonicalBackendEvent.PLAY)

        assertEquals(listOf("first", "second", "second"), seen)
    }

    @Test
    fun theSpineIsAStrictSubsetOfTheFullOrder() {
        // The spine is what is required; the full order is what is hoped for.
        // If they ever disagree about relative order, the vocabulary is wrong.
        val positions = CanonicalBackendEvent.PLAY_PAUSE_SPINE.map {
            CanonicalBackendEvent.FULL_ORDER.indexOf(it)
        }

        assertTrue(positions.none { it == -1 })
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun everyCanonicalNameIsListedInAll() {
        // The recorder subscribes by iterating ALL, so a name missing from it is
        // a name no gate would ever see.
        val declared = listOf(
            CanonicalBackendEvent.LOAD_START,
            CanonicalBackendEvent.LOADED_METADATA,
            CanonicalBackendEvent.CAN_PLAY,
            CanonicalBackendEvent.PLAY,
            CanonicalBackendEvent.PLAYING,
            CanonicalBackendEvent.TIME_UPDATE,
            CanonicalBackendEvent.WAITING,
            CanonicalBackendEvent.STALLED,
            CanonicalBackendEvent.PAUSE,
            CanonicalBackendEvent.ENDED,
            CanonicalBackendEvent.ERROR,
            CanonicalBackendEvent.STREAM_ERROR,
        )

        assertEquals(declared.toSet(), CanonicalBackendEvent.ALL.toSet())
    }
}
