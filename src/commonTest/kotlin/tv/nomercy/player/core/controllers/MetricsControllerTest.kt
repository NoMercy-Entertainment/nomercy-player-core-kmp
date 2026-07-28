// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.PlaybackMetrics
import tv.nomercy.player.core.ports.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem

// A clock a test drives, which is the whole reason Clock is a port.
private class StoppedClock(var millis: Long = 0L) : Clock {
    override fun now(): Long = millis
}

class MetricsControllerTest {

    @Test
    fun aFreshControllerHasCountedNothing() {
        val metrics: PlaybackMetrics = MetricsController(StoppedClock()).metrics()

        assertEquals(PlaybackMetrics(), metrics)
    }

    @Test
    fun aKnownCounterLandsOnItsField() {
        val subject = MetricsController(StoppedClock())

        subject.recordMetric(Metric.DROPPED_FRAMES, 12.0)

        assertEquals(12.0, subject.metrics().droppedFrames)
    }

    @Test
    fun aStringNameThatMatchesAKnownCounterIsNotADuplicate() {
        // A backend written against the web's string API must not produce a
        // second droppedFrames beside the typed one, or a support ticket gets
        // two answers to one question.
        val subject = MetricsController(StoppedClock())

        subject.recordMetric("droppedFrames", 12.0)

        assertEquals(12.0, subject.metrics().droppedFrames)
        assertEquals(emptyMap(), subject.metrics().custom, "a known counter was stored twice")
    }

    @Test
    fun anUnknownNameIsKeptRatherThanDropped() {
        val subject = MetricsController(StoppedClock())

        subject.recordMetric("lyricFetches", 3.0)

        assertEquals(mapOf("lyricFetches" to 3.0), subject.metrics().custom)
    }

    @Test
    fun theSessionDurationAdvancesWhileNothingElseHappens() {
        // The stretch a stalled player is asked about is exactly the stretch
        // where nothing writes a metric. A stored duration would stand still
        // through it and report zero.
        val clock = StoppedClock(1_000L)
        val subject = MetricsController(clock)
        subject.startSession()

        clock.millis = 31_000L

        assertEquals(30_000L, subject.metrics().sessionDurationMs)
    }

    @Test
    fun withNoSessionTheDurationIsZeroRatherThanTheEpoch() {
        val subject = MetricsController(StoppedClock(1_700_000_000_000L))

        assertEquals(0L, subject.metrics().sessionDurationMs)
    }

    @Test
    fun anEndedSessionStopsCounting() {
        val clock = StoppedClock(1_000L)
        val subject = MetricsController(clock)
        subject.startSession()
        subject.endSession()

        clock.millis = 60_000L

        assertEquals(0L, subject.metrics().sessionDurationMs)
    }

    @Test
    fun aNewSessionStartsFromNothing() {
        // Carrying the last item's dropped frames into the next one would make
        // every counter cumulative over a queue, which is not what any of them
        // are named for.
        val subject = MetricsController(StoppedClock())
        subject.recordMetric(Metric.DROPPED_FRAMES, 40.0)
        subject.recordMetric("lyricFetches", 3.0)

        subject.startSession()

        assertEquals(0.0, subject.metrics().droppedFrames)
        assertEquals(emptyMap(), subject.metrics().custom)
    }

    @Test
    fun aSnapshotDoesNotChangeUnderTheCallerThatTookIt() {
        val subject = MetricsController(StoppedClock())
        subject.recordMetric("lyricFetches", 1.0)
        val taken: PlaybackMetrics = subject.metrics()

        subject.recordMetric("lyricFetches", 99.0)

        assertEquals(mapOf("lyricFetches" to 1.0), taken.custom)
    }

    @Test
    fun everyNamedMetricRoundTripsThroughItsKey() {
        // The enum and the string API have to agree about all six names, or a
        // backend using one and a dashboard using the other disagree about
        // which counter is which.
        val subject = MetricsController(StoppedClock())

        Metric.entries.forEachIndexed { index, metric ->
            subject.recordMetric(metric.key, (index + 1).toDouble())
        }

        val snapshot: PlaybackMetrics = subject.metrics()
        assertEquals(emptyMap(), snapshot.custom, "a named metric fell through to the custom map")
        assertEquals(1.0, snapshot.droppedFrames)
        assertEquals(6.0, snapshot.bitrate)
    }

    @Test
    fun thePlayerRestartsCountingWhenTheItemChanges() = runTest {
        val player = ComposedPlayer(backend = FakeMediaBackend())
        player.queue(listOf(TestItem("a"), TestItem("b")))
        player.recordMetric(Metric.DROPPED_FRAMES, 40.0)

        player.item("b")

        assertEquals(0.0, player.metrics().droppedFrames, "the last item's frames were counted against the next")
    }

    @Test
    fun thePlayerReadsTimeThroughItsClock() {
        val clock = StoppedClock(1_700_000_000_000L)

        val player = ComposedPlayer(backend = FakeMediaBackend(), clock = clock)

        assertEquals(1_700_000_000_000L, player.now())
        assertTrue(player.now() == clock.now(), "the player kept its own idea of the time")
    }
}
