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

        assertEquals(
            TimeState(time = 25.0, duration = 100.0, buffered = 40.0, remaining = 75.0, percentage = 25.0),
            snapshot,
        )
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
    fun anExplicitStallIsAStallWheneverItArrives() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)

        backend.fire(CanonicalBackendEvent.STALLED)

        assertEquals(BufferState.STALLED, player.bufferState())
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
