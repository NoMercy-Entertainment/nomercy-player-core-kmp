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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.player.OfflinePolicy
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.player.WakeLockPolicy
import tv.nomercy.player.core.ports.CapabilitiesProbe
import tv.nomercy.player.core.ports.NetworkMonitor
import tv.nomercy.player.core.ports.NetworkSnapshot
import tv.nomercy.player.core.ports.NetworkType
import tv.nomercy.player.core.ports.PermissiveCapabilitiesProbe
import tv.nomercy.player.core.ports.Platform
import tv.nomercy.player.core.ports.VisibilityMonitor
import tv.nomercy.player.core.ports.WakeLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem

// A wake lock a test can watch. The shipped ones are all NoopWakeLock, which is
// exactly why nothing noticed that the policy was never wired.
private class RecordingWakeLock : WakeLock {
    var held: Boolean = false
        private set
    var acquisitions: Int = 0
        private set

    override suspend fun acquire() {
        held = true
        acquisitions += 1
    }

    override suspend fun release() {
        held = false
    }

    override fun isHeld(): Boolean = held
    override fun isSupported(): Boolean = true
}

// Monitors a test drives, over the real ports.
private class DrivablePlatform : Platform {
    override val wakeLock: RecordingWakeLock = RecordingWakeLock()
    override val capabilities: CapabilitiesProbe = PermissiveCapabilitiesProbe

    var downlink: Double? = null
    var rtt: Double? = null

    private val networkListeners: MutableList<(NetworkSnapshot) -> Unit> = mutableListOf()
    private val visibilityListeners: MutableList<(Boolean) -> Unit> = mutableListOf()

    override val network: NetworkMonitor = object : NetworkMonitor {
        override fun isOnline(): Boolean = true
        override fun type(): NetworkType = NetworkType.UNKNOWN
        override fun downlinkMbps(): Double? = downlink
        override fun rttMs(): Double? = rtt
        override fun subscribe(fn: (NetworkSnapshot) -> Unit): Subscription {
            networkListeners += fn
            return Subscription { networkListeners -= fn }
        }
    }

    override val visibility: VisibilityMonitor = object : VisibilityMonitor {
        override fun isVisible(): Boolean = true
        override fun subscribe(fn: (Boolean) -> Unit): Subscription {
            visibilityListeners += fn
            return Subscription { visibilityListeners -= fn }
        }
    }

    fun goOffline(): Unit = networkListeners.toList().forEach { it(NetworkSnapshot(false, NetworkType.NONE)) }
    fun goOnline(): Unit = networkListeners.toList().forEach { it(NetworkSnapshot(true, NetworkType.WIFI)) }
    fun hide(): Unit = visibilityListeners.toList().forEach { it(false) }
    fun show(): Unit = visibilityListeners.toList().forEach { it(true) }

    fun subscriberCount(): Int = networkListeners.size + visibilityListeners.size
}

// wakeLock, pauseWhenHidden and onOffline were three fields a host could set and
// nothing read. Each test here fails outright without the wiring, because
// without it the port is never touched and the event never fires.
class PolicyControllerTest {

    // On the test scope, because the policies answer a monitor callback that
    // cannot suspend and hand the suspending half to the player's scope. On the
    // default scope that work lands on another thread and an assertion taken
    // straight afterwards is a race the test would win most of the time.
    private suspend fun TestScope.playing(
        platform: DrivablePlatform,
        config: PlayerConfig,
    ): ComposedPlayer {
        val player = ComposedPlayer(backend = FakeMediaBackend(), platform = platform, scope = backgroundScope)
        player.queue(listOf(TestItem("a")))
        player.setup(config)
        player.play()
        runCurrent()
        return player
    }

    private fun TestScope.idle(platform: DrivablePlatform, config: PlayerConfig): ComposedPlayer =
        ComposedPlayer(backend = FakeMediaBackend(), platform = platform, scope = backgroundScope)
            .also { runBlockingSetup(it, config) }

    private fun TestScope.runBlockingSetup(player: ComposedPlayer, config: PlayerConfig) {
        backgroundScope.launch { player.setup(config) }
        runCurrent()
    }

    @Test
    fun theScreenIsHeldAwakeWhilePlaybackRuns() = runTest {
        val platform = DrivablePlatform()

        playing(platform, PlayerConfig(wakeLock = WakeLockPolicy.AUTO))

        assertTrue(platform.wakeLock.isHeld(), "AUTO must acquire when the phase reaches playing")
    }

    @Test
    fun pausingLetsTheScreenSleepAgain() = runTest {
        val platform = DrivablePlatform()
        val player = playing(platform, PlayerConfig(wakeLock = WakeLockPolicy.AUTO))

        player.pause()
        runCurrent()

        assertFalse(platform.wakeLock.isHeld())
    }

