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
import tv.nomercy.player.core.player.AudioTrackState
import tv.nomercy.player.core.ports.AudioOutput
import tv.nomercy.player.core.ports.AudioTrack
import tv.nomercy.player.core.ports.AudioOutputKind
import tv.nomercy.player.core.ports.AudioOutputRouter
import tv.nomercy.player.core.ports.CapabilitiesProbe
import tv.nomercy.player.core.ports.NetworkMonitor
import tv.nomercy.player.core.ports.NoopWakeLock
import tv.nomercy.player.core.ports.PermissiveCapabilitiesProbe
import tv.nomercy.player.core.ports.Platform
import tv.nomercy.player.core.ports.StaticNetworkMonitor
import tv.nomercy.player.core.ports.AlwaysVisible
import tv.nomercy.player.core.ports.VisibilityMonitor
import tv.nomercy.player.core.ports.WakeLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SPEAKER = AudioOutput("0", "Built-in speaker", AudioOutputKind.SPEAKER, isDefault = true)
private val EARBUDS = AudioOutput("17", "Earbuds", AudioOutputKind.BLUETOOTH)
private val OTHER_EARBUDS = AudioOutput("18", "Earbuds", AudioOutputKind.BLUETOOTH)

// A platform that can route audio, and one that cannot.
private class RoutingPlatform(private val router: AudioOutputRouter?) : Platform {
    override val wakeLock: WakeLock = NoopWakeLock
    override val network: NetworkMonitor = StaticNetworkMonitor()
    override val visibility: VisibilityMonitor = AlwaysVisible
    override val capabilities: CapabilitiesProbe = PermissiveCapabilitiesProbe
    override val audioOutput: AudioOutputRouter? = router
}

private class StubRouter(
    private val available: List<AudioOutput>,
    private val acceptsChoice: Boolean = true,
) : AudioOutputRouter {
    var active: AudioOutput? = available.firstOrNull { it.isDefault }
        private set

    override suspend fun outputs(): List<AudioOutput> = available
    override suspend fun current(): AudioOutput? = active

    override suspend fun select(id: String): Boolean {
        val chosen: AudioOutput = available.firstOrNull { it.id == id } ?: return false
        if (!acceptsChoice) return false
        active = chosen
        return true
    }
}

class AudioRoutingTest {

    private fun playerRouting(router: AudioOutputRouter?): ComposedPlayer =
        ComposedPlayer(backend = FakeMediaBackend(), platform = RoutingPlatform(router))

    @Test
    fun theOutputsAreWhateverThePlatformFound() = runTest {
        val player: ComposedPlayer = playerRouting(StubRouter(listOf(SPEAKER, EARBUDS)))

        assertEquals(listOf(SPEAKER, EARBUDS), player.audioOutputs())
        assertEquals(SPEAKER, player.audioOutput())
    }

    @Test
    fun choosingAnOutputMovesTheSound() = runTest {
        val router = StubRouter(listOf(SPEAKER, EARBUDS))
        val player: ComposedPlayer = playerRouting(router)

        assertTrue(player.selectAudioOutput("17"))

        assertEquals(EARBUDS, player.audioOutput())
    }

    @Test
    fun twoDevicesWithOneNameAreStillTwoDevices() = runTest {
        // Identical earbuds are the everyday case, and a router keying on the
        // name would send audio to whichever it found first.
        val router = StubRouter(listOf(SPEAKER, EARBUDS, OTHER_EARBUDS))
        val player: ComposedPlayer = playerRouting(router)

        player.selectAudioOutput("18")

        assertEquals(OTHER_EARBUDS, player.audioOutput())
        assertEquals("18", player.audioOutput()?.id)
    }

    @Test
    fun aPlatformThatRefusesTheChoiceSaysSoRatherThanThrowing() = runTest {
        // iOS routes audio by policy and treats a preference as a suggestion, so
        // 'your choice did not take' is an ordinary outcome rather than an
        // exceptional one.
        val player: ComposedPlayer = playerRouting(StubRouter(listOf(SPEAKER, EARBUDS), acceptsChoice = false))

        assertFalse(player.selectAudioOutput("17"))

        assertEquals(SPEAKER, player.audioOutput())
    }

    @Test
    fun choosingSomethingThatIsNotThereIsFalseNotACrash() = runTest {
        val player: ComposedPlayer = playerRouting(StubRouter(listOf(SPEAKER)))

        assertFalse(player.selectAudioOutput("a device that went away"))
    }

    @Test
    fun aPlatformThatCannotRouteAudioAnswersEmptyRatherThanFailing() = runTest {
        val player: ComposedPlayer = playerRouting(null)

        assertEquals(emptyList(), player.audioOutputs())
        assertNull(player.audioOutput())
        assertFalse(player.selectAudioOutput("17"))
    }

    @Test
    fun anAudioTrackStartsOutTheEnginesChoiceNotTheViewers() {
        // The distinction a chrome needs to decide whether to show a tick beside
        // a language: DEFAULT means the engine picked, which is usually the
        // item's first track and not a decision anyone made.
        assertEquals(AudioTrackState.DEFAULT, ComposedPlayer(backend = FakeMediaBackend()).audioTrackMode())
    }

    @Test
    fun choosingATrackMakesTheModeManualAndSaysSo() = runTest {
        val player = ComposedPlayer(backend = FakeMediaBackend())
        val announced: MutableList<Any?> = mutableListOf()
        player.on(CoreEvents.AudioTrackState) { announced += it.state }

        player.audioTrack(AudioTrack(id = "2", language = "nl", label = "Nederlands"))

        assertEquals(AudioTrackState.MANUAL, player.audioTrackMode())
        val expected: List<Any?> = listOf("manual")
        assertEquals(expected, announced)
    }
}
