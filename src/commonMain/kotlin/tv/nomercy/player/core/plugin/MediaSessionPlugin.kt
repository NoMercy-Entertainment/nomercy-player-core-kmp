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
        // Gated: a device passively mirroring another one's session keeps its
        // OWN engine paused on purpose (see MusicConnectPlugin.applyPassiveFrame
        // — it pauses the local engine every frame to guarantee it never
        // becomes a second stream). That local pause/resume-around-idle still
        // fires these same events, and left unguarded they raced
        // publishMirroredItem/publishMirroredState — the consumer's own
        // correct source of truth for what a passive device shows — for
        // ownership of this transport's PlaybackState. Whichever of the two
        // landed last won, several times a second, which is what turned a
        // passively mirrored PLAYING session into a system notification that
        // visibly flickered between playing and paused. Confirmed live, real
        // Samsung device, 2026-09-09 (video-captured + Samsung's own SystemUI
        // logs), root-caused via the exact 5s match between the flip period
        // and the far side's own position-report cadence.
        on(CoreEvents.Play) { if (!isPassivelyMirroring()) push(TransportPlaybackState.PLAYING) }
        on(CoreEvents.Pause) { if (!isPassivelyMirroring()) push(TransportPlaybackState.PAUSED) }
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

    /**
     * The transport to publish through — reopening one if the held reference
     * has gone stale (see [SystemTransport.isReleased]'s own doc for why that
     * happens even though this plugin never released it itself). Every
     * outward push in this class reads through here rather than the raw
     * [transport] field, so a plugin that outlives another engine's own
     * transport takeover recovers on its own next update instead of pushing
     * silently into a dead session forever.
     *
     * Null only before [use] has ever run, or after [dispose] — an
     * uninstalled plugin has nothing to reopen.
     */
    private fun liveTransport(): SystemTransport? {
        val current: SystemTransport = transport ?: return null
        if (!current.isReleased) return current
        val reopened: SystemTransport = openTransport()
        transport = reopened
        reopened.setActionHandlers(handlers())
        return reopened
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
        liveTransport()?.clearNowPlaying()
    }

    /** Rebuilds the custom buttons for what is playing, for a consumer whose own
     *  button state changed without the item changing. */
    protected fun refreshCustomButtons() {
        val opened: SystemTransport = liveTransport() ?: return
        val current: PlaylistItem = item() ?: return
        opened.setCustomButtons(customButtonsFor(current))
    }

    private var announced: NowPlaying? = null

    private fun announce(item: PlaylistItem?) {
        val opened: SystemTransport = liveTransport() ?: return
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
        val opened: SystemTransport = liveTransport() ?: return
        val playing: NowPlaying = nowPlayingFor(item)
        announced = playing
        opened.setNowPlaying(playing)
    }

    private fun push(state: TransportPlaybackState) {
        lastState = state
        liveTransport()?.setPlaybackState(state, positionMs, PLAYING_RATE)
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

    /**
     * A system slider dragged to an absolute percent, or null to leave only
     * [volumeStepHandler]'s ±1 reading available — see [TransportActions.onVolumeSet].
     */
    protected open fun volumeSetHandler(): ((Int) -> Unit)? = null

    /** True while the press belongs to another device — see [TransportActions.isVolumeRemote]. */
    protected open fun volumeIsRemote(): Boolean = false

    /**
     * True while this device's own engine is being kept deliberately idle to
     * mirror a session actually happening elsewhere — see [publishMirroredItem]/
     * [publishMirroredState]'s own doc for why an idle engine still fires real
     * Play/Pause events. While this is true, [use]'s own CoreEvents.Play/Pause
     * listeners publish nothing, so a consumer's explicit mirrored publish is
     * never raced by the local engine's own paused-on-purpose noise. False by
     * default: only a consumer that has a concept of "mirroring" knows when it
     * applies, the same reasoning [volumeIsRemote] already uses.
     */
    protected open fun isPassivelyMirroring(): Boolean = false

    /**
     * Publish a state this player's own engine cannot report.
     *
     * A device mirroring a session happening elsewhere has an idle engine, so
     * every event this plugin listens to says paused — and a paused session is
     * skipped when the platform decides who receives a hardware volume key,
     * which sends the press to the local speaker instead. A consumer that knows
     * playback is live somewhere else says so here.
     */
    /**
     * Publish the item a mirroring device is showing.
     *
     * Registering this plugin is not enough on its own: it builds its metadata
     * from the engine's item, and a device mirroring a session elsewhere has
     * none — so nothing was ever published and the platform had no session for
     * the app at all (measured: "Media button session is null"). A session that
     * does not exist cannot be handed a volume key.
     *
     * [durationMs] overrides whatever [nowPlayingFor] would have read off this
     * device's OWN engine — always 0 while mirroring, because a device that
     * never loads anything never gets a real CoreEvents.Time tick to learn a
     * duration from. Publishing that 0 drew a system notification with no seek
     * bar at all, even though the far side's real duration was sitting right
     * there in the frame this call is built from. Confirmed live, real Samsung
     * device, 2026-09-09.
     */
    protected fun publishMirroredItem(item: PlaylistItem, durationMs: Long) {
        val playing: NowPlaying = nowPlayingFor(item).copy(
            durationMs = durationMs,
            isLive = durationMs <= 0L,
        )
        liveTransport()?.setNowPlaying(playing)
    }

    protected fun publishMirroredState(isPlaying: Boolean, positionMs: Long) {
        liveTransport()?.setPlaybackState(
            if (isPlaying) TransportPlaybackState.PLAYING else TransportPlaybackState.PAUSED,
            positionMs,
            if (isPlaying) 1.0 else 0.0,
        )
    }

    /**
     * The active device's REAL, server-reported volume, pushed down into
     * whatever slider the platform draws for it.
     *
     * A consumer whose device may be controlling — or merely watching —
     * playback happening elsewhere calls this every time that real level
     * changes, including a change this device had no part in (another
     * client's own press, the far end's own remote). Without a live push
     * here the platform's slider only ever shows local interaction history:
     * see [SystemTransport.setDeviceVolume]'s own doc for the bug this closes.
     */
    protected fun publishRemoteVolume(percent: Int) {
        liveTransport()?.setDeviceVolume(percent)
    }

    /**
     * The real system route this session's playback is now going through —
     * see [SystemTransport.setRoutingControllerId]'s own doc. A consumer with
     * a platform route provider (Android's `MediaRoute2ProviderService`)
     * calls this the moment a route is selected, and again with `null` the
     * moment it's released, so the platform's own output-switcher chip has a
     * real name to resolve instead of falling back to a placeholder.
     */
    protected fun publishRoutingControllerId(routingControllerId: String?) {
        liveTransport()?.setRoutingControllerId(routingControllerId)
    }

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
        onVolumeSet = volumeSetHandler(),
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
