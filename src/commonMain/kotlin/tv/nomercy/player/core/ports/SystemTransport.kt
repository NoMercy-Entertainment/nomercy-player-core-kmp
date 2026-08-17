// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The operating system's own transport: a lock screen, a notification, a car's
// steering wheel, a keyboard's play key.
//
// Every platform has one and no two agree on anything — Android wants a Media3
// session, Apple a now-playing info dictionary and a remote command centre,
// Windows a system transport control, Linux a D-Bus object. What they do agree
// on is the shape of the conversation, and this is that shape: the player says
// what is playing and how it is going, the system says what the viewer pressed.
//
// Narrow on purpose. Handing an OS integration the whole player is how a lock
// screen ends up able to change the subtitle track.
public interface SystemTransport {

    // What is playing. Called when the item changes rather than on every tick:
    // a notification that rebuilds itself once a second is a notification that
    // flickers and a radio that never sleeps.
    public fun setNowPlaying(nowPlaying: NowPlaying)

    /**
     * Take the metadata off without ending the session.
     *
     * Defaulted to nothing so an existing implementation keeps compiling: a
     * platform that cannot clear separately is no worse off than before, and
     * one that can — Media3, MPNowPlayingInfoCenter, SMTC — overrides it. A
     * player between items should not leave the previous track's title on a
     * lock screen, and there was no way to say so.
     */
    public fun clearNowPlaying(): Unit = Unit

    // How it is going. Position is in milliseconds, which is Media3's unit and
    // the one most of these systems want; the two that do not convert once in
    // their own actual rather than making every caller guess.
    public fun setPlaybackState(
        state: TransportPlaybackState,
        positionMs: Long,
        playbackRate: Double,
    )

    // What the system is allowed to ask for. Set once; a platform that can only
    // register its commands at startup registers the ones it was given here.
    public fun setActionHandlers(actions: TransportActions)

    /**
     * The lego-brick custom buttons drawn beside the standard transport
     * controls.
     *
     * Defaulted to nothing so an existing implementation keeps compiling —
     * the same reasoning as [clearNowPlaying]: a platform that cannot draw
     * these is no worse off than before, and one that can — Media3's
     * `CommandButton` custom layout today, CarPlay's own custom set later —
     * overrides it. Called with the app's whole current set every time it
     * changes, including a state-only change on one button (an existing id
     * with a flipped isActive) — same replace-the-whole-list contract as
     * [setActionHandlers].
     */
    public fun setCustomButtons(buttons: List<CustomTransportButton>): Unit = Unit

    // Nothing is playing any more. Distinct from release: the transport is still
    // alive and will be used again, so a platform that tears down its session
    // here would have to build another one on the next item.
    public fun clear()

    public fun release()
}

// What the system shows about the current item.
//
// Named for the thing rather than for the shape of it. Every platform here has
// a type for this and they are all called some variation of now playing, so
// borrowing the phrase costs nothing and inventing a fifth name would.
//
// Artwork is a URL rather than a decoded image, deliberately. Every one of these
// platforms has its own image pipeline with its own memory rules — and handing a
// decoded bitmap across this seam is how an Android notification ends up holding
// a hardware bitmap it cannot read.
public data class NowPlaying(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0,
    // True only once the engine has actually reported a time update for this
    // item AND that update carries no duration — a genuine live stream, not
    // just a track whose duration hasn't arrived yet. durationMs==0 is worn
    // by both states; conflating them marked every freshly-announced track
    // "LIVE" for the instant before its real duration landed.
    val isLive: Boolean = false,
)

// The three states a system transport can show. Deliberately not the player's
// own phase enum: a player has buffering, seeking, stalled and ended states that
// a lock screen has no way to draw, and mapping them here rather than at each
// actual keeps four platforms from each inventing their own answer.
public enum class TransportPlaybackState {
    PLAYING,
    PAUSED,
    STOPPED,
}

// What the system may ask the player to do.
//
// Null means unsupported, and that is information the platforms use: Android
// hides a button with no handler and CarPlay refuses to register a command it
// cannot service. Defaulting them all to null means a caller wires up what it
// has rather than stubbing what it does not.
public data class TransportActions(
    val onPlay: (() -> Unit)? = null,
    val onPause: (() -> Unit)? = null,
    val onStop: (() -> Unit)? = null,
    val onNext: (() -> Unit)? = null,
    val onPrevious: (() -> Unit)? = null,
    val onSeekTo: ((Long) -> Unit)? = null,

    // A jump of a known length, which is a different button from the scrubber
    // and from next/previous: Android draws seek-back and seek-forward, Apple
    // draws MPSkipIntervalCommand, and both hide theirs when nothing is
    // registered. The offset is the one the system asked for, in milliseconds.
    val onSkipBackward: ((Long) -> Unit)? = null,
    val onSkipForward: ((Long) -> Unit)? = null,
    // The hardware volume rocker. Wired only by a consumer that has somewhere
    // else to send it: with no handler the system keeps its default, which is
    // this device's own output stream. A phone controlling playback happening
    // in another room needs the press to leave the phone, and the press never
    // reaches an Activity — the platform hands a volume key to the media
    // session, not to the foreground window.
    val onVolumeStep: ((Int) -> Unit)? = null,
    // Asked on every state build, because the answer changes while the app
    // runs: the press belongs elsewhere only while this device is not the one
    // playing. Declaring it always meant the device that IS playing had its own
    // speaker driven by the session too.
    val isVolumeRemote: (() -> Boolean)? = null,
)

// How far a skip button goes when the system has no opinion of its own.
//
// Five seconds because that is the web plugin's fallback for a browser that
// hands `seekbackward` no seekOffset, and the two are the same control. It is
// also what each platform is told to advertise, so the interval a viewer sees
// on the button is the interval the player moves by.
public const val DEFAULT_SKIP_OFFSET_MS: Long = 5_000

// The transport this platform actually has.
//
// Mirrors the defaultX() convention the other ports use, and for the same
// reason: a consumer that had to name a platform type would need one import per
// target in code that is meant to be shared.
public expect fun defaultSystemTransport(): SystemTransport
