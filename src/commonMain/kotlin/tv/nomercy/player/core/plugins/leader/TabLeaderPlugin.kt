// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.leader

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.core.plugin.TransportCommands
import tv.nomercy.player.core.plugin.VolumeCommands
import tv.nomercy.player.core.ports.InProcessLeaderLock
import tv.nomercy.player.core.ports.LeaderLock
import tv.nomercy.player.core.ports.LeaderLockRequest

public enum class LeaderLostAction {
    // Stop. The default, and what a viewer expects when they start something
    // else playing.
    PAUSE,

    // Keep going silently. For a player that should stay buffered and ready to
    // take over again.
    MUTE,
}

public data class TabLeaderOptions(
    val onLost: LeaderLostAction = LeaderLostAction.PAUSE,

    // Whether returning to the screen tries to take leadership back. The web
    // plugin listens to `visibilitychange`; here it is the same core event the
    // rest of the library reacts to.
    val handoffOnVisible: Boolean = true,

    // Null takes [DEFAULT_LEADER_LOCK_KEY], which every player in the app
    // shares — one plays at a time, everywhere. Name a key to scope leadership
    // to a screen, a server or a content item instead.
    val lockKey: String? = null,
)

/**
 * One player at a time.
 *
 * The same `tab-leader` id the web plugin has, and the same shape: this player
 * queues for a named lock, plays while it holds it, and does what
 * [TabLeaderOptions.onLost] says when it gives it up. Whoever is next in the
 * queue is handed the lock the moment it is released.
 *
 * What "at a time" spans is the [LeaderLock]'s business, not this plugin's. The
 * web elects across browser tabs because that is the scope a browser can offer;
 * the default here is [InProcessLeaderLock], which elects among the players in
 * one app. A consumer on a platform that arbitrates centrally — Android's audio
 * focus — implements the port over that and gets election across apps with no
 * change here.
 *
 * A null lock is the web plugin's unsupported branch: [TabLeaderEvents.Unsupported]
 * goes out once and the plugin does nothing else. Playback is never blocked by
 * a leadership scheme that could not run.
 */
public open class TabLeaderPlugin(
    private val commands: TransportCommands,
    private val volume: VolumeCommands,
    private val lock: LeaderLock? = InProcessLeaderLock.Shared,
    private val opts: TabLeaderOptions = TabLeaderOptions(),
) : Plugin<TabLeaderOptions>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "tab-leader"

        // Two, matching the web plugin this mirrors.
        override val version: String = "2.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    override val options: TabLeaderOptions get() = opts

    private var leader: Boolean = false
    private var pending: LeaderLockRequest? = null

    /** Whether this player currently holds the lock. */
    public fun isLeader(): Boolean = leader

    override fun use() {
        if (lock == null) {
            emit(TabLeaderEvents.Unsupported, Unit)
            return
        }

        if (opts.handoffOnVisible) {
            on(CoreEvents.VisibilityVisible) { requestLock() }
        }

        requestLock()
    }

    /**
     * Ask for the lock, or keep the request already outstanding.
     *
     * Calling it while a request is in flight does nothing rather than starting
     * a second one: two places in the same queue would leave the loser holding
     * a request nobody releases, and the queue behind it never moves again.
     */
    public fun requestLock() {
        val authority: LeaderLock = lock ?: run {
            emit(TabLeaderEvents.Unsupported, Unit)
            return
        }
        if (pending != null) return

        pending = authority.request(lockKey()) { acquired() }
    }

    /**
     * Give the lock up so whoever is next can have it, and do what
     * [TabLeaderOptions.onLost] says.
     *
     * The action only runs if this player was actually the leader. Withdrawing
     * a request that never came up is not losing anything, and pausing for it
     * would stop a player that had been told to wait its turn and had.
     */
    public fun releaseLock() {
        val request: LeaderLockRequest = pending ?: return
        val wasLeader: Boolean = leader

        pending = null
        leader = false
        request.release()

        if (!wasLeader) return

        emit(TabLeaderEvents.LeaderReleased, Unit)
        when (opts.onLost) {
            LeaderLostAction.MUTE -> volume.mute()
            LeaderLostAction.PAUSE -> commands.pause()
        }
    }

    /**
     * The key this player queues on.
     *
     * Open so a consumer can derive it from something the options cannot hold —
     * the server the item came from, the account watching — without every
     * caller having to pass a function in.
     */
    protected open fun lockKey(): String = opts.lockKey ?: DEFAULT_LEADER_LOCK_KEY

    override fun dispose() {
        releaseLock()
    }

    private fun acquired() {
        leader = true
        emit(TabLeaderEvents.LeaderAcquired, Unit)
    }
}

/** Shared by every player in the app, so one plays at a time. */
public const val DEFAULT_LEADER_LOCK_KEY: String = "nomercy-player-leader"
