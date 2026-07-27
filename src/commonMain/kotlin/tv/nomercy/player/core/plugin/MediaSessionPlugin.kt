// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.ports.NowPlaying
import tv.nomercy.player.core.ports.SystemTransport
import tv.nomercy.player.core.ports.TransportActions
import tv.nomercy.player.core.ports.TransportPlaybackState
import tv.nomercy.player.core.ports.defaultSystemTransport

// The player on the lock screen, and the lock screen driving the player.
//
// One plugin for every platform, because the alternative is what the app has
// today: two Android session owners that each think they are in charge, and a
// notification whose buttons depend on which of them registered last. There is
// exactly one owner here and it is this — the plugin builds the transport when
// it is installed and releases it when it goes away.
//
// Both directions cross a narrow seam. Outward through SystemTransport, inward
// through TransportCommands, and neither of them is the player: a lock screen
// that could reach the player object could change the subtitle track.
public open class MediaSessionPlugin(
    private val commands: TransportCommands,
    private val openTransport: () -> SystemTransport = ::defaultSystemTransport,
) : Plugin<Unit>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "media-session"

        // Two, matching the web plugin this mirrors. The contract is the same
        // one; what changed underneath it is which operating systems answer.
        override val version: String = "2.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    private var transport: SystemTransport? = null

    // Where playback had got to when something last happened.
    //
    // Kept rather than pushed on every tick. Every one of these systems is told
    // a position and a rate and works the rest out itself, so a push per time
    // update is several a second of cross-process traffic to tell a lock screen
    // what it already knew — and Apple says as much about its own centre. What
    // the systems cannot work out is a discontinuity, which is why a seek does
    // push and a tick does not.
    private var positionMs: Long = 0

    private var durationMs: Long = 0

    override fun use() {
        val opened: SystemTransport = openTransport()
        transport = opened
        opened.setActionHandlers(handlers())

        on(CoreEvents.Item) { change -> announce(change.item) }
        on(CoreEvents.Play) { push(TransportPlaybackState.PLAYING) }
        on(CoreEvents.Pause) { push(TransportPlaybackState.PAUSED) }
        on(CoreEvents.Time) { update ->
            positionMs = toMillis(update.time)
            durationMs = toMillis(update.duration)
        }
        on(CoreEvents.Seek) { position ->
            positionMs = toMillis(position.time)
            push(lastState)
        }

        // Ended is the item finishing, which on a queue is followed by the next
        // one starting. Stop is a viewer closing the player. Both stop, and only
        // one of them should take the item off the lock screen — clearing on
        // ended makes a notification blink out between every track.
        on(CoreEvents.Ended) { push(TransportPlaybackState.STOPPED) }
        on(CoreEvents.Stop) {
            push(TransportPlaybackState.STOPPED)
            opened.clear()
        }
    }

    override fun dispose() {
        transport?.release()
        transport = null
    }

    // What the system shows for this item.
    //
    // Open, because a PlaylistItem carries an id, a url and a title and nothing
    // else — artwork, artist and album belong to the libraries that have them,
    // and a core that invented fields for them would be guessing at every
    // consumer's model.
    protected open fun nowPlayingFor(item: PlaylistItem): NowPlaying = NowPlaying(
        title = item.title ?: item.url.substringAfterLast('/'),
        durationMs = durationMs,
    )

    private var lastState: TransportPlaybackState = TransportPlaybackState.STOPPED

    private fun announce(item: PlaylistItem?) {
        val opened: SystemTransport = transport ?: return
        if (item == null) {
            // The cursor past the end of an exhausted queue. Nothing is playing
            // and the lock screen should say so rather than keep the last item.
            opened.clear()
            return
        }

        positionMs = 0
        opened.setNowPlaying(nowPlayingFor(item))
    }

    private fun push(state: TransportPlaybackState) {
        lastState = state
        transport?.setPlaybackState(state, positionMs, PLAYING_RATE)
    }

    // What the system may ask for. Seek and the two transport verbs always;
    // queue movement only if the player has a queue, because a control with no
    // handler is hidden rather than drawn doing nothing.
    private fun handlers(): TransportActions = TransportActions(
        onPlay = commands::play,
        onPause = commands::pause,
        onStop = commands::stop,
        onNext = commands::next,
        onPrevious = commands::previous,
        onSeekTo = commands::seekTo,
    )
}

// The core reports seconds and every one of these systems wants something else.
// Converting once here means each actual converts from a known unit rather than
// from whatever the last caller happened to have.
private fun toMillis(seconds: Double): Long =
    if (seconds.isFinite() && seconds > 0) (seconds * MILLIS_PER_SECOND).toLong() else 0

private const val MILLIS_PER_SECOND = 1_000

// One. Rate is on the port because the systems ask for it and a future speed
// control will have an answer; today nothing in the core changes it.
private const val PLAYING_RATE = 1.0
