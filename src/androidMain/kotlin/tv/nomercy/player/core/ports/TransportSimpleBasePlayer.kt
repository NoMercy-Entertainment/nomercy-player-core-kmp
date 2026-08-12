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

    // Read by Media3SystemTransport before it asks Android to promote the
    // service — a PLAYING push that arrives with nothing loaded (a toggle
    // fired after a real stop, before the queue was reloaded) reports
    // STATE_IDLE below regardless of [playing], and Media3 will not promote
    // an IDLE session to foreground. Requesting anyway is a promotion
    // guaranteed to time out — ForegroundServiceDidNotStartInTimeException,
    // confirmed live, real device, 2026-08-12.
    var hasItem: Boolean = false
        private set

    // Recomputed when the handlers change rather than per getState, because
    // getState runs on every invalidation and the answer only moves when the
    // plugin rewires. Which commands are in here is what the notification and
    // the car draw: a command advertised with no handler behind it is a button
    // that does nothing when pressed.
    private var available: Player.Commands = commandsFor(actions)

    fun setActions(actions: TransportActions) {
        this.actions = actions
        available = commandsFor(actions)
        invalidateState()
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
            .setAvailableCommands(available)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (hasItem) Player.STATE_READY else Player.STATE_IDLE)
            .setContentPositionMs(positionMs)
            // The interval the buttons are labelled with, so what a viewer is
            // offered is what the player moves by. Media3's own defaults are
            // five back and fifteen forward, which would draw two different
            // numbers for one pair of controls.
            .setSeekBackIncrementMs(DEFAULT_SKIP_OFFSET_MS)
            .setSeekForwardIncrementMs(DEFAULT_SKIP_OFFSET_MS)

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

            // A jump of a known length, and the length is what gets passed on
            // rather than the position Media3 worked out from it. The player's
            // own skip clamps at both ends and says it was a skip; an absolute
            // position it computed for us does neither.
            Player.COMMAND_SEEK_BACK -> actions.onSkipBackward?.invoke(DEFAULT_SKIP_OFFSET_MS)

            Player.COMMAND_SEEK_FORWARD -> actions.onSkipForward?.invoke(DEFAULT_SKIP_OFFSET_MS)

            // Everything else is a position: a scrubber dragged, a car's jump to
            // a bookmark. They differ in how the position was arrived at and not
            // in what the player should do with it.
            else -> actions.onSeekTo?.invoke(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    // Only what there is a handler for. The two skip commands are the ones that
    // move: everything else this transport offers is wired by the plugin every
    // time, and these are the pair a consumer building TransportActions by hand
    // can leave out.
    private fun commandsFor(actions: TransportActions): Player.Commands {
        val builder = Player.Commands.Builder()
        ALWAYS.forEach { command -> builder.add(command) }
        if (actions.onSkipBackward != null) builder.add(Player.COMMAND_SEEK_BACK)
        if (actions.onSkipForward != null) builder.add(Player.COMMAND_SEEK_FORWARD)
        return builder.build()
    }

    private companion object {
        // One item, because the core owns the queue and Media3 is being told
        // what is playing rather than asked to manage a playlist. Next and
        // previous still work: they arrive as commands, not as index changes.
        const val ITEM_ID = "nomercy-current"

        const val MICROS_PER_MILLI = 1_000L

        val ALWAYS: IntArray = intArrayOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE,
        )
    }
}
