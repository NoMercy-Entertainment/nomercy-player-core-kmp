// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.cast

import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.core.plugin.TransportCommands
import tv.nomercy.player.core.plugin.VolumeCommands

/**
 * Playing on something else in the room.
 *
 * The same `cast-sender` id the web plugin has and the same shape: connect,
 * hand the current item to the receiver, forward transport commands to it while
 * it is playing, and give playback back when it goes away.
 *
 * WHAT the receiver is belongs to [CastSession], not here. The web talks to the
 * Chromecast framework because that is what a browser can reach; an Android
 * client talks to Play Services and an Apple one to AVRoutePickerView, and none
 * of those may be linked from core. This plugin is the behaviour all three
 * share, which is also what lets the disconnect and resume paths be tested
 * without a television in the room.
 *
 * A null session is the web plugin's unsupported branch: [CastSenderEvents.Unsupported]
 * goes out once and nothing else happens. Playback is never blocked by a casting
 * scheme that could not run.
 */
public open class CastSenderPlugin(
    private val commands: TransportCommands,
    private val volume: VolumeCommands,
    private val session: CastSession? = null,
    private val opts: CastSenderOptions = CastSenderOptions(),
) : Plugin<CastSenderOptions>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "cast-sender"

        // Two, matching the web plugin this mirrors.
        override val version: String = "2.0.0"

        /** What an item is sent as when it does not say. */
        public const val DEFAULT_CONTENT_TYPE: String = "video/mp4"

        private const val VOLUME_SCALE: Double = 100.0
    }

    override val manifest: PluginManifest get() = Manifest

    override val options: CastSenderOptions get() = opts

    /**
     * Where the receiver last said it was.
     *
     * Kept because it is the position a viewer resumes at locally when the
     * receiver goes away, and the local player's own clock is frozen at
     * whatever it read when the session began.
     */
    private var remotePositionSeconds: Double = 0.0

    private var connected: Boolean = false

    private var lastSentContentId: String? = null

    private var stopObserving: (() -> Unit)? = null

    /** Whether a receiver is connected. */
    public fun isConnected(): Boolean = connected

    /** Where the receiver is, in seconds. Zero before anything has been cast. */
    public fun remotePosition(): Double = remotePositionSeconds

    override fun use() {
        val remote: CastSession = session ?: run {
            emit(
                CastSenderEventKeys.Unsupported,
                CastSenderEvents.Unsupported("no cast session was supplied to this player"),
            )
            return
        }

        stopObserving = remote.observe(::onRemote)
        connected = remote.isConnected()
    }

    override fun dispose() {
        stopObserving?.invoke()
        stopObserving = null
    }

    /** Opens the platform's picker. Does nothing when casting is unavailable. */
    public suspend fun connect() {
        val remote: CastSession = session ?: return
        runCatching { remote.connect() }.onFailure(::report)
    }

    public suspend fun disconnect() {
        val remote: CastSession = session ?: return
        runCatching { remote.disconnect() }.onFailure(::report)
    }

    /**
     * Sends an item to the receiver, starting where the local player is.
     *
     * [lastSentContentId] stops the same item being reloaded: a receiver told to
     * load what it is already playing starts it again from the beginning, which
     * a viewer experiences as the film jumping back to the top on a reconnect.
     */
    public suspend fun cast(media: CastMediaInfo, positionSeconds: Double) {
        val remote: CastSession = session ?: return
        if (media.contentId == lastSentContentId) return

        runCatching {
            remote.load(media.withDefaultsFrom(opts), positionSeconds)
            lastSentContentId = media.contentId
        }.onFailure(::report)
    }

    /**
     * What the receiver said.
     *
     * Every branch here is also published on the player's bus, because a chrome
     * subscribes to the player and not to a plugin it may not know is
     * registered.
     */
    private fun onRemote(event: CastSenderEvents) {
        when (event) {
            is CastSenderEvents.Connected -> {
                connected = true
                emit(CastSenderEventKeys.Connected, event)
            }

            is CastSenderEvents.Disconnected -> onDisconnected()

            is CastSenderEvents.Failed -> emit(CastSenderEventKeys.Failed, event)

            is CastSenderEvents.RemoteState -> {
                remotePositionSeconds = event.time
                emit(CastSenderEventKeys.RemoteState, event)
            }

            is CastSenderEvents.MediaChanged -> {
                lastSentContentId = event.contentId
                emit(CastSenderEventKeys.MediaChanged, event)
            }

            is CastSenderEvents.Unsupported -> emit(CastSenderEventKeys.Unsupported, event)
        }
    }

    /**
     * The receiver went away.
     *
     * Resuming seeks to where the RECEIVER was rather than simply pressing play:
     * the local player has been paused since the session started and its own
     * position is the one the film was at when casting began, which is usually
     * an hour wrong.
     */
    private fun onDisconnected() {
        connected = false
        lastSentContentId = null
        emit(CastSenderEventKeys.Disconnected, Unit)

        if (!opts.resumeLocalOnDisconnect) return

        commands.seekTo((remotePositionSeconds * MILLIS_PER_SECOND).toLong())
        commands.play()
    }

    // Forwarded to the receiver while it is connected, and to the local player
    // otherwise. One entry point per command so a chrome never has to ask which
    // of the two it is talking to — asking is how a pause button pauses a phone
    // that was already silent and leaves the television playing.
    public suspend fun play() {
        if (connected) session?.let { remote -> runCatching { remote.play() }.onFailure(::report) }
        else commands.play()
    }

    public suspend fun pause() {
        if (connected) session?.let { remote -> runCatching { remote.pause() }.onFailure(::report) }
        else commands.pause()
    }

    public suspend fun stop() {
        if (connected) session?.let { remote -> runCatching { remote.stop() }.onFailure(::report) }
        else commands.stop()
    }

    public suspend fun seekTo(positionSeconds: Double) {
        if (connected) {
            session?.let { remote -> runCatching { remote.seekTo(positionSeconds) }.onFailure(::report) }
            return
        }

        commands.seekTo((positionSeconds * MILLIS_PER_SECOND).toLong())
    }

    /** 0..100, the player's own scale, converted once here rather than by callers. */
    public suspend fun setVolume(level: Int) {
        if (connected) {
            session?.let { remote ->
                runCatching { remote.setVolume(level / VOLUME_SCALE) }.onFailure(::report)
            }
            return
        }

        volume.volume(level)
    }

    public suspend fun setMuted(muted: Boolean) {
        if (connected) {
            session?.let { remote -> runCatching { remote.setMuted(muted) }.onFailure(::report) }
            return
        }

        if (muted) volume.mute() else volume.unmute()
    }

    // Reported rather than thrown. A receiver refusing a command is not a reason
    // for the player to stop: the viewer is still watching something, and an
    // exception out of a forwarded pause would take the chrome down with it.
    private fun report(cause: Throwable) {
        logger.warn("cast command failed: ${cause.message}")
        emit(CastSenderEventKeys.Failed, CastSenderEvents.Failed(cause))
    }
}

private const val MILLIS_PER_SECOND: Double = 1000.0

/**
 * The item, with whatever the plugin's options say it did not carry.
 *
 * Applied here rather than at every call site, because a caller that forgets the
 * content type sends a receiver a stream it will not recognise and the failure
 * arrives as a blank television.
 */
private fun CastMediaInfo.withDefaultsFrom(opts: CastSenderOptions): CastMediaInfo = copy(
    contentType = contentType.ifBlank {
        opts.defaultContentType ?: CastSenderPlugin.DEFAULT_CONTENT_TYPE
    },
    streamType = streamType ?: if (opts.live) CastStreamType.LIVE else CastStreamType.BUFFERED,
)
