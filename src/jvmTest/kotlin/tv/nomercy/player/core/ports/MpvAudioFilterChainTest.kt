// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.dsp.EqBand
import kotlin.test.Test
import kotlin.test.assertEquals

// No mpv, no audio hardware needed — this is the pure half of P19.9's desktop
// EQ, the part a headless test CAN assert. The native half (MpvDspGraph
// actually reaching mpv through the property setter) is compile-verified
// only; a real speaker is what proves the sound changed.
class MpvAudioFilterChainTest {

    @Test
    fun disabledOrEmptyProducesNoFilterChain() {
        assertEquals("", MpvAudioFilterChain.build(emptyList(), preGain = 1.0, enabled = true))
        assertEquals(
            "",
            MpvAudioFilterChain.build(listOf(EqBand(1000, 4.0, 1.0)), preGain = 1.0, enabled = false),
        )
    }

    @Test
    fun zeroGainBandsAreOmitted() {
        val bands = listOf(EqBand(60, 0.0, 1.0), EqBand(1000, 0.0, 1.0))
        assertEquals("", MpvAudioFilterChain.build(bands, preGain = 1.0, enabled = true))
    }

    @Test
    fun oneBoostedBandBecomesOneEqualizerStage() {
        val bands = listOf(EqBand(1000, 4.0, 1.0))
        assertEquals(
            "lavfi=[equalizer=f=1000:width_type=q:w=1:g=4]",
            MpvAudioFilterChain.build(bands, preGain = 1.0, enabled = true),
        )
    }

    @Test
    fun multipleBandsChainInOrderSeparatedByCommas() {
        val bands = listOf(EqBand(60, 3.0, 1.0), EqBand(1000, -2.0, 0.7), EqBand(8000, 5.0, 1.4))
        assertEquals(
            "lavfi=[equalizer=f=60:width_type=q:w=1:g=3,equalizer=f=1000:width_type=q:w=0.7:g=-2,equalizer=f=8000:width_type=q:w=1.4:g=5]",
            MpvAudioFilterChain.build(bands, preGain = 1.0, enabled = true),
        )
    }

    @Test
    fun unityPreGainAddsNoVolumeStage() {
        val bands = listOf(EqBand(1000, 2.0, 1.0))
        assertEquals(
            "lavfi=[equalizer=f=1000:width_type=q:w=1:g=2]",
            MpvAudioFilterChain.build(bands, preGain = 1.0, enabled = true),
        )
    }

    @Test
    fun nonUnityPreGainLeadsTheChainAsAVolumeStage() {
        val bands = listOf(EqBand(1000, 2.0, 1.0))
        assertEquals(
            "lavfi=[volume=0.5,equalizer=f=1000:width_type=q:w=1:g=2]",
            MpvAudioFilterChain.build(bands, preGain = 0.5, enabled = true),
        )
    }

    @Test
    fun preGainAloneWithNoBoostedBandsStillChains() {
        val bands = listOf(EqBand(1000, 0.0, 1.0))
        assertEquals(
            "lavfi=[volume=1.5]",
            MpvAudioFilterChain.build(bands, preGain = 1.5, enabled = true),
        )
    }

    @Test
    fun disabledDropsBandsButKeepsPreGain() {
        val bands = listOf(EqBand(1000, 4.0, 1.0))
        assertEquals(
            "lavfi=[volume=0.8]",
            MpvAudioFilterChain.build(bands, preGain = 0.8, enabled = false),
        )
    }

    @Test
    fun smallGainFormatsAsPlainDecimalNeverScientificNotation() {
        val bands = listOf(EqBand(1000, -0.0001, 1.0))
        // FFmpeg's filter-graph parser reads "g=-1.0E-4" as a syntax error, not
        // as a very small number — this proves the formatter never emits one.
        assertEquals(
            "lavfi=[equalizer=f=1000:width_type=q:w=1:g=-0.0001]",
            MpvAudioFilterChain.build(bands, preGain = 1.0, enabled = true),
        )
    }
}
