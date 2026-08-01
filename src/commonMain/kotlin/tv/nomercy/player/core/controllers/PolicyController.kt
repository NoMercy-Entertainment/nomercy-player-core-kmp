// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.NetworkSlowPayload
import tv.nomercy.player.core.events.PhaseChange
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.ActionSource
import tv.nomercy.player.core.player.OfflinePolicy
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.player.WakeLockPolicy
import tv.nomercy.player.core.ports.Platform

// Under this and a 1080p rung is not going to hold. The web player's number,
// kept so a consumer moving between them sees the same badge at the same moment.
private const val SLOW_DOWNLINK_MBPS = 1.5

// The three host-settable options that answer to the world outside the player:
// the screen blanking, the surface going away, and the connection dropping.
//
// They were three fields a host could set and nothing read, which is worse than
// three absent options — a host that set `pauseWhenHidden` believed it took
// effect. Each one wires at setup and unwires at dispose, and the subscription
// handles are held here rather than in the host, because a policy that outlives
// the player it was set on is a leak whose cause is invisible.
public class PolicyController(
    private val ctx: PlayerContext,
    private val platform: Platform,
    private val transport: TransportController,
    private val scope: CoroutineScope,
) {

    private val subscriptions: MutableList<Subscription> = mutableListOf()

    // Whether the last reading was already slow, so `network:slow` announces the
    // crossing rather than every reading taken while it stays slow.
    private var wasNetworkSlow: Boolean = false

    // The same order the reference wires them in: visibility, network, wake
    // lock. Visibility and network can both pause, and the one registered first
    // is the one a listener sees first.
    public fun setup(config: PlayerConfig) {
        wireVisibility(config.pauseWhenHidden)
        wireNetwork(config.onOffline)
        wireWakeLock(config.wakeLock)
    }

    public fun dispose() {
        for (subscription in subscriptions) subscription.dispose()
        subscriptions.clear()
        wasNetworkSlow = false
        if (platform.wakeLock.isHeld()) releaseWakeLock()
    }

    // Off by default, and it has to be: a music player is expected to keep
    // playing with the screen off, and pausing there would be the library
    // deciding something the host did not ask for.
    private fun wireVisibility(pauseWhenHidden: Boolean) {
        if (!pauseWhenHidden) return

        subscriptions += platform.visibility.subscribe { visible ->
            if (!visible) pausePlatform()
            ctx.emit(if (visible) CoreEvents.VisibilityVisible else CoreEvents.VisibilityHidden, Unit)
        }
    }

    // IGNORE does not subscribe at all, rather than subscribing and dropping the
    // reading. A host that turned connectivity handling off should not be paying
    // for a monitor it asked not to have.
    private fun wireNetwork(onOffline: OfflinePolicy) {
        if (onOffline == OfflinePolicy.IGNORE) return

        subscriptions += platform.network.subscribe { snapshot ->
            if (snapshot.online) announceOnline() else announceOffline(onOffline)
        }
    }

    private fun announceOnline() {
        ctx.emit(CoreEvents.NetworkOnline, Unit)

        val isSlowNow: Boolean = isSlow(platform.network.downlinkMbps())
        if (isSlowNow && !wasNetworkSlow) {
            ctx.emit(CoreEvents.NetworkSlow, NetworkSlowPayload(platform.network.rttMs()?.toString().orEmpty()))
        }
        wasNetworkSlow = isSlowNow
    }

    private fun announceOffline(onOffline: OfflinePolicy) {
        wasNetworkSlow = false
        ctx.emit(CoreEvents.NetworkOffline, Unit)
        if (onOffline == OfflinePolicy.PAUSE) pausePlatform()
    }

    // Null means the platform cannot measure it, which is not the same as slow:
    // a desktop with no such API would otherwise be permanently degraded.
    private fun isSlow(downlinkMbps: Double?): Boolean =
        downlinkMbps != null && downlinkMbps > 0.0 && downlinkMbps < SLOW_DOWNLINK_MBPS

    // ALWAYS holds one for the whole session and AUTO follows playback. Both
    // swallow failures: a wake lock is best-effort everywhere, and a player that
    // failed to start because a screen-power API said no would be trading a
    // dimming screen for a black one.
    private fun wireWakeLock(policy: WakeLockPolicy) {
        when (policy) {
            WakeLockPolicy.ALWAYS -> acquireWakeLock()
            WakeLockPolicy.AUTO -> subscriptions += ctx.on(CoreEvents.Phase, ::followPhase)
            WakeLockPolicy.NEVER -> Unit
        }
    }

    private fun followPhase(change: PhaseChange) {
        if (change.to in HOLDING_PHASES) {
            if (!platform.wakeLock.isHeld()) acquireWakeLock()
            return
        }
        if (change.to in RELEASING_PHASES && platform.wakeLock.isHeld()) releaseWakeLock()
    }

    private fun acquireWakeLock() {
        scope.launch { platform.wakeLock.acquire() }
    }

    private fun releaseWakeLock() {
        scope.launch { platform.wakeLock.release() }
    }

    // Named PLATFORM, because this one knows the answer. An unsourced pause
    // reaches a listener as "nobody knows who asked", and a plugin dimming a
    // lamp on a viewer's pause would dim it for a phone going into a pocket.
    private fun pausePlatform() {
        scope.launch { transport.pause(ActionOptions(source = ActionSource.PLATFORM)) }
    }

    private companion object {
        val HOLDING_PHASES: Set<PlayerPhase> = setOf(PlayerPhase.PLAYING, PlayerPhase.STARTING)

        val RELEASING_PHASES: Set<PlayerPhase> = setOf(
            PlayerPhase.PAUSED,
            PlayerPhase.STOPPED,
            PlayerPhase.ENDED,
            PlayerPhase.DISPOSED,
        )
    }
}
