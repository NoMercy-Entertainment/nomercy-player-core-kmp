// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.TimeUpdate
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.ports.Clock
import tv.nomercy.player.core.ports.defaultClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend

class TimeControllerTest {

    private class TimeRig(private val clock: Clock = defaultClock()) {
        val ctx: PlayerContext = newContext()
        val queue: QueueController = QueueController(ctx)
        val transport: TransportController = TransportController(ctx, queue)
        val time: TimeController = TimeController(ctx, queue, transport, clock)
        val backend: FakeMediaBackend get() = ctx.fakeBackend()

        init {
            queue.transport = transport
            queue.wireQueue()
        }

        fun ready(): TimeRig = apply { ctx.transitionPhase(PlayerPhase.READY) }
    }

    @Test
    fun theTimeGetterAsksTheEngineRatherThanTheRememberedCopy() {
        val rig = TimeRig()
        rig.ctx.internalCurrentTime = 12.5

        // An engine reports timeupdate on its own cadence — libVLC a few times
        // a second — so the remembered copy is stale between them, and anything
        // following the playhead per frame drew the same position repeatedly.
        // The engine's own clock is continuous, so it is the one to ask.
        rig.backend.currentTime(30.0)

        assertEquals(30.0, rig.time.time(), "the getter answered from the remembered copy, not the engine")
    }

    @Test
    fun thePlayheadKeepsMovingBetweenTwoIdenticalEngineReports() {
        // libVLC answers get_time from its last input update, a few times a
        // second, so an engine that is playing hands back the same number for
        // hundreds of milliseconds. Anything following the playhead per frame
        // inherits that: the subtitle overlay redraws sixty times a second and
        // was measured advancing four.
        val stopwatch = TestClock()
        val rig = TimeRig(stopwatch).ready()
        rig.ctx.playState = PlayState.PLAYING
        rig.backend.currentTime(30.0)

        val anchored: Double = rig.time.time()
        stopwatch.advance(100L)

        assertTrue(
            rig.time.time() > anchored,
            "the engine reported the same number twice and the playhead stood still",
        )
    }

    @Test
    fun aPausedEngineHoldsThePlayheadWhereItIs() {
        // The carry is elapsed time, and a paused engine reports the same
        // position forever. Carrying that walks the subtitles into a part of
        // the film nobody is watching.
        val stopwatch = TestClock()
        val rig = TimeRig(stopwatch).ready()
        rig.ctx.playState = PlayState.PAUSED
        rig.backend.currentTime(30.0)

        rig.time.time()
        stopwatch.advance(2_000L)

        assertEquals(30.0, rig.time.time(), "a paused playhead moved")
    }

    @Test
    fun theCarryStopsAtTheCadenceTheEngineReportsAt() {
        // An engine that stops speaking entirely — a stall, a lost backend —
        // must not let the playhead run away on its own. The carry is bounded
        // by how far apart its reports actually are.
        val stopwatch = TestClock()
        val rig = TimeRig(stopwatch).ready()
        rig.ctx.playState = PlayState.PLAYING
        rig.backend.currentTime(30.0)

        rig.time.time()
        stopwatch.advance(10_000L)

        assertTrue(rig.time.time() < 31.0, "the playhead ran away from an engine that went quiet")
    }

    @Test
    fun aBackwardsReportIsFollowed() {
        // A position that goes back is a seek, and a monotonic guard that
        // clamped it would leave the playhead in the part of the film the
        // viewer just left.
        val stopwatch = TestClock()
        val rig = TimeRig(stopwatch).ready()
        rig.ctx.playState = PlayState.PLAYING
        rig.backend.currentTime(30.0)
        rig.time.time()

        stopwatch.advance(50L)
        rig.backend.currentTime(5.0)

        assertEquals(5.0, rig.time.time(), "a seek backwards was clamped away")
    }

    private class TestClock : Clock {
        private var millis: Long = 0L
        override fun now(): Long = millis
        fun advance(by: Long) {
            millis += by
        }
    }

    @Test
    fun seekingClampsBelowZeroAndAnnouncesSeekThenSeeked() = runTest {
        val rig = TimeRig().ready()
        val seeks = EventLog().capture(rig.ctx, CoreEvents.Seek)
        val seekeds = EventLog().capture(rig.ctx, CoreEvents.Seeked)

        rig.time.time(-4.0)

        assertEquals(0.0, rig.ctx.internalCurrentTime)
        assertEquals(0.0, seeks.single().time)
        assertEquals(0.0, seekeds.single().time)
        assertEquals(listOf(0.0), rig.backend.seekedTo)
    }

    @Test
    fun aRefusedSeekLeavesThePlayheadAndTheEngineAlone() = runTest {
        val rig = TimeRig().ready()
        rig.ctx.internalCurrentTime = 5.0
        rig.ctx.on(CoreEvents.BeforeSeek) { it.preventDefault() }
        var prevented = 0
        rig.ctx.on(CoreEvents.SeekPrevented) { prevented += 1 }

        rig.time.time(20.0)

        assertEquals(1, prevented)
        assertEquals(5.0, rig.ctx.internalCurrentTime)
        assertTrue(rig.backend.seekedTo.isEmpty())
    }

    @Test
    fun durationComesFromTheContextAndBufferedFromTheEngine() {
        val rig = TimeRig()
        rig.ctx.internalDuration = 120.0
        rig.backend.bufferedValue = 30.0

        assertEquals(120.0, rig.time.duration())
        assertEquals(30.0, rig.time.buffered())
    }

