// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.player.RepeatState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransportControllerTest {

    @Test
    fun playingChangesTheStateAnnouncesItAndReachesTheEngine() = runTest {
        val rig = Rig().ready()
        val played = EventLog().capture(rig.ctx, CoreEvents.Play)
        val phases = EventLog().capture(rig.ctx, CoreEvents.Phase)

        rig.transport.play()

        assertEquals(PlayState.PLAYING, rig.ctx.playState)
        assertEquals(1, played.size)
        assertEquals(PlayerPhase.STARTING, phases.last().to)
        assertEquals(1, rig.backend.playCount)
    }

    @Test
    fun aVetoedPlayNeverReachesTheEngineAndLeavesTheStateAlone() = runTest {
        val rig = Rig().ready()
        rig.ctx.on(CoreEvents.BeforePlay) { it.preventDefault() }
        val prevented = EventLog().capture(rig.ctx, CoreEvents.PlayPrevented)

        rig.transport.play()

        // The veto has to prevent the action, not undo it afterwards.
        assertEquals(0, rig.backend.playCount)
        assertEquals(PlayState.IDLE, rig.ctx.playState)
        assertEquals(1, prevented.size)
        assertEquals("listener-prevented", prevented.single().reason)
    }

    @Test
    fun playingBeforeSetupIsRefusedWithTheNotReadyCode() = runTest {
        val rig = Rig()

        assertEquals(
            "core:player/not-ready",
            assertFailsWith<PlayerError> { rig.transport.play() }.code,
        )
    }

    @Test
    fun pausingChangesTheStateAndReachesTheEngine() = runTest {
        val rig = Rig().ready()
        rig.transport.play()
        val phases = EventLog().capture(rig.ctx, CoreEvents.Phase)

        rig.transport.pause()

        assertEquals(PlayState.PAUSED, rig.ctx.playState)
        assertEquals(PlayerPhase.PAUSED, phases.last().to)
        assertEquals(1, rig.backend.pauseCount)
    }

    @Test
    fun aVetoedPauseLeavesItPlaying() = runTest {
        val rig = Rig().ready()
        rig.transport.play()
        rig.ctx.on(CoreEvents.BeforePause) { it.preventDefault() }
        val prevented = EventLog().capture(rig.ctx, CoreEvents.PausePrevented)

        rig.transport.pause()

        assertEquals(1, prevented.size)
        assertEquals(0, rig.backend.pauseCount)
        assertEquals(PlayState.PLAYING, rig.ctx.playState)
    }

    @Test
    fun stoppingReachesTheEngineAndLandsInTheStoppedPhase() = runTest {
        val rig = Rig().ready()
        rig.transport.play()

        rig.transport.stop()

        assertEquals(PlayState.STOPPED, rig.ctx.playState)
        assertEquals(PlayerPhase.STOPPED, rig.ctx.phase)
        assertEquals(1, rig.backend.stopCount)
    }

    @Test
    fun toggleFlipsBetweenPlayingAndPaused() = runTest {
        val rig = Rig().ready()

        rig.transport.togglePlayback()
        assertEquals(1, rig.backend.playCount)

        rig.transport.togglePlayback()
        assertEquals(1, rig.backend.pauseCount)
    }

    @Test
    fun nextMovesTheCursorLoadsAndPlays() = runTest {
        val rig = Rig().ready()
        rig.queue.queue(items("a", "b", "c"))
        val seen = EventLog().capture(rig.ctx, CoreEvents.Item)

        rig.transport.next()

        assertEquals("b", rig.queue.item()?.id)
        assertEquals("b", seen.last().item?.id)
        assertEquals(listOf("https://example.test/b"), rig.backend.loadedUrls)
        assertEquals(1, rig.backend.playCount)
    }

    @Test
    fun theCursorMovesBeforeTheMediaDoes() = runTest {
        val rig = Rig().ready()
        rig.queue.queue(items("a", "b"))
        val order = mutableListOf<String>()
        rig.ctx.on(CoreEvents.Item) { order += "item" }
        rig.ctx.on(CoreEvents.Play) { order += "play" }

        rig.transport.next()

        // A chrome renders the new item before the buffering it is about to
        // show, not after.
        assertEquals(listOf("item", "play"), order)
    }

    @Test
    fun reachingTheEndWithRepeatOffSaysTheQueueIsDone() = runTest {
        val rig = Rig().ready()
        rig.queue.queue(items("a", "b"))
        rig.queue.item("b")
        val exhausted = EventLog().capture(rig.ctx, CoreEvents.QueueExhausted)

        rig.transport.next()

        assertEquals(1, exhausted.size)
        assertEquals("b", rig.queue.item()?.id)
    }

    @Test
    fun repeatAllWrapsBackToTheTop() = runTest {
        val rig = Rig().ready()
        rig.queue.queue(items("a", "b"))
        rig.queue.item("b")
        rig.ctx.repeatState = RepeatState.ALL

        rig.transport.next()

        assertEquals("a", rig.queue.item()?.id)
        assertEquals("https://example.test/a", rig.backend.loadedUrls.last())
    }

    @Test
    fun repeatOneReloadsTheSameItemWithoutMovingTheCursor() = runTest {
        val rig = Rig().ready()
        rig.queue.queue(items("a", "b"))
        rig.ctx.repeatState = RepeatState.ONE

        rig.transport.next()

        assertEquals("a", rig.queue.item()?.id)
        assertEquals(0, rig.queue.index())
        assertEquals("https://example.test/a", rig.backend.loadedUrls.last())
    }

    @Test
    fun aVetoedNextLoadsNothingAndLeavesTheCursorWhereItWas() = runTest {
        val rig = Rig().ready()
        rig.queue.queue(items("a", "b"))
        rig.ctx.on(CoreEvents.BeforeNext) { it.preventDefault() }
        val prevented = EventLog().capture(rig.ctx, CoreEvents.NextPrevented)

        rig.transport.next()

        assertEquals(1, prevented.size)
        assertEquals("a", rig.queue.item()?.id)
        assertEquals(0, rig.backend.loadedUrls.size)
    }

    @Test
    fun previousMovesBackAndLoads() = runTest {
        val rig = Rig().ready()
        rig.queue.queue(items("a", "b", "c"))
        rig.queue.item("c")

        rig.transport.previous()

        assertEquals("b", rig.queue.item()?.id)
        assertEquals("https://example.test/b", rig.backend.loadedUrls.last())
    }

    @Test
    fun previousAtTheStartDoesNothingAndDoesNotClaimTheQueueIsDone() = runTest {
        val rig = Rig().ready()
        rig.queue.queue(items("a", "b"))
        val exhausted = EventLog().capture(rig.ctx, CoreEvents.QueueExhausted)

        rig.transport.previous()

        // Reaching the beginning is not the same event as running out.
        assertEquals("a", rig.queue.item()?.id)
        assertEquals(0, exhausted.size)
    }

    @Test
    fun rewindingClampsAtTheStart() = runTest {
        val rig = Rig().ready()
        rig.ctx.internalCurrentTime = 3.0

        rig.transport.rewind(5.0)

        assertEquals(0.0, rig.ctx.internalCurrentTime)
        assertEquals(listOf(0.0), rig.backend.seekedTo)
    }

    @Test
    fun forwardingAddsTheDelta() = runTest {
        val rig = Rig().ready()
        rig.ctx.internalCurrentTime = 10.0

        rig.transport.forward(5.0)

        assertEquals(15.0, rig.ctx.internalCurrentTime)
        assertEquals(listOf(15.0), rig.backend.seekedTo)
    }

    @Test
    fun restartingSeeksToZeroAndPlays() = runTest {
        val rig = Rig().ready()

        rig.transport.restart()

        assertEquals(listOf(0.0), rig.backend.seekedTo)
        assertEquals(1, rig.backend.playCount)
    }

    @Test
    fun aVetoedSeekStopsARestartFromPlayingAtAll() = runTest {
        val rig = Rig().ready()
        rig.ctx.on(CoreEvents.BeforeSeek) { it.preventDefault() }

        rig.transport.restart()

        // Restarting without the seek would just be play(), which is not what
        // the caller asked for.
        assertEquals(0, rig.backend.seekedTo.size)
        assertEquals(0, rig.backend.playCount)
    }

    @Test
    fun aSeekListenerCanRedirectWhereItLands() = runTest {
        val rig = Rig().ready()
        rig.ctx.on(CoreEvents.BeforeSeek) { it.data = it.data.copy(time = 42.0) }

        rig.transport.seek(10.0)

        assertEquals(listOf(42.0), rig.backend.seekedTo)
        assertEquals(42.0, rig.ctx.internalCurrentTime)
    }

    @Test
    fun seekingWhilePlayingPassesThroughSeekingAndComesBack() = runTest {
        val rig = Rig().ready()
        rig.transport.play()
        rig.ctx.transitionPhase(PlayerPhase.PLAYING)
        val phases = EventLog().capture(rig.ctx, CoreEvents.Phase)

        rig.transport.seek(30.0)

        assertEquals(listOf(PlayerPhase.SEEKING, PlayerPhase.PLAYING), phases.map { it.to })
    }

    @Test
    fun seekingAStoppedPlayerDoesNotAnnounceAPhaseChange() = runTest {
        val rig = Rig().ready()
        val phases = EventLog().capture(rig.ctx, CoreEvents.Phase)

        assertTrue(rig.transport.seek(30.0))

        // A position change on a still player is not a state change, and
        // announcing one would flash a spinner over a static frame.
        assertEquals(emptyList(), phases.map { it.to })
        assertEquals(listOf(30.0), rig.backend.seekedTo)
    }
}
