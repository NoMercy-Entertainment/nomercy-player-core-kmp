// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The Android transport against the real Media3 session, on a device.
//
// A MediaSession cannot be built on a host JVM — it wants a Looper, a Context
// and a notification manager — so everything below is here rather than in a
// host test, and none of it is provable anywhere else.
@UnstableApi
class Media3SystemTransportTest {

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun onMainThread(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    @Test
    fun aSessionIsBuiltAndReleasedWithTheTransport() {
        // The single-owner claim, at its simplest. Two sessions in one process
        // is what the app has today and why a notification's buttons reach
        // whichever service registered last.
        var transport: SystemTransport? = null

        onMainThread { transport = Media3SystemTransport(context()) }
        assertNotNull(transport)

        onMainThread { transport?.release() }
    }

    @Test
    fun whatIsPlayingReachesTheSessionsMetadata() {
        var title: CharSequence? = null

        onMainThread {
            val bridge = TransportSimpleBasePlayer()
            bridge.setNowPlaying(
                NowPlaying(
                    title = "Blade Runner 2049",
                    artist = "Denis Villeneuve",
                    durationMs = 9_000_000,
                ),
            )
            title = bridge.mediaMetadata.title
        }

        assertEquals("Blade Runner 2049", title.toString())
    }

    @Test
    fun playingIsWhatMedia3CallsReadyAndWanting() {
        // Media3 has no "playing": it has a ready state and a play-when-ready
        // flag, and a lock screen showing a pause button is reading both. A
        // bridge that set only one shows a play button on a playing item.
        var ready = false
        var wants = false

        onMainThread {
            val bridge = TransportSimpleBasePlayer()
            bridge.setNowPlaying(NowPlaying(title = "x", durationMs = 1_000))
            bridge.setPlayback(TransportPlaybackState.PLAYING, 12_000)
            ready = bridge.playbackState == androidx.media3.common.Player.STATE_READY
            wants = bridge.playWhenReady
        }

        assertTrue(ready, "the session was not in a ready state")
        assertTrue(wants, "the session did not think playback was wanted")
    }

    @Test
    fun thePositionPushedIsWhereTheSessionCarriesOnFrom() {
        // Approximately, and the approximation is the finding. Media3 reported
        // 12001 for a 12000 push, because a session given a position and told
        // playback is wanted runs the clock on by itself from the moment it was
        // told. That is the behaviour the plugin is built around: it pushes on
        // transitions and on seeks, and lets the session carry the seconds in
        // between rather than telling it what it already knows several times a
        // second.
        var position: Long = -1

        onMainThread {
            val bridge = TransportSimpleBasePlayer()
            bridge.setNowPlaying(NowPlaying(title = "x", durationMs = 100_000))
            bridge.setPlayback(TransportPlaybackState.PLAYING, 12_000)
            position = bridge.currentPosition
        }

        assertTrue(
            position in 12_000..12_500,
            "the session carried on from $position rather than from the 12000 it was given",
        )
    }

    @Test
    fun aPlayCommandFromTheSystemReachesTheHandler() {
        // The inward half. Media3 delivers a transport button as a
        // play-when-ready change, and this is where it becomes the player's.
        var played = false

        onMainThread {
            val bridge = TransportSimpleBasePlayer()
            bridge.setActions(TransportActions(onPlay = { played = true }))
            bridge.setNowPlaying(NowPlaying(title = "x"))
            bridge.playWhenReady = true
        }

        assertTrue(played, "a play from the system reached nothing")
    }

    @Test
    fun aSeekFromTheSystemArrivesAsAPositionInMilliseconds() {
        var seekedTo: Long = -1

        onMainThread {
            val bridge = TransportSimpleBasePlayer()
            bridge.setActions(TransportActions(onSeekTo = { position -> seekedTo = position }))
            bridge.setNowPlaying(NowPlaying(title = "x", durationMs = 100_000))
            bridge.seekTo(30_000)
        }

        assertEquals(30_000, seekedTo)
    }

    @Test
    fun clearingLeavesNothingForTheLockScreenToShow() {
        var title: CharSequence? = "still here"

        onMainThread {
            val bridge = TransportSimpleBasePlayer()
            bridge.setNowPlaying(NowPlaying(title = "Blade Runner 2049"))
            bridge.blank()
            title = bridge.mediaMetadata.title
        }

        assertEquals(null, title)
    }
}