    @Test
    fun percentageIsZeroBeforeADurationIsKnown() {
        val rig = TimeRig()
        rig.ctx.internalCurrentTime = 10.0

        // Not a division by zero, and not NaN reaching a progress bar.
        assertEquals(0.0, rig.time.percentage())

        rig.ctx.internalDuration = 40.0
        assertEquals(25.0, rig.time.percentage())
    }

    @Test
    fun seekingByPercentageLandsOnTheRightSecond() = runTest {
        val rig = TimeRig().ready()
        rig.ctx.internalDuration = 200.0

        rig.time.seekByPercentage(25.0)

        assertEquals(50.0, rig.ctx.internalCurrentTime)
    }

    @Test
    fun seekingByPercentageDoesNothingBeforeADurationIsKnown() = runTest {
        val rig = TimeRig().ready()
        rig.ctx.internalDuration = 0.0

        rig.time.seekByPercentage(50.0)

        // Would otherwise jump to zero, which is not where the viewer let go.
        assertTrue(rig.backend.seekedTo.isEmpty())
    }

    @Test
    fun theRateIsClampedForwardedAndAnnouncedTwice() = runTest {
        val rig = TimeRig()
        var announced = -1.0
        var fromEngine = -1.0
        rig.ctx.on(CoreEvents.PlaybackRate) { announced = it.rate }
        rig.ctx.on(CoreEvents.BackendRateChange) { fromEngine = it.rate }

        rig.time.playbackRate(3.0)

        assertEquals(2.0, rig.ctx.playbackRate)
        assertEquals(2.0, announced)
        assertEquals(2.0, fromEngine)
        assertEquals(listOf(2.0), rig.backend.ratesSet)
    }

    @Test
    fun aRefusedRateChangeLeavesTheRateAlone() = runTest {
        val rig = TimeRig()
        rig.ctx.on(CoreEvents.BeforePlaybackRate) { it.preventDefault() }
        var prevented = 0
        rig.ctx.on(CoreEvents.PlaybackRatePrevented) { prevented += 1 }

        rig.time.playbackRate(1.5)

        assertEquals(1, prevented)
        assertEquals(1.0, rig.ctx.playbackRate)
        assertTrue(rig.backend.ratesSet.isEmpty())
    }

    @Test
    fun theOfferedRatesAreOrderedAndIncludeNormalSpeed() {
        val rates = TimeRig().time.playbackRates()

        assertEquals(rates.sorted(), rates)
        assertTrue(rates.contains(1.0))
    }

    @Test
    fun endingSoonFiresOnceNotOnEveryTimeUpdateInsideTheWindow() = runTest {
        val rig = TimeRig()
        rig.queue.queue(items("a"))
        rig.queue.item("a")
        var fires = 0
        var remaining = -1.0
        rig.ctx.on(CoreEvents.ItemEndingSoon) { fires += 1; remaining = it.remaining }

        rig.time.checkItemEndingSoon(currentTime = 95.0, duration = 100.0)
        rig.time.checkItemEndingSoon(currentTime = 96.0, duration = 100.0)
        rig.time.checkItemEndingSoon(currentTime = 97.0, duration = 100.0)

        // A preloader that restarted on every tick would fetch the next item
        // once per frame.
        assertEquals(1, fires)
        assertEquals(5.0, remaining)
    }

    @Test
    fun endingSoonDoesNotFireOutsideTheWindowOrWithoutADuration() = runTest {
        val rig = TimeRig()
        rig.queue.queue(items("a"))
        var fires = 0
        rig.ctx.on(CoreEvents.ItemEndingSoon) { fires += 1 }

        rig.time.checkItemEndingSoon(currentTime = 10.0, duration = 100.0)
        rig.time.checkItemEndingSoon(currentTime = 10.0, duration = 0.0)

        assertEquals(0, fires)
    }

    @Test
    fun theEndingSoonLatchCanBeClearedForARewind() = runTest {
        val rig = TimeRig()
        rig.queue.queue(items("a"))
        var fires = 0
        rig.ctx.on(CoreEvents.ItemEndingSoon) { fires += 1 }

        rig.time.checkItemEndingSoon(95.0, 100.0)
        rig.time.resetItemEndingSoonLatch()
        rig.time.checkItemEndingSoon(95.0, 100.0)

        assertEquals(2, fires)
    }

    @Test
    fun theEnginesOwnTickIsWhatFiresEndingSoon() = runTest {
        // The check existed, was tested, and nothing in the player called it, so
        // itemEndingSoon never fired outside its own test — and the auto-advance
        // window, the preload head start and an "up next" card all wait on it.
        // Driven through the event the engine's tick actually produces rather
        // than by calling the check, which is what the gap was.
        val rig = TimeRig()
        rig.queue.queue(items("a"))
        var fires = 0
        rig.ctx.on(CoreEvents.ItemEndingSoon) { fires += 1 }

        rig.ctx.emit(CoreEvents.Time, TimeUpdate(time = 95.0, duration = 100.0, percentage = 95.0))

        assertEquals(1, fires)
    }

    @Test
    fun theEndingSoonPayloadCarriesTheItemItIsAbout() = runTest {
        val rig = TimeRig()
        rig.queue.queue(items("a", "b"))
        rig.queue.item("b")
        var seenId: String? = null
        rig.ctx.on(CoreEvents.ItemEndingSoon) { seenId = it.item?.id }

        rig.time.checkItemEndingSoon(95.0, 100.0)

        // By the time a preloader finishes, the cursor may have moved on.
        assertEquals("b", seenId)
    }
}
