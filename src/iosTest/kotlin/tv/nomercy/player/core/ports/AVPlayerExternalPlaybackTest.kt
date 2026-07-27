// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import platform.AVFoundation.AVPlayer
import platform.AVFoundation.allowsExternalPlayback
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The AirPlay wiring on a simulator, which has no speaker to send anything to.
//
// So this asserts the part that is true without a receiver: the port reports
// itself supported, it starts local rather than optimistically claiming a route,
// permission actually reaches the player, and tearing it down is safe from the
// two places that both think they own it.
class AVPlayerExternalPlaybackTest {

    @Test
    fun aniOSPlayerReportsThatItCanSendPlaybackElsewhere() {
        val external = AVPlayerExternalPlayback(AVPlayer())

        assertTrue(external.isSupported)

        external.dispose()
    }

    @Test
    fun itStartsLocalRatherThanClaimingARouteItHasNotGot() {
        // Supported is not in use. A port that reported a route active before one
        // was chosen has chrome drawing "playing on Kitchen" over an empty room.
        val external = AVPlayerExternalPlayback(AVPlayer())

        val state = external.state.value as ExternalPlaybackState.Available

        assertFalse(state.active)
        assertNull(state.activeRoute)
        external.dispose()
    }

    @Test
    fun forbiddingItReachesThePlayerRatherThanJustTheState() {
        // A licence that forbids external playback has to stop the platform
        // offering the route, not stop this library drawing a button. The system
        // sheet is drawn by iOS and will happily offer a route we only pretended
        // to refuse.
        val player = AVPlayer()
        val external = AVPlayerExternalPlayback(player)

        external.setAllowed(false)

        assertFalse(player.allowsExternalPlayback)
        external.dispose()
    }

    @Test
    fun allowingItAgainPutsTheRouteBack() {
        val player = AVPlayer()
        val external = AVPlayerExternalPlayback(player)
        external.setAllowed(false)

        external.setAllowed(true)

        assertTrue(player.allowsExternalPlayback)
        external.dispose()
    }

    @Test
    fun disposingTwiceIsSafe() {
        // A chrome tearing down a screen and a backend releasing its player both
        // have a claim to call it, and neither knows about the other. Removing
        // an observer twice is a crash on Apple platforms, not a warning.
        val external = AVPlayerExternalPlayback(AVPlayer())

        external.dispose()
        external.dispose()

        assertTrue(external.isSupported)
    }
}
