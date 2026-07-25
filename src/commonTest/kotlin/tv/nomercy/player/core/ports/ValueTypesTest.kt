// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val TOLERANCE = 1e-9

class ValueTypesTest {

    @Test
    fun everyWireNameMatchesTheWebContract() {
        assertEquals(
            listOf("idle", "loading", "ready", "playing", "paused", "error"),
            BackendState.entries.map { it.wire },
        )
        assertEquals(listOf("native", "hls", "dash"), StreamKind.entries.map { it.wire })
        assertEquals(
            listOf("idle", "loading", "ready", "playing", "error"),
            StreamSourceState.entries.map { it.wire },
        )
        assertEquals(
            listOf("sdr", "hdr10", "hdr10+", "dolby-vision"),
            DynamicRange.entries.map { it.wire },
        )
        assertEquals(listOf("linear", "equal-power"), CrossfadeCurve.entries.map { it.wire })
    }

    @Test
    fun aQualityIsIdentifiedByWhatItIsNotByWhereItSitsInAList() {
        val first = QualityLevel(height = 1080, bitrate = 6_000_000, codec = "h264")
        val sameFromAnotherLadder = QualityLevel(height = 1080, bitrate = 6_000_000, codec = "h264")

        assertEquals(first, sameFromAnotherLadder)
    }

    @Test
    fun dynamicRangeAndCodecArePartOfAQualitysIdentity() {
        val sdr = QualityLevel(height = 1080, bitrate = 6_000_000, codec = "h264")

        // Same size, same bitrate, different stream — and a device that plays
        // one may not play the other.
        assertNotEquals(sdr, sdr.copy(dynamicRange = DynamicRange.HDR10))
        assertNotEquals(sdr, sdr.copy(codec = "hevc"))
    }

    @Test
    fun anAudioTrackIsKeyedByLanguageAndLabelTogether() {
        val english = AudioTrack(id = "0", language = "en", label = "English")

        // Both are English; a viewer picks between them by label.
        assertNotEquals(english, english.copy(label = "Commentary"))
    }

    @Test
    fun theEqualPowerCurveHitsItsAnchorPoints() {
        assertTrue(abs(CrossfadeCurve.EQUAL_POWER.gain(0.0)) < TOLERANCE)
        assertTrue(abs(CrossfadeCurve.EQUAL_POWER.gain(1.0) - 1.0) < TOLERANCE)
        assertTrue(abs(CrossfadeCurve.EQUAL_POWER.gain(0.5) - cos(0.25 * PI)) < TOLERANCE)
    }

    @Test
    fun theEqualPowerCurveHoldsConstantPowerAcrossTheWholeFade() {
        // in² + out² == 1 everywhere. This is the whole reason the curve exists:
        // two linear ramps sum to less power in the middle and the mix dips.
        var progress = 0.0
        while (progress <= 1.0) {
            val fadingIn: Double = CrossfadeCurve.EQUAL_POWER.gain(progress)
            val fadingOut: Double = CrossfadeCurve.EQUAL_POWER.gain(1.0 - progress)
            val power: Double = fadingIn * fadingIn + fadingOut * fadingOut
            assertTrue(abs(power - 1.0) < TOLERANCE, "power was $power at progress $progress")
            progress += 0.05
        }
    }

    @Test
    fun aLinearCurveIsTheIdentityAndBothCurvesClamp() {
        assertEquals(0.5, CrossfadeCurve.LINEAR.gain(0.5))

        // A late frame reporting progress past the end must not push gain above 1.
        assertEquals(1.0, CrossfadeCurve.LINEAR.gain(2.0))
        assertEquals(0.0, CrossfadeCurve.LINEAR.gain(-1.0))
        // The cosine bottoms out at 6e-17 rather than a hard zero, which is
        // 300dB below anything audible — so the assertion is a tolerance, not a
        // claim the curve returns an exact 0.
        assertTrue(abs(CrossfadeCurve.EQUAL_POWER.gain(2.0) - 1.0) < TOLERANCE)
        assertTrue(abs(CrossfadeCurve.EQUAL_POWER.gain(-1.0)) < TOLERANCE)
    }
}
