// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.desktop

import tv.nomercy.player.core.ports.NowPlaying
import tv.nomercy.player.core.ports.TransportActions
import tv.nomercy.player.core.ports.TransportPlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The Windows flyout and the keyboard's media keys.
//
// Everything here runs against a fake set of controls, which is the point of the
// seam: the mapping is the part that goes wrong and it is ordinary code. What is
// left needing Windows is the handful of calls the fake stands in for.
class WindowsSmtcTransportTest {

    private class FakeControls : SmtcControls {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var artwork: String? = null
        var commits: Int = 0
        // Not called "status": a property of that name generates the same JVM
        // signature as the interface method it records, and the two clash.
        var shownStatus: SmtcStatus? = null
        var enabled: Set<SmtcButton> = emptySet()
        var handler: ((SmtcButton) -> Unit)? = null
        var cleared: Int = 0
        var closed: Int = 0

        override fun showMusic(title: String, artist: String, album: String) {
            this.title = title
            this.artist = artist
            this.album = album
        }

        override fun showArtwork(url: String?) {
            artwork = url
        }

        override fun commit() {
            commits += 1
        }

        override fun setStatus(status: SmtcStatus) {
            shownStatus = status
        }

        override fun enableButtons(buttons: Set<SmtcButton>) {
            enabled = buttons
        }

        override fun onButtonPressed(handler: ((SmtcButton) -> Unit)?) {
            this.handler = handler
        }

        override fun clear() {
            cleared += 1
        }

        override fun close() {
            closed += 1
        }
    }

    private val controls = FakeControls()
    private val transport = WindowsSmtcTransport(controls)

    @Test
    fun theTrackReachesTheFlyoutOnlyOnceItIsCommitted() {
        // Windows draws from a display updater that keeps whatever it was last
        // given until told to publish. Setting the fields without committing
        // leaves the previous track on screen, playing the new one.
        transport.setNowPlaying(NowPlaying(title = TRACK_TITLE, artist = TRACK_ARTIST, album = "Season 1"))

        assertEquals(TRACK_TITLE, controls.title)
        assertEquals(1, controls.commits)
    }

    @Test
    fun aMissingArtistIsBlankedRatherThanLeftAlone() {
        // The updater keeps its last value, so a track with no artist would show
        // the previous track's. An empty string is how Windows is told there is
        // none.
        transport.setNowPlaying(NowPlaying(title = "First", artist = TRACK_ARTIST))
        transport.setNowPlaying(NowPlaying(title = "Second"))

        assertEquals("", controls.artist)
        assertEquals("", controls.album)
    }

    @Test
    fun eachPlayerStateGetsThePictureWindowsHasForIt() {
        assertEquals(SmtcStatus.PLAYING, smtcStatusOf(TransportPlaybackState.PLAYING))
        assertEquals(SmtcStatus.PAUSED, smtcStatusOf(TransportPlaybackState.PAUSED))
        assertEquals(SmtcStatus.STOPPED, smtcStatusOf(TransportPlaybackState.STOPPED))
    }

    @Test
    fun theStatusValuesAreTheOnesWindowsActuallyUses() {
        // Not checkable at runtime on any other machine, and a wrong number is
        // not an error: it is controls that show the wrong picture.
        assertEquals(3, SmtcStatus.PLAYING.winrtValue)
        assertEquals(4, SmtcStatus.PAUSED.winrtValue)
        assertEquals(2, SmtcStatus.STOPPED.winrtValue)
    }

    @Test
    fun onlyTheButtonsThatAreWiredAreOffered() {
        // A media key that lights up and does nothing is worse than one that is
        // not offered, because the person pressing it concludes the application
        // has hung.
        transport.setActionHandlers(TransportActions(onPlay = {}, onPause = {}))

        assertEquals(setOf(SmtcButton.PLAY, SmtcButton.PAUSE), controls.enabled)
    }

    @Test
    fun pressingAKeyReachesTheHandlerItBelongsTo() {
        val pressed: MutableList<String> = mutableListOf()
        transport.setActionHandlers(
            TransportActions(
                onPlay = { pressed += PLAY },
                onPause = { pressed += PAUSE },
                onNext = { pressed += NEXT },
                onPrevious = { pressed += "previous" },
            ),
        )

        controls.handler?.invoke(SmtcButton.NEXT)
        controls.handler?.invoke(SmtcButton.PAUSE)

        assertEquals(listOf(NEXT, PAUSE), pressed)
    }

    @Test
    fun aKeyWithNoHandlerIsIgnoredRatherThanCrashing() {
        // The controls can deliver a press for a button we did not enable, and
        // an exception on the thread Windows called us on takes the process with
        // it.
        transport.setActionHandlers(TransportActions(onPlay = {}))

        controls.handler?.invoke(SmtcButton.STOP)
    }

    @Test
    fun releasingStopsListeningBeforeTheControlsGoAway() {
        // A handler left attached to a closed set of controls is a callback into
        // an object that no longer exists.
        transport.setActionHandlers(TransportActions(onPlay = {}))

        transport.release()

        assertNull(controls.handler)
        assertEquals(1, controls.closed)
    }

    @Test
    fun clearingLeavesTheControlsStoppedRatherThanShowingTheLastTrack() {
        transport.setNowPlaying(NowPlaying(title = TRACK_TITLE))

        transport.clear()

        assertEquals(1, controls.cleared)
        assertEquals(SmtcStatus.STOPPED, controls.shownStatus)
    }
}

private const val TRACK_TITLE = "Rail Wars"
private const val TRACK_ARTIST = "Nomad"
private const val PLAY = "play"
private const val PAUSE = "pause"
private const val NEXT = "next"
