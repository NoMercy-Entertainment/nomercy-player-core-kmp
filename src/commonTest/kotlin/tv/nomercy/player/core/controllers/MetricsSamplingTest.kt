// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlaybackMetrics
import tv.nomercy.player.core.player.PlayerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend

// metricsIntervalMs had zero readers, so the counters were only readable by a
// consumer that thought to poll them — which is the one thing a support ticket
// cannot do after the session it is about has ended.
class MetricsSamplingTest {

    private fun TestScope.setUp(config: PlayerConfig): ComposedPlayer {
        val player = ComposedPlayer(backend = FakeMediaBackend(), scope = backgroundScope)
        backgroundScope.launch { player.setup(config) }
        runCurrent()
        return player
    }

    @Test
    fun theCountersAreHandedOverOnTheConfiguredInterval() = runTest {
        val player = setUp(PlayerConfig(metricsIntervalMs = 1_000))
        val samples: MutableList<PlaybackMetrics> = mutableListOf()
        player.on(CoreEvents.PlaybackMetrics) { samples += it }

        advanceTimeBy(3_500)
        runCurrent()

        assertEquals(3, samples.size)
    }

    @Test
    fun zeroTurnsSamplingOff() = runTest {
        val player = setUp(PlayerConfig(metricsIntervalMs = 0))
        var samples = 0
        player.on(CoreEvents.PlaybackMetrics) { samples += 1 }

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(0, samples)
    }

    @Test
    fun aSampleCarriesWhatWasRecordedSinceTheItemStarted() = runTest {
        val player = setUp(PlayerConfig(metricsIntervalMs = 1_000))
        val samples: MutableList<PlaybackMetrics> = mutableListOf()
        player.on(CoreEvents.PlaybackMetrics) { samples += it }

        player.recordMetric(Metric.DROPPED_FRAMES, 7.0)
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(7.0, samples.single().droppedFrames)
    }

    @Test
    fun disposingStopsTheSampler() = runTest {
        // A timer that outlives the player it was measuring keeps a whole
        // player graph alive and emits into listeners that are gone.
        val player = setUp(PlayerConfig(metricsIntervalMs = 1_000))
        var samples = 0
        player.on(CoreEvents.PlaybackMetrics) { samples += 1 }

        advanceTimeBy(1_500)
        runCurrent()
        val before: Int = samples

        backgroundScope.launch { player.dispose() }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(before > 0, "the sampler has to have been running for this to prove anything")
        assertEquals(before, samples)
    }
}
