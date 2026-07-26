// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.player.PlayerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val INACTIVITY_MS = 4_000L

// The autohide rule, which is one event and four conditions.
//
// A chrome binds to `activity { active }` and shows or fades itself. Keeping
// the rule in core is what makes a Compose overlay, a tvOS one and a web one
// agree about when controls disappear — and it is why these assertions are on
// the emitted sequence rather than on anything visual.
class ActivityControllerTest {

    // The countdown runs on the player's scope, so the test has to own it —
    // otherwise advanceTimeBy moves a clock the delay is not waiting on and
    // every assertion here passes for the wrong reason.
    private fun TestScope.player(): ComposedPlayer =
        ComposedPlayer(backend = FakeMediaBackend(), scope = backgroundScope)

    private fun record(player: ComposedPlayer): MutableList<Boolean> {
        val states: MutableList<Boolean> = mutableListOf()
        player.on(CoreEvents.Activity) { states += it.active }
        return states
    }

    @Test
    fun startingAPlayerShowsTheControls() = runTest {
        // The viewer just started a player. That is activity, and controls that
        // began hidden would make the first thing they see a bare video.
        val player: ComposedPlayer = player()
        val states: MutableList<Boolean> = record(player)

        player.setup(PlayerConfig(inactivityMs = INACTIVITY_MS))

        assertEquals(listOf(true), states)
    }

    @Test
    fun theControlsFadeWhileSomethingIsPlaying() = runTest {
        val player: ComposedPlayer = player()
        val states: MutableList<Boolean> = record(player)
        player.setup(PlayerConfig(inactivityMs = INACTIVITY_MS))
        player.queue(listOf(TestItem("a")))
        player.play()

        advanceTimeBy(INACTIVITY_MS + 1)
        runCurrent()

        assertEquals(listOf(true, false), states)
    }

    @Test
    fun theControlsStayUpWhilePaused() = runTest {
        // A viewer who paused is looking at the controls. Fading them is how a
        // player loses an argument with someone trying to read a chapter title.
        val player: ComposedPlayer = player()
        player.setup(PlayerConfig(inactivityMs = INACTIVITY_MS))
        player.queue(listOf(TestItem("a")))
        player.play()
        player.pause()
        player.bumpActivity()
        val states: MutableList<Boolean> = record(player)
        runCurrent()

        advanceTimeBy(INACTIVITY_MS * 10)
        runCurrent()

        assertEquals(emptyList(), states, "the controls faded while playback was paused")
    }

    @Test
    fun everyBumpRestartsTheCountdownRatherThanShorteningIt() = runTest {
        val player: ComposedPlayer = player()
        player.setup(PlayerConfig(inactivityMs = INACTIVITY_MS))
        player.queue(listOf(TestItem("a")))
        player.play()
        player.bumpActivity()
        val states: MutableList<Boolean> = record(player)

        // Three quarters through, then again: a viewer moving the pointer must
        // not have the controls fade because the first countdown was still
        // running.
        advanceTimeBy(INACTIVITY_MS * 3 / 4)
        player.bumpActivity()
        advanceTimeBy(INACTIVITY_MS * 3 / 4)
        runCurrent()

        assertEquals(emptyList(), states, "the controls faded before a full window of inactivity")
    }

    @Test
    fun repeatedBumpsAnnounceOnce() = runTest {
        // A chrome re-rendering on every pointer move would repaint sixty times
        // a second for a value that did not change.
        val player: ComposedPlayer = player()
        player.setup(PlayerConfig(inactivityMs = INACTIVITY_MS))
        val states: MutableList<Boolean> = record(player)

        repeat(10) { player.bumpActivity() }

        assertEquals(emptyList(), states, "an unchanged active state was announced again")
    }

    @Test
    fun resumingPlaybackBringsTheControlsBackAndRearmsTheCountdown() = runTest {
        // The bug this prevents: controls shown during a pause stay up for the
        // rest of the film. The countdown expired harmlessly while paused, and
        // without this nothing arms it again — including when the resume came
        // from a headphone button no UI saw.
        val player: ComposedPlayer = player()
        player.setup(PlayerConfig(inactivityMs = INACTIVITY_MS))
        player.queue(listOf(TestItem("a")))
        player.play()
        advanceTimeBy(INACTIVITY_MS + 1)
        runCurrent()
        val states: MutableList<Boolean> = record(player)

        player.pause()
        player.play()
        runCurrent()

        assertEquals(listOf(true), states, "resuming did not bring the controls back")

        advanceTimeBy(INACTIVITY_MS + 1)
        runCurrent()

        assertEquals(listOf(true, false), states, "resuming did not re-arm the countdown")
    }

    @Test
    fun aChromeCanTakeOverAndTheBuiltInTrackerStandsDown() = runTest {
        // A chrome with its own state machine — one that keeps controls up
        // while a menu is open — becomes the sole emitter.
        val player: ComposedPlayer = player()
        player.setup(PlayerConfig(inactivityMs = INACTIVITY_MS))
        player.queue(listOf(TestItem("a")))
        player.play()
        player.bumpActivity()
        val states: MutableList<Boolean> = record(player)

        player.activityTracking(false)
        advanceTimeBy(INACTIVITY_MS * 10)
        runCurrent()

        assertFalse(player.activityTracking())
        assertEquals(emptyList(), states, "an armed countdown fired after the chrome took over")
        player.bumpActivity()
        assertEquals(emptyList(), states, "a bump was honoured after tracking was turned off")
    }

    @Test
    fun zeroInactivityTurnsTheTrackerOffEntirely() = runTest {
        // A TV chrome that never autohides, or a kiosk. Zero means off rather
        // than "fade immediately", which would hide the controls on the frame
        // they appeared.
        val player: ComposedPlayer = player()
        val states: MutableList<Boolean> = record(player)

        player.setup(PlayerConfig(inactivityMs = 0L))

        assertFalse(player.activityTracking())
        assertEquals(emptyList(), states)
    }

    @Test
    fun turningTrackingBackOnCannotOverrideZero() = runTest {
        // The config said this player never autohides. A chrome flipping the
        // switch should not resurrect a countdown that has no window to run.
        val player: ComposedPlayer = player()
        player.setup(PlayerConfig(inactivityMs = 0L))

        player.activityTracking(true)

        assertFalse(player.activityTracking())
    }

    @Test
    fun aChromeThatTookOverCanHandItBack() = runTest {
        val player: ComposedPlayer = player()
        player.setup(PlayerConfig(inactivityMs = INACTIVITY_MS))
        player.activityTracking(false)

        player.activityTracking(true)

        assertTrue(player.activityTracking())
    }
}
