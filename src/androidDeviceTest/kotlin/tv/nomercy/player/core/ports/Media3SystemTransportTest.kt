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
    fun theRoutingControllerIdReachesTheSessionsDeviceInfo() {
        // The output-switcher chip reads DeviceInfo.routingControllerId to
        // resolve a real device name — without it Android falls back to a
        // generic "Other device" placeholder even on a genuinely remote
        // session. DeviceInfo is only built while a remote volume handler is
        // wired (see getState()'s own gate), so both are set here.
        var routingControllerId: String? = null

        onMainThread {
            val bridge = TransportSimpleBasePlayer()
            bridge.setActions(TransportActions(onVolumeStep = {}, isVolumeRemote = { true }))
            bridge.setNowPlaying(NowPlaying(title = "x"))
            bridge.setRoutingControllerId("route-42")
            routingControllerId = bridge.deviceInfo.routingControllerId
        }

        assertEquals("route-42", routingControllerId)
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
    fun aPushedRemoteVolumeReachesTheSessionsDeviceVolume() {
        // The inbound half, on the real bridge: setRemoteVolume is what a
        // live server frame (via MediaSessionPlugin.publishRemoteVolume) ends
        // up calling, and Media3's own deviceVolume is what the system slider
        // actually reads to draw itself.
        var deviceVolume = -1

        onMainThread {
            val bridge = TransportSimpleBasePlayer()
            bridge.setActions(TransportActions(onVolumeStep = { }, isVolumeRemote = { true }))
            bridge.setRemoteVolume(64)
            deviceVolume = bridge.deviceVolume
        }

        assertEquals(64, deviceVolume, "a pushed remote volume did not reach the session")
    }

    @Test
    fun aDraggedDeviceVolumeSendsTheExactTargetRatherThanANotch() {
        // The bug this closes: a drag across the whole bar (20% to 80%) used
        // to compute only a direction and move the real device by one ±1
        // step. onVolumeSet, when wired, gets the whole target instead.
        var sentPercent = -1

        onMainThread {
            val bridge = TransportSimpleBasePlayer()
            bridge.setActions(
                TransportActions(
                    onVolumeStep = { },
                    onVolumeSet = { percent -> sentPercent = percent },
                    isVolumeRemote = { true },
                ),
            )
            bridge.setDeviceVolume(80, 0)
        }

        assertEquals(80, sentPercent, "a dragged volume was not sent as its own absolute target")
    }

    @Test
    fun withNoAbsoluteHandlerADraggedVolumeStillFallsBackToOneStep() {
        // Non-breaking for a caller that never wires onVolumeSet — the
        // original ±1-per-command behaviour survives unchanged.
        var steppedDirection: Int? = null

        onMainThread {
            val bridge = TransportSimpleBasePlayer()
            bridge.setActions(TransportActions(onVolumeStep = { direction -> steppedDirection = direction }))
            bridge.setDeviceVolume(80, 0)
        }

        assertEquals(1, steppedDirection, "no onVolumeSet should still step by one notch")
    }

    @Test
    fun aRejectedForegroundServiceStartRetriesOnTheNextPlayingTransition() {
        // The bug: startPlaybackService() swallows a real OS rejection
        // (ForegroundServiceStartNotAllowedException — normal for a device
        // backgrounded and only mirroring another device's session) by
        // design, but the caller used to latch servicePromotionRequested
        // shut on the FIRST attempt regardless of whether it succeeded. One
        // rejection while backgrounded then permanently killed the
        // notification for this instance's whole life. Not reproducible via
        // a real OS rejection here — instrumentation itself is normally
        // foreground-exempt — so the platform call is faked instead: reject
        // once, then confirm the very next PLAYING transition tries again.
        var attempts = 0
        var transport: SystemTransport? = null

        onMainThread {
            transport = Media3SystemTransport(
                context(),
                requestForegroundService = { _, _ ->
                    attempts++
                    if (attempts == 1) error("simulated ForegroundServiceStartNotAllowedException")
                },
            )
            transport?.setNowPlaying(NowPlaying(title = "x", durationMs = 1_000))
            transport?.setPlaybackState(TransportPlaybackState.PLAYING, 0, 1.0)
            transport?.setPlaybackState(TransportPlaybackState.PLAYING, 0, 1.0)
        }

        assertEquals(2, attempts, "a rejected start was not retried on the next PLAYING transition")

        onMainThread { transport?.release() }
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
