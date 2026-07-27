// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNumber
import platform.Foundation.numberWithDouble
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPNowPlayingPlaybackStateStopped
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess

// The iOS and tvOS lock screen, control centre and car display.
//
// Straight cinterop, no Swift in the middle, and that was the open question:
// MPRemoteCommandCenter hands out its targets through blocks that return a
// status, and MPNowPlayingInfoCenter wants a heterogeneous dictionary keyed by
// framework constants. Both bridge from Kotlin without help, so there is no
// bridge to keep in step with this file.
//
// The command centre is a process-wide singleton, unlike Android's session. That
// is Apple's design and it means the same one-owner rule applies for a different
// reason: two players adding targets both receive every button.
@OptIn(ExperimentalForeignApi::class)
internal class AppleSystemTransport : SystemTransport {

    private val center: MPNowPlayingInfoCenter = MPNowPlayingInfoCenter.defaultCenter()

    private val commands: MPRemoteCommandCenter = MPRemoteCommandCenter.sharedCommandCenter()

    private var actions: TransportActions = TransportActions()

    // What was last announced, kept because the centre takes the whole
    // dictionary every time. Pushing a state without it would blank the title.
    private var info: MutableMap<Any?, Any?> = mutableMapOf()

    // The targets this transport added, so release removes exactly its own.
    // removeTarget(null) would take out every target on the command, including
    // any an embedding application added for itself.
    private val targets: MutableList<Pair<platform.MediaPlayer.MPRemoteCommand, Any>> = mutableListOf()

    override fun setNowPlaying(nowPlaying: NowPlaying) {
        info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to nowPlaying.title,
        )
        nowPlaying.artist?.let { info[MPMediaItemPropertyArtist] = it }
        nowPlaying.album?.let { info[MPMediaItemPropertyAlbumTitle] = it }
        if (nowPlaying.durationMs > 0) {
            info[MPMediaItemPropertyPlaybackDuration] =
                NSNumber.numberWithDouble(nowPlaying.durationMs / MILLIS_PER_SECOND)
        }
        center.setNowPlayingInfo(info)
    }

    override fun setPlaybackState(
        state: TransportPlaybackState,
        positionMs: Long,
        playbackRate: Double,
    ) {
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] =
            NSNumber.numberWithDouble(positionMs / MILLIS_PER_SECOND)

        // Rate is how the lock screen knows to run its own clock on. Zero while
        // paused, or the scrubber keeps travelling with nothing playing.
        info[MPNowPlayingInfoPropertyPlaybackRate] = NSNumber.numberWithDouble(
            if (state == TransportPlaybackState.PLAYING) playbackRate else 0.0,
        )
        center.setNowPlayingInfo(info)
        center.setPlaybackState(
            when (state) {
                TransportPlaybackState.PLAYING -> MPNowPlayingPlaybackStatePlaying
                TransportPlaybackState.PAUSED -> MPNowPlayingPlaybackStatePaused
                TransportPlaybackState.STOPPED -> MPNowPlayingPlaybackStateStopped
            },
        )
    }

    override fun setActionHandlers(actions: TransportActions) {
        this.actions = actions
        removeTargets()

        // Enabled only where there is a handler. A command left enabled with
        // nothing behind it draws its button on the lock screen and does nothing
        // when pressed, and on CarPlay it is worse: the system refuses a command
        // it advertised and could not service.
        add(commands.playCommand, actions.onPlay)
        add(commands.pauseCommand, actions.onPause)
        add(commands.stopCommand, actions.onStop)
        add(commands.nextTrackCommand, actions.onNext)
        add(commands.previousTrackCommand, actions.onPrevious)
        addSeek(actions.onSeekTo)
    }

    override fun clear() {
        info = mutableMapOf()
        center.setNowPlayingInfo(null)
        center.setPlaybackState(MPNowPlayingPlaybackStateStopped)
    }

    override fun release() {
        removeTargets()
        clear()
    }

    private fun add(command: platform.MediaPlayer.MPRemoteCommand, handler: (() -> Unit)?) {
        command.enabled = handler != null
        if (handler == null) return

        val target: Any = command.addTargetWithHandler { _ ->
            handler()
            MPRemoteCommandHandlerStatusSuccess
        }
        targets += command to target
    }

    private fun addSeek(handler: ((Long) -> Unit)?) {
        val command = commands.changePlaybackPositionCommand
        command.enabled = handler != null
        if (handler == null) return

        val target: Any = command.addTargetWithHandler { event ->
            val position: MPChangePlaybackPositionCommandEvent? =
                event as? MPChangePlaybackPositionCommandEvent

            if (position == null) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                handler((position.positionTime * MILLIS_PER_SECOND).toLong())
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        targets += command to target
    }

    private fun removeTargets() {
        targets.forEach { (command, target) -> command.removeTarget(target) }
        targets.clear()
    }
}

private const val MILLIS_PER_SECOND = 1_000.0
