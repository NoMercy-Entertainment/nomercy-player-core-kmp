// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.desktop

import tv.nomercy.player.core.ports.NowPlaying
import tv.nomercy.player.core.ports.SystemTransport
import tv.nomercy.player.core.ports.TransportActions
import tv.nomercy.player.core.ports.TransportPlaybackState

// What Windows shows above the volume flyout, and on the keyboard's media keys.
//
// The controls are a WinRT object, and the obvious way to get one is to ask for
// the one belonging to a window. A library has no window, which is where this
// stalled the first time it was looked at. The answer is that a WinRT media
// player owns a set as well, and that route needs no window at all — so the
// binding below is a handful of calls rather than a reason not to have this.
//
// The binding is the only part of this file that needs Windows. Everything above
// it is the mapping, and it is tested against a fake.
public class WindowsSmtcTransport(private val controls: SmtcControls) : SystemTransport {

    override fun setNowPlaying(nowPlaying: NowPlaying) {
        controls.showMusic(
            title = nowPlaying.title,
            // Windows draws an empty string where a null would leave the
            // previous track's artist on screen. The display updater keeps
            // whatever it was last given.
            artist = nowPlaying.artist.orEmpty(),
            album = nowPlaying.album.orEmpty(),
        )
        controls.showArtwork(nowPlaying.artworkUrl)
        controls.commit()
    }

    override fun setPlaybackState(
        state: TransportPlaybackState,
        positionMs: Long,
        playbackRate: Double,
    ) {
        controls.setStatus(smtcStatusOf(state))
    }

    // Enabled from what is wired rather than all on. A media key that lights up
    // and does nothing is worse than one that is not offered, because the person
    // pressing it concludes the application has hung.
    override fun setActionHandlers(actions: TransportActions) {
        controls.enableButtons(
            buildSet {
                if (actions.onPlay != null) add(SmtcButton.PLAY)
                if (actions.onPause != null) add(SmtcButton.PAUSE)
                if (actions.onStop != null) add(SmtcButton.STOP)
                if (actions.onNext != null) add(SmtcButton.NEXT)
                if (actions.onPrevious != null) add(SmtcButton.PREVIOUS)
            },
        )
        controls.onButtonPressed { button -> dispatch(button, actions) }
    }

    override fun clear() {
        controls.clear()
        controls.setStatus(smtcStatusOf(TransportPlaybackState.STOPPED))
    }

    override fun release() {
        controls.onButtonPressed(null)
        controls.close()
    }

    private fun dispatch(button: SmtcButton, actions: TransportActions) {
        when (button) {
            SmtcButton.PLAY -> actions.onPlay
            SmtcButton.PAUSE -> actions.onPause
            SmtcButton.STOP -> actions.onStop
            SmtcButton.NEXT -> actions.onNext
            SmtcButton.PREVIOUS -> actions.onPrevious
        }?.invoke()
    }
}

// The numbers Windows uses for MediaPlaybackStatus. Named here so the mapping is
// one thing to read rather than a magic number at the call site, and so the
// values can be checked without a Windows machine.
public enum class SmtcStatus(public val winrtValue: Int) {
    CLOSED(0),
    CHANGING(1),
    STOPPED(2),
    PLAYING(3),
    PAUSED(4),
}

public enum class SmtcButton { PLAY, PAUSE, STOP, NEXT, PREVIOUS }

// Every player state Windows has a picture for.
//
// The player's own states do not map one to one: buffering and seeking are both
// still playing as far as the flyout is concerned, and drawing them as stopped
// makes the controls flicker on every seek.
public fun smtcStatusOf(state: TransportPlaybackState): SmtcStatus = when (state) {
    TransportPlaybackState.PLAYING -> SmtcStatus.PLAYING
    TransportPlaybackState.PAUSED -> SmtcStatus.PAUSED
    TransportPlaybackState.STOPPED -> SmtcStatus.STOPPED
}

// The WinRT surface, which is all that is left to write against the platform.
//
// Kept this narrow on purpose: everything above is ordinary Kotlin that runs and
// is tested anywhere, and the part that needs Windows is small enough to read in
// one sitting.
public interface SmtcControls {

    public fun showMusic(title: String, artist: String, album: String)

    public fun showArtwork(url: String?)

    public fun commit()

    public fun setStatus(status: SmtcStatus)

    // The set of what is offered rather than a flag each. Five booleans in a
    // row is the call site where two of them get swapped and the mistake shows
    // up as a next button that pauses.
    public fun enableButtons(buttons: Set<SmtcButton>)

    public fun onButtonPressed(handler: ((SmtcButton) -> Unit)?)

    public fun clear()

    public fun close()
}
