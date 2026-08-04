// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.Clock
import tv.nomercy.player.testing.FakeMediaBackend
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The counters, actually counting.
 *
 * Every field of PlaybackMetrics was zero for the life of every session because
 * nothing in the trio called recordMetric — the sampler published six zeros on
 * a timer and the testbed log filled with them. These three are the ones core
 * can count from events it already has.
 */
class MetricsCountingTest {

    @Test
    fun aStallIsCountedOnceAndItsSecondsAddUp() {
        val clock = SteppingClock()
        val metrics = MetricsController(clock)

        metrics.startSession()
        clock.advance(500)
        // Waiting and stalled both arrive for one stall. Counting each would
        // report twice the stalls that happened.
        metrics.onWaitingStarted()
        metrics.onWaitingStarted()
        clock.advance(2_000)
        metrics.onWaitingEnded()

        assertEquals(1.0, metrics.metrics().bufferingEvents)
        assertEquals(2.0, metrics.metrics().bufferingSeconds)
    }

    // The number a viewer is staring at is how long they have been waiting, and
    // adding it only when the stall ends leaves it at zero for exactly as long
    // as the wait lasts.
    @Test
    fun aStallStillRunningIsAlreadyInTheSeconds() {
        val clock = SteppingClock()
        val metrics = MetricsController(clock)

        metrics.startSession()
        metrics.onWaitingStarted()
        clock.advance(3_000)

        assertEquals(3.0, metrics.metrics().bufferingSeconds)
    }

    @Test
    fun startupIsTheTimeToTheFirstFrameAndASeekDoesNotOverwriteIt() {
        val clock = SteppingClock()
        val metrics = MetricsController(clock)

        metrics.startSession()
        clock.advance(1_500)
        metrics.onFirstFrame()
        clock.advance(60_000)
        // A seek renders a first frame too. Letting it through would report the
        // cost of the last scrub as the cost of starting the film.
        metrics.onFirstFrame()

        assertEquals(1.5, metrics.metrics().startupSeconds)
    }

    @Test
    fun aNewItemStartsFromNothing() {
        val clock = SteppingClock()
        val metrics = MetricsController(clock)

        metrics.startSession()
        metrics.onWaitingStarted()
        clock.advance(4_000)
        metrics.onWaitingEnded()

        metrics.startSession()

        assertEquals(0.0, metrics.metrics().bufferingEvents)
        assertEquals(0.0, metrics.metrics().bufferingSeconds)
    }
}

private class SteppingClock : Clock {
    private var millis: Long = 0L

    override fun now(): Long = millis

    fun advance(by: Long) {
        millis += by
    }
}

/**
 * The same counters, through the PLAYER.
 *
 * The class above grades MetricsController, which was never the broken layer:
 * it counted correctly the whole time and nothing called it. These assert the
 * subscription exists, so a future edit that drops the wiring fails here rather
 * than shipping a log full of zeros again.
 */
class PlayerMetricsWiringTest {

    @Test
    fun aStallReportedByTheEngineReachesTheMetrics() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend, scope = backgroundScope)
        player.setup(PlayerConfig())

        backend.fire(CanonicalBackendEvent.WAITING)

        assertEquals(1.0, player.metrics().bufferingEvents)
    }

    @Test
    fun playingAgainClosesTheStall() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend, scope = backgroundScope)
        player.setup(PlayerConfig())

        backend.fire(CanonicalBackendEvent.WAITING)
        backend.fire(CanonicalBackendEvent.PLAYING)
        backend.fire(CanonicalBackendEvent.WAITING)

        assertEquals(2.0, player.metrics().bufferingEvents)
    }
}
