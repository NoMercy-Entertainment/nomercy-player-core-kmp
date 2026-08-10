// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.controllers.ComposedPlayer
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.ports.FocusChange
import tv.nomercy.player.core.ports.FocusLossKind
import tv.nomercy.player.core.ports.fakes.FakeAudioFocusPort
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The plugin over the real event bus, the same shape MediaSessionPluginTest
// and PlayerTransportCommandsTest use and for the same reason: the mapping
// from a core event to a focus decision is the whole plugin, and a fake bus
// would let a renamed event pass silently.
//
// Wired via [audioFocusPlugin] against a real [ComposedPlayer] rather than
// recording lambdas for pause/resume — the property under test is
// specifically whether this plugin's own pause()/play() calls, round-tripping
// through the real player and back onto CoreEvents, are told apart from the
// viewer's. A pair of plain recording lambdas would not exercise that round
// trip at all.
class AudioFocusPluginTest {

    // Runs what is launched into it straight away — audioFocusPlugin's pause
    // and resume launch rather than await, on purpose, so this is what lets a
    // test read the result on the next line rather than pumping a scheduler.
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private class Wiring(
        val player: ComposedPlayer,
        val port: FakeAudioFocusPort,
        val duckGains: MutableList<Float>,
        val playCount: MutableList<Unit>,
        val pauseCount: MutableList<Unit>,
    )

    private suspend fun TestScope.wire(): Wiring {
        val port = FakeAudioFocusPort()
        val duckGains = mutableListOf<Float>()
        val playCount = mutableListOf<Unit>()
        val pauseCount = mutableListOf<Unit>()
        val player = ComposedPlayer(backend = FakeMediaBackend(), scope = backgroundScope)
        player.setup(PlayerConfig())
        player.queue(listOf(TestItem("a")))
        player.on(CoreEvents.Play) { playCount += Unit }
        player.on(CoreEvents.Pause) { pauseCount += Unit }
        player.addPlugin(audioFocusPlugin(player, eager(), duck = { duckGains += it }, openPort = { port }))
        return Wiring(player, port, duckGains, playCount, pauseCount)
    }

    @Test
    fun playingRequestsFocus() = runTest {
        val wiring: Wiring = wire()

        wiring.player.emit(CoreEvents.Play, PlaySource())

        assertTrue(wiring.port.requested, "playing never asked the OS for focus")
    }

    @Test
    fun aTransientLossPausesThePlayerItself() = runTest {
        val wiring: Wiring = wire()
        wiring.player.emit(CoreEvents.Play, PlaySource())

        wiring.port.simulate(FocusChange.Lost(FocusLossKind.TRANSIENT))

        assertEquals(1, wiring.pauseCount.size, "the transient loss never reached the player's own pause")
    }

    @Test
    fun aGrantResumesAPauseThisPluginIssuedForTheLoss() = runTest {
        val wiring: Wiring = wire()
        wiring.player.emit(CoreEvents.Play, PlaySource())
        wiring.port.simulate(FocusChange.Lost(FocusLossKind.TRANSIENT))

        wiring.port.simulate(FocusChange.Gained)

        assertEquals(2, wiring.playCount.size, "the grant never resumed the pause this plugin issued")
    }

    @Test
    fun aRealUserPauseIsNeverResumedByAGrant() = runTest {
        val wiring: Wiring = wire()
        wiring.player.emit(CoreEvents.Play, PlaySource())
        wiring.port.simulate(FocusChange.Lost(FocusLossKind.TRANSIENT))

        // The viewer's own tap, arriving through the same event this plugin's
        // own pause fires — the tag on ActionOptions is what tells them apart.
        wiring.player.emit(CoreEvents.Pause, PlaySource(source = "user"))
        wiring.port.simulate(FocusChange.Gained)

        assertEquals(1, wiring.playCount.size, "a grant resumed a pause the viewer asked for")
    }

    @Test
    fun aDuckCapableLossReachesTheVolumeSetterRatherThanPausing() = runTest {
        val wiring: Wiring = wire()
        wiring.player.emit(CoreEvents.Play, PlaySource())

        wiring.port.simulate(FocusChange.Lost(FocusLossKind.TRANSIENT_CAN_DUCK))

        assertEquals(0, wiring.pauseCount.size, "a duckable loss paused the player")
        assertEquals(listOf(0.2f), wiring.duckGains)
    }

    @Test
    fun aPermanentLossAbandonsFocusRatherThanWaitingForAGrantThatWillNotCome() = runTest {
        val wiring: Wiring = wire()
        wiring.player.emit(CoreEvents.Play, PlaySource())

        wiring.port.simulate(FocusChange.Lost(FocusLossKind.PERMANENT))

        assertEquals(1, wiring.pauseCount.size)
        assertTrue(wiring.port.abandoned, "a permanent loss kept the focus request alive")
    }

    @Test
    fun stoppingAbandonsFocus() = runTest {
        val wiring: Wiring = wire()
        wiring.player.emit(CoreEvents.Play, PlaySource())

        wiring.player.emit(CoreEvents.Stop, PlaySource())

        assertTrue(wiring.port.abandoned, "a deliberate stop left the focus request open")
    }
}
