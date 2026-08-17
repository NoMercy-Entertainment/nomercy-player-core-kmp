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
import tv.nomercy.player.core.ports.CustomTransportButton
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

    // Whether a real CoreEvents.Time update has arrived for the CURRENT item.
    // Reset alongside durationMs on every announce(); flipped true the moment
    // one lands, live or not — that flip is what tells "live" apart from
    // "not measured yet" below.
    private var durationKnown: Boolean = false

    override fun use() {
        val opened: SystemTransport = openTransport()
        transport = opened
        opened.setActionHandlers(handlers())

        on(CoreEvents.Item) { change -> announce(change.item) }
        on(CoreEvents.Play) { push(TransportPlaybackState.PLAYING) }
        on(CoreEvents.Pause) { push(TransportPlaybackState.PAUSED) }
        on(CoreEvents.Time) { update ->
            positionMs = toMillis(update.time)
            val newDurationMs = toMillis(update.duration)
            // Only pushed when the duration actually moves — the item's
            // duration is usually still unknown at announce() (Item fires
            // before the engine has read it), so every notification built
            // that way carried durationMs=0 forever and Media3 drew no seek
            // bar for it at all, confirmed live, real device, 2026-08-12.
            //
            // Routed through pushNowPlaying(), NOT announce() — announce()
            // resets positionMs to 0 and durationKnown to false, which called
            // from here (every tick, once durationKnown flips back to false
            // by its own reset) snapped the reported position back to zero
            // every second and produced exactly the flicker this file's top
            // comment already warns against — confirmed live, real device,
            // 2026-08-12 (the seek bar vanished within a few seconds of
            // playback starting).
            val changed = durationMs != newDurationMs
            val firstTickForItem = !durationKnown
            durationKnown = true
            durationMs = newDurationMs
            if (firstTickForItem || changed) {
                item()?.let { pushNowPlaying(it) }
            }
        }
        on(CoreEvents.Seek) { position ->
            positionMs = toMillis(position.time)
            push(lastState)
        }
        // Seeked fires ~11ms BEFORE the real BACKEND_SETTLE blip lands
        // (SeekedOrderingTraceTest, real hardware, 2026-08-15), so this
        // push(lastState) can be stale — harmless, since the unconditional
        // Play/Pause handlers above overwrite it once the settle event
        // arrives. Kept for positionMs, which should update immediately
        // rather than wait for that settle.
        on(CoreEvents.Seeked) { position ->
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

        // What is already playing, for the attach that arrives late.
        //
        // The listeners above only ever hear the NEXT item, and a consumer that
        // queues and plays before installing the plugin never fires one within
        // their hearing — which is the common order, because the player is
        // built and started and the session plugin is one line further down.
        // Without this the lock screen stays empty until the track changes.
        item()?.let { announce(it) }
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
        // Live only once a real update has confirmed there is no duration —
        // never on the transient durationMs==0 every item starts at.
        isLive = durationKnown && durationMs <= 0L,
    )

    // The lego-brick custom buttons for this item — favorite, or whatever
    // else a consumer wants beside the standard transport controls. Empty by
    // default: this library has no concept of what any of them mean, the
    // same reasoning as [nowPlayingFor]'s own comment. A subclass overrides
    // this rather than calling SystemTransport.setCustomButtons directly, so
    // the buttons stay in step with the item they were built for — announce
    // pushes both together, and a consumer cannot push one without the
    // other going stale.
    protected open fun customButtonsFor(item: PlaylistItem): List<CustomTransportButton> = emptyList()

    private var lastState: TransportPlaybackState = TransportPlaybackState.STOPPED

    /**
     * What the system transport is currently showing, or null when nothing has
     * been announced.
     *
     * The reference exposes it so a host can read back what the lock screen
     * and the car display are actually saying, rather than re-deriving it from
     * the item and hoping the two agree.
     */
    public fun metadata(): NowPlaying? = announced

    /**
     * Clear the system transport's metadata without tearing the session down.
     *
     * The reference separates this from dispose deliberately: a player between
     * items, or one that has stopped but is still mounted, should not leave the
     * previous track's title on a lock screen. Only dispose existed here, so
     * the choice was a stale title or no session at all.
     */
    public fun clearMetadata() {
        announced = null
        transport?.clearNowPlaying()
    }

    /** Rebuilds the custom buttons for what is playing, for a consumer whose own
     *  button state changed without the item changing. */
    protected fun refreshCustomButtons() {
        val opened: SystemTransport = transport ?: return
        val current: PlaylistItem = item() ?: return
        opened.setCustomButtons(customButtonsFor(current))
    }

    private var announced: NowPlaying? = null

    private fun announce(item: PlaylistItem?) {
        val opened: SystemTransport = transport ?: return
        if (item == null) {
            // The cursor past the end of an exhausted queue — OR the transient
            // null a queue REPLACE passes through on its way to the real item
            // (confirmed live: every playTrack() call fires this on its way to
            // announcing the real item, not only on genuine exhaustion). Using
            // the heavier clear() here — which now also unpublishes the
            // session and resets the foreground-promotion flag, see its own
            // comment — turned every ordinary track change into a spurious
            // stop/republish cycle and reintroduced
            // ForegroundServiceDidNotStartInTimeException on a real device,
            // 2026-08-12. clearNowPlaying() only blanks the displayed
            // metadata, which is all a transient (or genuinely empty) cursor
            // needs — the session itself stays exactly as it was.
            opened.clearNowPlaying()
            return
        }

        positionMs = 0
        // Reset alongside position — a track change carries over the PREVIOUS
        // track's durationMs otherwise, since only the Time handler ever wrote
        // it, and that handler only re-announces on the 0-to-known transition
        // (see its own comment). Without this reset that transition never
        // happens again after the first track: switching songs (or to a radio
        // station with a different length) left the notification/mini-player
        // showing the old track's stale duration and a progress bar computed
        // against it — confirmed live, real device, 2026-08-12.
        durationMs = 0
        durationKnown = false
        pushNowPlaying(item)
        opened.setCustomButtons(customButtonsFor(item))
    }

    // The metadata half of announce(), without the item-change resets —
    // positionMs and durationKnown carry over. This is what the Time
    // handler's duration correction calls: it is fixing up the CURRENT
    // item's metadata, not starting a new one, so it must not touch either.
    private fun pushNowPlaying(item: PlaylistItem) {
        val opened: SystemTransport = transport ?: return
        val playing: NowPlaying = nowPlayingFor(item)
        announced = playing
        opened.setNowPlaying(playing)
    }

    private fun push(state: TransportPlaybackState) {
        lastState = state
        transport?.setPlaybackState(state, positionMs, PLAYING_RATE)
    }

    // What the system may ask for. Seek and the two transport verbs always;
    // queue movement only if the player has a queue, because a control with no
    // handler is hidden rather than drawn doing nothing.
    /**
     * A hardware volume press, in notches, or null to leave the platform's own
     * default alone. Overridden by a consumer whose device may be controlling
     * playback somewhere else — see [TransportActions.onVolumeStep].
     */
    protected open fun volumeStepHandler(): ((Int) -> Unit)? = null

    /** True while the press belongs to another device — see [TransportActions.isVolumeRemote]. */
    protected open fun volumeIsRemote(): Boolean = false

    private fun handlers(): TransportActions = TransportActions(
        onPlay = commands::play,
        onPause = commands::pause,
        onStop = commands::stop,
        onNext = commands::next,
        onPrevious = commands::previous,
        onSeekTo = commands::seekTo,

        // Wired unconditionally, like next and previous: TransportCommands
        // defaults both to nothing, so a player that cannot skip registers a
        // handler that does nothing rather than the platform hiding a control
        // the web player draws. That is the web plugin's own shape — its
        // seekbackward and seekforward handlers call optional player methods.
        onSkipBackward = commands::skipBackward,
        onSkipForward = commands::skipForward,
        onVolumeStep = volumeStepHandler(),
        isVolumeRemote = { volumeIsRemote() },
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
