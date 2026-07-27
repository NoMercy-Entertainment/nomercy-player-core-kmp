// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// Media3's idea of a player, backed by whatever is actually playing.
//
// A MediaSession requires an androidx.media3.common.Player and the core's engine
// is not one — it is a port with several implementations, only one of which is
// ExoPlayer. SimpleBasePlayer is Media3's own answer to that: a base for players
// it did not write, where a subclass describes its state and handles commands.
//
// This is the bridge and nothing more. It holds no playback logic; it turns
// Media3's commands into TransportActions and Media3's questions into whatever
// the plugin last pushed.
@UnstableApi
internal class TransportSimpleBasePlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    private var actions: TransportActions = TransportActions()

    private var playing: Boolean = false
    private var positionMs: Long = 0
    private var durationMs: Long = 0
    private var metadata: MediaMetadata = MediaMetadata.EMPTY
    private var hasItem: Boolean = false

    fun setActions(actions: TransportActions) {
        this.actions = actions
    }

    // Every setter invalidates rather than pushing. Media3 pulls its state when
    // it is told something changed, and building a State object on the caller's
    // thread would race the session reading the last one.
    fun setNowPlaying(nowPlaying: NowPlaying) {
        metadata = MediaMetadata.Builder()
            .setTitle(nowPlaying.title)
            .setArtist(nowPlaying.artist)
            .setAlbumTitle(nowPlaying.album)
            .setArtworkUri(nowPlaying.artworkUrl?.let(android.net.Uri::parse))
            .build()
        durationMs = nowPlaying.durationMs
        hasItem = true
        invalidateState()
    }

    fun setPlayback(state: TransportPlaybackState, positionMs: Long) {
        playing = state == TransportPlaybackState.PLAYING
        this.positionMs = positionMs
        invalidateState()
    }

    fun blank() {
        metadata = MediaMetadata.EMPTY
        hasItem = false
        playing = false
        positionMs = 0
        durationMs = 0
        invalidateState()
    }

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (hasItem) Player.STATE_READY else Player.STATE_IDLE)
            .setContentPositionMs(positionMs)

        if (hasItem) {
            builder.setPlaylist(
                listOf(
                    MediaItemData.Builder(ITEM_ID)
                        .setMediaItem(MediaItem.Builder().setMediaId(ITEM_ID).build())
                        .setMediaMetadata(metadata)
                        .setDurationUs(durationMs * MICROS_PER_MILLI)
                        .build(),
                ),
            )
        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) actions.onPlay?.invoke() else actions.onPause?.invoke()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        actions.onStop?.invoke()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> actions.onNext?.invoke()

            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> actions.onPrevious?.invoke()

            // Everything else is a position: a scrubber dragged, a skip button,
            // a car's rewind. They differ in how the position was arrived at and
            // not in what the player should do with it.
            else -> actions.onSeekTo?.invoke(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    private companion object {
        // One item, because the core owns the queue and Media3 is being told
        // what is playing rather than asked to manage a playlist. Next and
        // previous still work: they arrive as commands, not as index changes.
        const val ITEM_ID = "nomercy-current"

        const val MICROS_PER_MILLI = 1_000L

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
            )
            .build()
    }
}
