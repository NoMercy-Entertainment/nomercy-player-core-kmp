// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.fakes

import tv.nomercy.player.core.ports.NowPlaying
import tv.nomercy.player.core.ports.SystemTransport
import tv.nomercy.player.core.ports.TransportActions
import tv.nomercy.player.core.ports.TransportPlaybackState

// An operating system that does what it is told and says what it was told.
//
// Standing in for four real ones. What a test can check here is the half of the
// conversation the plugin owns — what it pushes and when — because the other
// half is a lock screen, and nothing on a build machine has one of those.
class FakeSystemTransport : SystemTransport {

    var lastNowPlaying: NowPlaying? = null
        private set

    var lastState: TransportPlaybackState? = null
        private set

    var lastPositionMs: Long = -1
        private set

    var lastPlaybackRate: Double = -1.0
        private set

    var cleared: Boolean = false
        private set

    var released: Boolean = false
        private set

    // Every push in order, because the interesting failures are about ordering
    // and frequency rather than about any single value. A notification rebuilt
    // once a second is a radio that never sleeps, and only a sequence shows it.
    val pushes: MutableList<String> = mutableListOf()

    private var actions: TransportActions = TransportActions()

    override fun setNowPlaying(nowPlaying: NowPlaying) {
        lastNowPlaying = nowPlaying
        pushes += "nowPlaying:${nowPlaying.title}"
    }

    override fun setPlaybackState(
        state: TransportPlaybackState,
        positionMs: Long,
        playbackRate: Double,
    ) {
        lastState = state
        lastPositionMs = positionMs
        lastPlaybackRate = playbackRate
        pushes += "state:$state"
    }

    override fun setActionHandlers(actions: TransportActions) {
        this.actions = actions
        pushes += "actions"
    }

    override fun clear() {
        cleared = true
        pushes += "clear"
    }

    override fun release() {
        released = true
        pushes += "release"
    }

    // The other direction: a viewer pressing a button on something this process
    // does not own.
    fun simulateOsAction(action: String) {
        when (action) {
            "play" -> actions.onPlay
            "pause" -> actions.onPause
            "stop" -> actions.onStop
            "next" -> actions.onNext
            "previous" -> actions.onPrevious
            else -> null
        }?.invoke()
    }

    fun simulateOsSeek(positionMs: Long) {
        actions.onSeekTo?.invoke(positionMs)
    }

    // The interval buttons, which hand over how far rather than where to.
    fun simulateOsSkipForward(offsetMs: Long) {
        actions.onSkipForward?.invoke(offsetMs)
    }

    fun simulateOsSkipBackward(offsetMs: Long) {
        actions.onSkipBackward?.invoke(offsetMs)
    }

    // What the system would draw. A platform hides a control it has no handler
    // for, so this is the difference between a lock screen with a next button
    // and one without.
    fun offeredActions(): Set<String> = mapOf<String, Any?>(
        "play" to actions.onPlay,
        "pause" to actions.onPause,
        "stop" to actions.onStop,
        "next" to actions.onNext,
        "previous" to actions.onPrevious,
        "seekTo" to actions.onSeekTo,
        "skipBackward" to actions.onSkipBackward,
        "skipForward" to actions.onSkipForward,
    ).filterValues { handler -> handler != null }.keys
}
