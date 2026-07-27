// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.ports.fakes.FakeSystemTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The shape of the conversation between a player and an operating system.
//
// Four platforms implement this and no two of them agree on anything below it.
// What is pinned here is the part they do share, because a contract that only
// one implementation has ever been held to is a contract nobody has tested.
class SystemTransportContractTest {

    @Test
    fun whatIsPlayingReachesTheSystemIntact() {
        val transport = FakeSystemTransport()

        transport.setNowPlaying(
            NowPlaying(
                title = "Blade Runner 2049",
                artist = "Denis Villeneuve",
                album = "Warner Bros.",
                artworkUrl = "https://cdn.example.test/poster.jpg",
                durationMs = 9_000_000,
            ),
        )

        assertEquals("Blade Runner 2049", transport.lastNowPlaying?.title)
        assertEquals("https://cdn.example.test/poster.jpg", transport.lastNowPlaying?.artworkUrl)
        assertEquals(9_000_000, transport.lastNowPlaying?.durationMs)
    }

    @Test
    fun anItemWithNoArtistOrArtworkIsStillAnnounceable() {
        // A local file with no metadata beyond its name. Requiring the optional
        // fields would mean the one case with least information is the one that
        // cannot reach the lock screen at all.
        val transport = FakeSystemTransport()

        transport.setNowPlaying(NowPlaying(title = "holiday-2019.mp4"))

        assertEquals("holiday-2019.mp4", transport.lastNowPlaying?.title)
        assertEquals(null, transport.lastNowPlaying?.artworkUrl)
    }

    @Test
    fun howItIsGoingReachesTheSystemInMilliseconds() {
        val transport = FakeSystemTransport()

        transport.setPlaybackState(TransportPlaybackState.PLAYING, positionMs = 12_000, playbackRate = 1.0)

        assertEquals(TransportPlaybackState.PLAYING, transport.lastState)
        assertEquals(12_000, transport.lastPositionMs)
        assertEquals(1.0, transport.lastPlaybackRate)
    }

    @Test
    fun aButtonPressedOnTheLockScreenReachesTheHandlerThatWasRegistered() {
        val transport = FakeSystemTransport()
        var played = false
        var seekedTo: Long = -1

        transport.setActionHandlers(
            TransportActions(
                onPlay = { played = true },
                onSeekTo = { positionMs -> seekedTo = positionMs },
            ),
        )
        transport.simulateOsAction("play")
        transport.simulateOsSeek(30_000)

        assertTrue(played, "the play button reached nothing")
        assertEquals(30_000, seekedTo)
    }

    @Test
    fun anActionWithNoHandlerIsNotOfferedAndDoesNothing() {
        // Null means unsupported, and the platforms read it that way: a control
        // with no handler is hidden rather than shown doing nothing. A film has
        // no next episode, and a next button that silently fails is worse than
        // one that was never drawn.
        val transport = FakeSystemTransport()

        transport.setActionHandlers(TransportActions(onPlay = {}))

        assertEquals(setOf("play"), transport.offeredActions())
        transport.simulateOsAction("next")
    }

    @Test
    fun clearingIsNotReleasing() {
        // Two different moments. Nothing is playing any more, against this
        // transport will never be used again — a platform that tore its session
        // down on the first would have to build another for the next item, and
        // rebuilding an Android session is a notification that flickers away and
        // comes back.
        val transport = FakeSystemTransport()

        transport.clear()

        assertTrue(transport.cleared)
        assertFalse(transport.released, "clearing released the transport as well")
    }

    @Test
    fun aPlatformWithNoIntegrationYetAcceptsEverythingAndDoesNothing() {
        // Every platform starts here. A player on one still plays; it simply
        // does not appear on a notification shade, which is the truth rather
        // than a stub standing in for one.
        val transport: SystemTransport = NoSystemTransport()

        transport.setNowPlaying(NowPlaying(title = "anything"))
        transport.setPlaybackState(TransportPlaybackState.PLAYING, 0, 1.0)
        transport.setActionHandlers(TransportActions())
        transport.clear()
        transport.release()
    }

    @Test
    fun everyPlatformThisBuildsForHasATransport() {
        // The factory resolves per target, so this asserts on Android that the
        // Android one exists, on iOS the Apple one, and so on. A target that
        // never wired an actual fails to compile rather than at runtime, and
        // this is what says the resolved one is usable.
        val transport: SystemTransport = defaultSystemTransport()

        transport.setPlaybackState(TransportPlaybackState.STOPPED, 0, 1.0)
    }
}
