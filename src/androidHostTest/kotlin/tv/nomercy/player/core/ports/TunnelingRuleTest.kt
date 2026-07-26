// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The tunneling truth table.
//
// Every row is a device that broke. Writing them out is the point: the rule
// reads as three arbitrary conditions until each one is named for the failure
// it prevents.
class TunnelingRuleTest {

    private fun tunnel(isTv: Boolean, hls: Boolean = false, refused: Boolean = false): Boolean =
        TunnelingRule.shouldTunnel(isTv = isTv, sourceIsHls = hls, refusedByAudioSink = refused)

    @Test
    fun aTelevisionPlayingAProgressiveFileTunnels() {
        // The case it exists for: 4K HDR on hardware that cannot keep up with
        // frames passing through the app.
        assertTrue(tunnel(isTv = true))
    }

    @Test
    fun aPhoneNeverTunnels() {
        // No tunnel surface contract with a phone display. Enabling it there is
        // not a slower path, it is a black picture.
        assertFalse(tunnel(isTv = false))
        assertFalse(tunnel(isTv = false, hls = true))
        assertFalse(tunnel(isTv = false, refused = true))
    }

    @Test
    fun aTelevisionPlayingHlsDoesNotTunnel() {
        // HLS switches representation mid-stream and a tunneled decoder cannot
        // be reconfigured without tearing down the surface. The symptom is a
        // freeze at the first quality change, not a failure to start — which is
        // why it took a while to find.
        assertFalse(tunnel(isTv = true, hls = true))
    }

    @Test
    fun aTelevisionWhoseAudioSinkRefusedDoesNotTryAgain() {
        // Tunneling needs an AudioTrack with hardware A/V sync and some audio
        // HALs will not open one. A device that said no will say no every time,
        // so retrying means failing at the first frame of every item.
        assertFalse(tunnel(isTv = true, refused = true))
    }

    @Test
    fun aRefusalOutranksEverythingElseThatWouldAllowIt() {
        assertFalse(tunnel(isTv = true, hls = false, refused = true))
    }

    @Test
    fun everyCombinationIsDecidedRatherThanLeftToChance() {
        // Eight rows, one assertion. A condition added later without a row here
        // would leave a combination nobody chose.
        val expected: Map<Triple<Boolean, Boolean, Boolean>, Boolean> = mapOf(
            Triple(true, false, false) to true,
            Triple(true, false, true) to false,
            Triple(true, true, false) to false,
            Triple(true, true, true) to false,
            Triple(false, false, false) to false,
            Triple(false, false, true) to false,
            Triple(false, true, false) to false,
            Triple(false, true, true) to false,
        )

        for ((inputs, want) in expected) {
            val (isTv, hls, refused) = inputs
            assertEquals(want, tunnel(isTv, hls, refused), "tv=$isTv hls=$hls refused=$refused")
        }
    }

    @Test
    fun anAudioTrackInitFailureWhileTunnelingIsARefusal() {
        assertTrue(
            TunnelingRule.isTunnelingRefusal(
                errorCode = TunnelingRule.AUDIO_TRACK_INIT_FAILED,
                tunnelingWasEnabled = true,
            ),
        )
    }

    @Test
    fun theSameFailureWithoutTunnelingIsSomethingElse() {
        // A device with no audio route fails the same way, and turning off a
        // feature that was already off would hide the real problem.
        assertFalse(
            TunnelingRule.isTunnelingRefusal(
                errorCode = TunnelingRule.AUDIO_TRACK_INIT_FAILED,
                tunnelingWasEnabled = false,
            ),
        )
    }

    @Test
    fun aDecoderErrorIsNotARefusal() {
        // This check has to run before any decoder-error handling, because the
        // refusal's message contains "codec" and a substring match sends it down
        // the rebuild path — which rebuilds with the same tunneling parameters
        // and fails again identically.
        assertFalse(TunnelingRule.isTunnelingRefusal(errorCode = 4001, tunnelingWasEnabled = true))
    }
}