    @Test
    fun neverMeansNever() = runTest {
        val platform = DrivablePlatform()

        playing(platform, PlayerConfig(wakeLock = WakeLockPolicy.NEVER))

        assertEquals(0, platform.wakeLock.acquisitions)
    }

    @Test
    fun alwaysHoldsItFromSetupRatherThanFromPlayback() = runTest {
        val platform = DrivablePlatform()

        idle(platform, PlayerConfig(wakeLock = WakeLockPolicy.ALWAYS))

        assertTrue(platform.wakeLock.isHeld())
    }

    @Test
    fun disposingReleasesAHeldLock() = runTest {
        val platform = DrivablePlatform()
        val player = idle(platform, PlayerConfig(wakeLock = WakeLockPolicy.ALWAYS))

        player.dispose()
        runCurrent()

        assertFalse(platform.wakeLock.isHeld(), "a lock surviving the player is a screen that never sleeps again")
    }

    @Test
    fun theSurfaceGoingAwayPausesWhenTheHostAskedForThat() = runTest {
        val platform = DrivablePlatform()
        val player = playing(platform, PlayerConfig(pauseWhenHidden = true))

        platform.hide()
        runCurrent()

        assertEquals(PlayState.PAUSED, player.playState())
    }

    @Test
    fun theSurfaceGoingAwayIsAnnouncedEitherWay() = runTest {
        val platform = DrivablePlatform()
        val player = playing(platform, PlayerConfig(pauseWhenHidden = true))
        val seen: MutableList<String> = mutableListOf()
        player.on(CoreEvents.VisibilityHidden) { seen += "hidden" }
        player.on(CoreEvents.VisibilityVisible) { seen += "visible" }

        platform.hide()
        platform.show()
        runCurrent()

        assertEquals(listOf("hidden", "visible"), seen)
    }

    @Test
    fun aMusicPlayerKeepsPlayingWithTheScreenOff() = runTest {
        // The default. Pausing here would be the library deciding something the
        // host did not ask for.
        val platform = DrivablePlatform()
        val player = playing(platform, PlayerConfig())

        platform.hide()
        runCurrent()

        assertEquals(PlayState.PLAYING, player.playState())
    }

    @Test
    fun losingTheConnectionPausesUnderThePausePolicy() = runTest {
        val platform = DrivablePlatform()
        val player = playing(platform, PlayerConfig(onOffline = OfflinePolicy.PAUSE))

        platform.goOffline()
        runCurrent()

        assertEquals(PlayState.PAUSED, player.playState())
    }

    @Test
    fun theDefaultPolicyKeepsPlayingWhatIsAlreadyBuffered() = runTest {
        val platform = DrivablePlatform()
        val player = playing(platform, PlayerConfig(onOffline = OfflinePolicy.CONTINUE_BUFFERED))
        var offline = 0
        player.on(CoreEvents.NetworkOffline) { offline += 1 }

        platform.goOffline()
        runCurrent()

        assertEquals(PlayState.PLAYING, player.playState(), "pausing on a two-second dropout is worse than the dropout")
        assertEquals(1, offline, "the drop is still worth saying out loud")
    }

    @Test
    fun ignoreDoesNotEvenSubscribe() = runTest {
        val platform = DrivablePlatform()

        idle(platform, PlayerConfig(onOffline = OfflinePolicy.IGNORE))

        assertEquals(0, platform.subscriberCount())
    }

    @Test
    fun aThinConnectionIsAnnouncedOnceRatherThanOnEveryReading() = runTest {
        val platform = DrivablePlatform()
        platform.downlink = 0.8
        val player = playing(platform, PlayerConfig())
        var slow = 0
        player.on(CoreEvents.NetworkSlow) { slow += 1 }

        platform.goOnline()
        platform.goOnline()

        assertEquals(1, slow)
    }

    @Test
    fun comingBackUpToSpeedAndDroppingAgainAnnouncesTwice() = runTest {
        val platform = DrivablePlatform()
        platform.downlink = 0.8
        val player = playing(platform, PlayerConfig())
        var slow = 0
        player.on(CoreEvents.NetworkSlow) { slow += 1 }

        platform.goOnline()
        platform.downlink = 20.0
        platform.goOnline()
        platform.downlink = 0.5
        platform.goOnline()

        assertEquals(2, slow)
    }

    @Test
    fun disposingUnsubscribesEveryMonitor() = runTest {
        // A policy that outlives the player it was set on is a leak whose cause
        // is invisible: the monitor is the host's and keeps firing.
        val platform = DrivablePlatform()
        val player = idle(platform, PlayerConfig(pauseWhenHidden = true, onOffline = OfflinePolicy.PAUSE))

        player.dispose()
        runCurrent()

        assertEquals(0, platform.subscriberCount())
    }
}
