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
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.ports.BackendState
import tv.nomercy.player.core.ports.CapabilitiesProbe
import tv.nomercy.player.core.ports.NetworkMonitor
import tv.nomercy.player.core.ports.NetworkSnapshot
import tv.nomercy.player.core.ports.NetworkType
import tv.nomercy.player.core.ports.Platform
import tv.nomercy.player.core.ports.VisibilityMonitor
import tv.nomercy.player.core.ports.NoopWakeLock
import tv.nomercy.player.core.ports.PermissiveCapabilitiesProbe
import tv.nomercy.player.core.ports.WakeLock
import kotlin.test.Test
import kotlin.test.assertEquals
import tv.nomercy.player.testing.FakeMediaBackend

// What the host's own observers would report, over the real Platform port.
private class StubPlatform(
    var online: Boolean = true,
    var downlink: Double? = null,
    var onScreen: Boolean = true,
) : Platform {
    override val wakeLock: WakeLock = NoopWakeLock
    override val capabilities: CapabilitiesProbe = PermissiveCapabilitiesProbe

    override val network: NetworkMonitor = object : NetworkMonitor {
        override fun isOnline(): Boolean = online
        override fun type(): NetworkType = NetworkType.UNKNOWN
        override fun downlinkMbps(): Double? = downlink
        override fun rttMs(): Double? = null
        override fun subscribe(fn: (NetworkSnapshot) -> Unit): Subscription = Subscription {}
    }

    override val visibility: VisibilityMonitor = object : VisibilityMonitor {
        override fun isVisible(): Boolean = onScreen
        override fun subscribe(fn: (Boolean) -> Unit): Subscription = Subscription {}
    }
}

class EnvironmentStateTest {

    @Test
    fun theMonitorIsAskedEveryTimeRatherThanCached() {
        // The whole point of it being a port: connectivity changes while the
        // process runs, and a cached answer is the state from whenever the
        // player last thought to ask.
        val platform = StubPlatform()
        val player = ComposedPlayer(backend = FakeMediaBackend(), platform = platform)

        platform.online = false
        platform.onScreen = false

        assertEquals(NetworkState.OFFLINE, player.networkState())
        assertEquals(VisibilityState.HIDDEN, player.visibilityState())
    }

    @Test
    fun aConnectionTooThinForAStreamIsSlowRatherThanOnline() {
        val platform = StubPlatform(downlink = 0.8)
        val player = ComposedPlayer(backend = FakeMediaBackend(), platform = platform)

        assertEquals(NetworkState.SLOW, player.networkState())
    }

    @Test
    fun aPlatformThatCannotMeasureBandwidthIsNotTherebySlow() {
        // A desktop with no such API reports null. Treating that as slow would
        // leave it permanently degraded.
        val player = ComposedPlayer(backend = FakeMediaBackend(), platform = StubPlatform(downlink = null))

        assertEquals(NetworkState.ONLINE, player.networkState())
    }

    @Test
    fun offlineWinsOverSlow() {
        // A downlink figure from a connection that is gone is stale by
        // definition, and 'slow' would send a consumer down a retry path when
        // there is nothing to retry against.
        val player = ComposedPlayer(
            backend = FakeMediaBackend(),
            platform = StubPlatform(online = false, downlink = 0.5),
        )

        assertEquals(NetworkState.OFFLINE, player.networkState())
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
