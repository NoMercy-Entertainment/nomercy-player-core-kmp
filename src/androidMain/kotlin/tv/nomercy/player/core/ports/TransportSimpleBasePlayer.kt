// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
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
    private var isLive: Boolean = false
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

    // Read by Media3SystemTransport's own PLAYING-branch service-promotion
    // check. True only for a session whose actions report
    // PLAYBACK_TYPE_REMOTE (see [getState]'s DeviceInfo branch below) — by
    // construction that is never genuine local playback (RemoteCastSystemSession
    // hardcodes it true; AppMusicMediaSessionPlugin only turns it true while
    // this device is a passive, engine-idle Connect mirror).
    val isVolumeRemoteNow: Boolean
        get() = actions.isVolumeRemote?.invoke() == true

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
        isLive = nowPlaying.isLive
        hasItem = true
        invalidateState()
    }

    fun setPlayback(state: TransportPlaybackState, positionMs: Long) {
        playing = state == TransportPlaybackState.PLAYING
        this.positionMs = positionMs
        invalidateState()
    }

    // The inbound half of the volume conversation — see
    // [SystemTransport.setDeviceVolume]'s own doc. A percent that arrived
    // from outside (a server frame, another client's own press) replaces
    // whatever this bridge last showed, same explicit-setter-plus-invalidate
    // shape as [setNowPlaying]/[setPlayback] above.
    fun setRemoteVolume(percent: Int) {
        remoteVolume = percent.coerceIn(0, REMOTE_VOLUME_MAX)
        invalidateState()
    }

    // The real MediaRouter2 route this playback is now going through, handed
    // down from Media3SystemTransport the moment a MediaRoute2ProviderService
    // reports a selection — see [SystemTransport.setRoutingControllerId]'s own
    // doc for why. Same explicit-setter-plus-invalidate shape as the volume
    // and now-playing setters above.
    private var routingControllerId: String? = null

    fun setRoutingControllerId(routingControllerId: String?) {
        this.routingControllerId = routingControllerId
        invalidateState()
    }

    fun blank() {
        metadata = MediaMetadata.EMPTY
        hasItem = false
        playing = false
        positionMs = 0
        durationMs = 0
        isLive = false
        invalidateState()
    }

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(available)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (hasItem) Player.STATE_READY else Player.STATE_IDLE)
            .setContentPositionMs(positionMs)
            .also { state ->
                // A session whose volume is LOCAL has its keys handled by the
                // platform against this device's own stream, and the player is
                // never asked. Only a REMOTE device is routed to the player,
                // which is the whole point of taking the press.
                if (actions.onVolumeStep != null && actions.isVolumeRemote?.invoke() == true) {
                    state.setDeviceInfo(
                        DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                            .setMaxVolume(REMOTE_VOLUME_MAX)
                            .setMinVolume(0)
                            .setRoutingControllerId(routingControllerId)
                            .build(),
                    )
                    state.setDeviceVolume(remoteVolume)
                }
            }
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
                        // TIME_UNSET when live OR not-yet-known — both are
                        // "no seek bar", the difference is app-UI-facing
                        // (NowPlaying.isLive), not Media3's. A literal 0 here
                        // drew a real, frozen zero-length bar instead of
                        // Media3's own no-duration treatment.
                        .setDurationUs(
                            if (isLive || durationMs <= 0L) C.TIME_UNSET else durationMs * MICROS_PER_MILLI,
                        )
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

    // Media3 requires a level to draw before anything real has arrived — the
    // window between this bridge being built and [setRemoteVolume]'s first
    // real push. Zero rather than a fake midpoint: a level this bridge has
    // never been told is honestly unknown, not "half".
    private var remoteVolume: Int = 0

    // Both the flags and the no-arg variants: which one Media3 dispatches to
    // depends on the caller, and overriding one leaves the other's default in
    // play — a press that lands on the wrong one is silently dropped.
    private fun stepDeviceVolume(direction: Int): ListenableFuture<*> {
        remoteVolume = (remoteVolume + direction).coerceIn(0, REMOTE_VOLUME_MAX)
        actions.onVolumeStep?.invoke(direction)
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = stepDeviceVolume(1)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = stepDeviceVolume(-1)

    // A drag, not a notch — deviceVolume is the WHOLE position the viewer
    // dragged to, and [TransportActions.onVolumeSet] is the only handler that
    // can act on that rather than just its sign. Falling back to a single
    // step when nothing is wired for it keeps a caller with no absolute sink
    // working exactly as before, one notch per drag regardless of distance.
    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        val onSet = actions.onVolumeSet
        if (onSet != null) {
            remoteVolume = deviceVolume.coerceIn(0, REMOTE_VOLUME_MAX)
            onSet.invoke(remoteVolume)
            return Futures.immediateVoidFuture()
        }
        val direction: Int = if (deviceVolume > remoteVolume) 1 else -1
        return stepDeviceVolume(direction)
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
        // Declared only when someone wants the presses. Media3 hands a volume
        // key to the player only if the player says it takes one; otherwise the
        // platform moves this device's own stream and the press never leaves.
        if (actions.onVolumeStep != null) {
            builder.add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
            builder.add(Player.COMMAND_ADJUST_DEVICE_VOLUME)
            builder.add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
            builder.add(Player.COMMAND_GET_DEVICE_VOLUME)
        }
        return builder.build()
    }

    private companion object {
        // One item, because the core owns the queue and Media3 is being told
        // what is playing rather than asked to manage a playlist. Next and
        // previous still work: they arrive as commands, not as index changes.
        const val ITEM_ID = "nomercy-current"

        const val MICROS_PER_MILLI = 1_000L

        // 100, not a small integer range — Media3's DeviceInfo places no
        // upper bound on it, and Connect's own wire volume (MusicHub's
        // volume_percentage) is already a clean 0-100 percent. Matching it
        // 1:1 means a value crossing this bridge never needs converting in
        // either direction, which is what a lossy small-range max was doing
        // before: a server-reported 37% had nowhere exact to land in a
        // 0-20 scale.
        const val REMOTE_VOLUME_MAX = 100

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
