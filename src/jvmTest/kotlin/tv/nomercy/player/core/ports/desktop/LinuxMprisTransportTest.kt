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

// What a Linux desktop reads off the session bus.
//
// The units are the whole reason this is tested rather than eyeballed. MPRIS
// counts in microseconds and everything else here counts in milliseconds, and a
// factor of a thousand in either direction is a track that appears to last
// seventeen minutes or a seek that lands past the end of every song.
class LinuxMprisTransportTest {

    private class FakeBus : MprisBus {
        var metadata: Map<String, Any> = emptyMap()
        var published: String? = null
        var position: Long = -1
        var controls: Set<MprisMethod> = emptySet()
        var handler: ((MprisMethod, Long) -> Unit)? = null
        var unexported: Int = 0

        override fun publishMetadata(metadata: Map<String, Any>) {
            this.metadata = metadata
        }

        override fun publishStatus(status: String) {
            published = status
        }

        override fun publishPosition(microseconds: Long) {
            position = microseconds
        }

        override fun publishCanControl(methods: Set<MprisMethod>) {
            controls = methods
        }

        override fun onMethodCall(handler: ((MprisMethod, Long) -> Unit)?) {
            this.handler = handler
        }

        override fun unexport() {
            unexported += 1
        }
    }

    private val bus = FakeBus()
    private val transport = LinuxMprisTransport(bus)

    @Test
    fun theTrackIsPublishedUnderTheKeysTheSpecificationNames() {
        transport.setNowPlaying(
            NowPlaying(title = TRACK_TITLE, artist = TRACK_ARTIST, album = "Season 1", durationMs = 200_000),
        )

        assertEquals(TRACK_TITLE, bus.metadata["xesam:title"])
        assertEquals("Season 1", bus.metadata["xesam:album"])
    }

    @Test
    fun theArtistIsAListBecauseTheSpecificationSaysSo() {
        // A desktop reading a bare string here shows nothing at all rather than
        // failing, which is the worst way for this to be wrong: it looks like a
        // track with no artist.
        transport.setNowPlaying(NowPlaying(title = TRACK_TITLE, artist = TRACK_ARTIST))

        assertEquals(listOf(TRACK_ARTIST), bus.metadata[ARTIST_KEY])
    }

    @Test
    fun theLengthIsPublishedInMicroseconds() {
        transport.setNowPlaying(NowPlaying(title = TRACK_TITLE, durationMs = 200_000))

        assertEquals(200_000_000L, bus.metadata["mpris:length"])
    }

    @Test
    fun aFieldWithNothingInItIsLeftOutRatherThanPublishedEmpty() {
        // A desktop draws the key it was given. An empty artist is a blank line
        // under the title where no line should be.
        transport.setNowPlaying(NowPlaying(title = TRACK_TITLE))

        assertFalse(bus.metadata.containsKey(ARTIST_KEY))
        assertFalse(bus.metadata.containsKey("mpris:artUrl"))
        assertFalse(bus.metadata.containsKey("mpris:length"))
    }

    @Test
    fun theStatusStringsAreTheOnesEveryDesktopMatchesOn() {
        assertEquals("Playing", mprisStatusOf(TransportPlaybackState.PLAYING))
        assertEquals("Paused", mprisStatusOf(TransportPlaybackState.PAUSED))
        assertEquals(STOPPED, mprisStatusOf(TransportPlaybackState.STOPPED))
    }

    @Test
    fun thePositionIsPublishedInMicrosecondsToo() {
        transport.setPlaybackState(TransportPlaybackState.PLAYING, positionMs = 12_500, playbackRate = 1.0)

        assertEquals(12_500_000L, bus.position)
    }

    @Test
    fun aSeekArrivingFromTheDesktopIsConvertedBack() {
        // The direction that actually breaks playback. A client passing the
        // number through unconverted seeks a thousand times too far and lands at
        // the end of every track.
        var seekedToMs: Long = -1
        transport.setActionHandlers(TransportActions(onSeekTo = { seekedToMs = it }))

        bus.handler?.invoke(MprisMethod.SET_POSITION, 42_000_000L)

        assertEquals(42_000L, seekedToMs)
    }

    @Test
    fun everyMethodReachesTheHandlerItBelongsTo() {
        val called: MutableList<String> = mutableListOf()
        transport.setActionHandlers(
            TransportActions(
                onPlay = { called += PLAY },
                onPause = { called += "pause" },
                onStop = { called += "stop" },
                onNext = { called += NEXT },
                onPrevious = { called += "previous" },
            ),
        )

        listOf(MprisMethod.PLAY, MprisMethod.PAUSE, MprisMethod.NEXT, MprisMethod.PREVIOUS, MprisMethod.STOP)
            .forEach { bus.handler?.invoke(it, 0) }

        assertEquals(listOf(PLAY, "pause", NEXT, "previous", "stop"), called)
    }

    @Test
    fun onlyTheControlsThatAreWiredAreAdvertised() {
        // The desktop greys out what it is told cannot be done. Advertising a
        // control with no handler is a button that does nothing.
        transport.setActionHandlers(TransportActions(onPlay = {}, onSeekTo = {}))

        assertEquals(setOf(MprisMethod.PLAY, MprisMethod.SEEK), bus.controls)
    }

    @Test
    fun aMethodWithNoHandlerIsIgnoredRatherThanCrashing() {
        // The bus can deliver a call for something we said we could not do, and
        // an exception on its thread takes the export down with it.
        transport.setActionHandlers(TransportActions(onPlay = {}))

        bus.handler?.invoke(MprisMethod.NEXT, 0)
    }

    @Test
    fun clearingEmptiesTheMetadataRatherThanLeavingTheLastTrack() {
        transport.setNowPlaying(NowPlaying(title = TRACK_TITLE))

        transport.clear()

        assertEquals(emptyMap(), bus.metadata)
        assertEquals(STOPPED, bus.published)
    }

    @Test
    fun releasingStopsAnsweringBeforeTheNameGoesAway() {
        transport.setActionHandlers(TransportActions(onPlay = {}))

        transport.release()

        assertNull(bus.handler)
        assertEquals(1, bus.unexported)
    }
}

private const val TRACK_TITLE = "Rail Wars"
private const val TRACK_ARTIST = "Nomad"
private const val ARTIST_KEY = "xesam:artist"
private const val STOPPED = "Stopped"
private const val PLAY = "play"
private const val NEXT = "next"
private const val SEEK = "seek"
