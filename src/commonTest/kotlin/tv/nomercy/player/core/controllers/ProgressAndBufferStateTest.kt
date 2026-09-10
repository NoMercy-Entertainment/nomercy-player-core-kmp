// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.TimeState
import tv.nomercy.player.core.player.BufferState
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem

// The progress bar's numbers, and the reason the picture is not moving.
class ProgressAndBufferStateTest {

    private suspend fun playing(): Pair<ComposedPlayer, FakeMediaBackend> {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        player.queue(listOf(TestItem("a")))
        player.setup()
        player.play()
        return player to backend
    }

    // The player keeps its own position, synced from the engine's tick rather
    // than read from it on demand — so a test has to tick it, the same way a
    // real engine does. Setting the fake's fields alone would assert against a
    // path nothing takes.
    private fun FakeMediaBackend.tick(position: Double, total: Double = 0.0, ahead: Double = 0.0) {
        currentTimeValue = position
        durationValue = total
        bufferedValue = ahead
        fire(CanonicalBackendEvent.TIME_UPDATE)
    }

    @Test
    fun aSnapshotCarriesTheDerivedFieldsSoAChromeDoesNotComputeThem() = runTest {
        val (player, backend) = playing()

        backend.tick(position = 25.0, total = 100.0, ahead = 40.0)

        val snapshot: TimeState = player.timeData()

        // The position advances between engine ticks now — the renderer owns the
        // clock — so the one field that keeps moving is compared with a
        // tolerance and the three derived from it are checked against IT, which
        // is the thing this test is actually about: a chrome reading a snapshot
        // rather than computing the same three numbers itself.
        assertTrue(
            snapshot.time >= 25.0 && snapshot.time < 25.5,
            "the snapshot did not carry the position it was ticked to: ${snapshot.time}",
        )
        assertEquals(100.0, snapshot.duration)
        assertEquals(40.0, snapshot.buffered)
        assertEquals(snapshot.duration - snapshot.time, snapshot.remaining)
        assertEquals(snapshot.time / snapshot.duration * 100.0, snapshot.percentage)
    }

    @Test
    fun remainingNeverGoesNegative() = runTest {
        // Ordinary at the end of an item: the backend reports a position past a
        // duration it has not refreshed. A chrome rendering "-3s remaining"
        // looks broken.
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)

        backend.tick(position = 103.0, total = 100.0)

        assertEquals(0.0, player.timeData().remaining)
    }

    @Test
    fun aLiveStreamReportsZeroPercentRatherThanDividingByZero() = runTest {
        // The duration is unknown for the whole of a live stream, which is not
        // an edge case there — it is the normal state.
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)

        backend.tick(position = 240.0)

        val snapshot: TimeState = player.timeData()

        assertEquals(0.0, snapshot.percentage)
        assertEquals(0.0, snapshot.remaining)
        assertEquals(240.0, snapshot.time, "a live stream still has a position")
    }

    @Test
    fun aFreshPlayerIsNotBuffering() {
        assertEquals(BufferState.IDLE, ComposedPlayer(backend = FakeMediaBackend()).bufferState())
    }

    @Test
    fun aSourceStartingToLoadIsLoadingNotStalled() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)

        backend.fire(CanonicalBackendEvent.LOAD_START)

        assertEquals(BufferState.LOADING, player.bufferState())
    }

    @Test
    fun runningDryBeforeTheFirstFrameIsStillLoading() = runTest {
        // Nothing has been shown yet, so a spinner over a black frame is what a
        // viewer expects and there is nothing unusual to report.
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)

        backend.fire(CanonicalBackendEvent.WAITING)

        assertEquals(BufferState.LOADING, player.bufferState())
    }

    @Test
    fun runningDryAfterPlaybackStartedIsAStall() = runTest {
        // The same engine event, and the thing a viewer actually needs told:
        // the connection is not keeping up. Showing the same spinner as at
        // startup says nothing about a picture that was moving a moment ago.
        val (player, backend) = playing()

        backend.fire(CanonicalBackendEvent.WAITING)

        assertEquals(BufferState.STALLED, player.bufferState())
    }

    @Test
    fun playingAgainClearsTheStall() = runTest {
        val (player, backend) = playing()
        backend.fire(CanonicalBackendEvent.WAITING)

        backend.fire(CanonicalBackendEvent.PLAYING)

        assertEquals(BufferState.IDLE, player.bufferState())
    }

    @Test
    fun aPositionThatKeepsAdvancingClearsAStallTheEngineNeverTakesBack() = runTest {
        // The desktop engine's stall, measured rather than imagined. libVLC
        // announces canplay once per item and re-announces playing only on a
        // state change, so after a stall at an HLS rendition switch neither of
        // the two events that could clear one ever arrives again — the only
        // thing still coming is the position, several times a second. Left as
        // it was, bufferState stayed STALLED for the remaining eighty seconds
        // of a film playing at full rate.
        val (player, backend) = playing()
        backend.fire(CanonicalBackendEvent.WAITING)

        backend.tick(position = 12.0, total = 100.0)

        assertEquals(BufferState.IDLE, player.bufferState())
    }

    @Test
    fun theSnapshotCarriesTheBufferStateSoAChromeCanDrawItsSpinner() = runTest {
        // PlayerState declared the field and StateController never filled it in,
        // so every snapshot ever taken reported the default. ChromeStateAdapter
        // reads it as its `buffering` flag, which means the spinner could not
        // appear through any stall there has ever been.
        val (player, backend) = playing()

        backend.fire(CanonicalBackendEvent.WAITING)

        assertEquals(BufferState.STALLED, player.state().bufferState)
    }

    @Test
    fun anExplicitStallIsAStallWheneverItArrives() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)

        backend.fire(CanonicalBackendEvent.STALLED)

        assertEquals(BufferState.STALLED, player.bufferState())
    }

    // A player sitting still is not waiting for data, whether it was paused or
    // never started.
    //
    // The guard read the PHASE, which is only PAUSED once something has paused
    // — a player parked at the start has never been there. So the engine's own
    // buffering, which happens whether or not anybody asked for playback, was
    // reported as a stall, and a stall on a still picture clears on a position
    // that advances: nothing was advancing, so it stayed for ever.
    @Test
    fun anEngineFillingItsBufferOnAPlayerNobodyStartedIsNotAStall() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        player.queue(listOf(TestItem("a")))
        player.setup()
        // Loaded and parked at the top, which is where a pre-screen leaves it:
        // nobody has pressed play, so nothing has ever been PAUSED either.
        backend.fire(CanonicalBackendEvent.CAN_PLAY)

        backend.fire(CanonicalBackendEvent.WAITING)

        assertEquals(BufferState.IDLE, player.bufferState())
    }

    @Test
    fun havingEnoughDataToPlayClearsTheLoadingState() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        backend.fire(CanonicalBackendEvent.LOAD_START)

        backend.fire(CanonicalBackendEvent.CAN_PLAY)

        assertEquals(BufferState.IDLE, player.bufferState())
    }
}
