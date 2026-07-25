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
import tv.nomercy.player.core.player.PlayerPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeControllerTest {

    private class TimeRig {
        val ctx: PlayerContext = newContext()
        val queue: QueueController = QueueController(ctx)
        val transport: TransportController = TransportController(ctx, queue)
        val time: TimeController = TimeController(ctx, queue, transport)
        val backend: FakeMediaBackend get() = ctx.fakeBackend()

        init {
            queue.transport = transport
            queue.wireQueue()
        }

        fun ready(): TimeRig = apply { ctx.transitionPhase(PlayerPhase.READY) }
    }

    @Test
    fun theTimeGetterReadsTheOneCopyOfThePlayhead() {
        val rig = TimeRig()
        rig.ctx.internalCurrentTime = 12.5

        assertEquals(12.5, rig.time.time())
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
