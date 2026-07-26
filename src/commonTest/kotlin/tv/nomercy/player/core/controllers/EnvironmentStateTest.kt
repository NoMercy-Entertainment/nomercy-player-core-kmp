// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.player.CastState
import tv.nomercy.player.core.player.NetworkState
import tv.nomercy.player.core.player.VisibilityState
import tv.nomercy.player.core.ports.BackendState
import tv.nomercy.player.core.ports.EnvironmentMonitor
import kotlin.test.Test
import kotlin.test.assertEquals

// What the host's own observers would report.
private class StubEnvironment(
    var connectivity: NetworkState = NetworkState.ONLINE,
    var onScreen: VisibilityState = VisibilityState.VISIBLE,
) : EnvironmentMonitor {
    override fun network(): NetworkState = connectivity
    override fun visibility(): VisibilityState = onScreen
}

class EnvironmentStateTest {

    @Test
    fun withNoMonitorThePlayerAssumesOnlineAndVisible() {
        // Both defaults lean the same way for the same reason: the wrong guess
        // has to be the cheap one. Assuming offline would refuse to start on
        // every host that never wired a monitor up, and assuming hidden would
        // pause a player nobody told otherwise.
        val player = ComposedPlayer(backend = FakeMediaBackend())

        assertEquals(NetworkState.ONLINE, player.networkState())
        assertEquals(VisibilityState.VISIBLE, player.visibilityState())
    }

    @Test
    fun theMonitorIsAskedEveryTimeRatherThanCached() {
        // The whole point of it being a port: connectivity changes while the
        // process runs, and a cached answer is the state from whenever the
        // player last thought to ask.
        val environment = StubEnvironment()
        val player = ComposedPlayer(backend = FakeMediaBackend(), environment = environment)

        environment.connectivity = NetworkState.OFFLINE
        environment.onScreen = VisibilityState.HIDDEN

        assertEquals(NetworkState.OFFLINE, player.networkState())
        assertEquals(VisibilityState.HIDDEN, player.visibilityState())
    }

    @Test
    fun slowIsTheHostsJudgementNotTheLibrarys() {
        // "Slow" on a metered phone connection and on a TV's ethernet are
        // different thresholds, and only the app knows which it is on.
        val environment = StubEnvironment(connectivity = NetworkState.SLOW)
        val player = ComposedPlayer(backend = FakeMediaBackend(), environment = environment)

        assertEquals(NetworkState.SLOW, player.networkState())
    }

    @Test
    fun theStreamStateIsTheEnginesOwnAccount() {
        // Distinct from playState: a player that says PLAYING while its engine
        // says IDLE is a bug the two together name and neither does alone.
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        backend.stateValue = BackendState.LOADING

        assertEquals(BackendState.LOADING, player.streamState())
    }

    @Test
    fun aPlayerWithNoEngineReportsIdleRatherThanFailing() {
        assertEquals(BackendState.IDLE, ComposedPlayer().streamState())
    }

    @Test
    fun castingIsUnavailableUntilSomethingSaysOtherwise() {
        // Honest rather than pessimistic: no discovery has run, and core has no
        // business scanning a network to find out.
        assertEquals(CastState.UNAVAILABLE, ComposedPlayer(backend = FakeMediaBackend()).castState())
    }

    @Test
    fun whateverOwnsDiscoveryCanReportWhatItFound() {
        val player = ComposedPlayer(backend = FakeMediaBackend())
        val announced: MutableList<Any?> = mutableListOf()
        player.on(CoreEvents.CastState) { announced += it.state }

        player.castState(CastState.AVAILABLE)
        player.castState(CastState.CONNECTED)

        assertEquals(CastState.CONNECTED, player.castState())
        val expected: List<Any?> = listOf("available", "connected")
        assertEquals(expected, announced)
    }

    @Test
    fun reportingTheSameCastStateTwiceAnnouncesOnce() {
        // A discovery loop re-reporting on a timer would otherwise make a
        // chrome re-render for a value that did not change.
        val player = ComposedPlayer(backend = FakeMediaBackend())
        var announcements = 0
        player.on(CoreEvents.CastState) { announcements += 1 }

        player.castState(CastState.AVAILABLE)
        player.castState(CastState.AVAILABLE)

        assertEquals(1, announcements)
    }
}
