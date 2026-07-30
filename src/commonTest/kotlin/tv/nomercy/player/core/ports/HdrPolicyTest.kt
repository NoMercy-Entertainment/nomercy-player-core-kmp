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

// What to do about dynamic range on this screen.
//
// HdrAbrConstraint existed, was tested three ways, and was called from no playback
// path — so HDR played on an SDR screen with nothing consulted and nothing
// converted. Built-and-unwired again, and the tests around it were what made it look
// finished.
class HdrPolicyTest {

    @Test
    fun anHdrScreenIsLeftAlone() {
        assertEquals(
            HdrDecision.AsIs,
            hdrDecision(MIXED, displayHdr = true, backendCanToneMap = false),
        )
    }

    @Test
    fun anOrdinarySdrItemIsLeftAlone() {
        assertEquals(
            HdrDecision.AsIs,
            hdrDecision(SDR_ONLY, displayHdr = false, backendCanToneMap = true),
        )
    }

    @Test
    fun anSdrRungWinsOverToneMapping() {
        // Cheaper and correct: a natively-SDR stream costs nothing per frame. Tone-
        // mapping is for the case where there is no rung to pick, not a first resort.
        val decision: HdrDecision = hdrDecision(MIXED, displayHdr = false, backendCanToneMap = true)

        assertEquals(HdrDecision.CapTo(SDR_1080), decision)
    }

    @Test
    fun toneMappingWinsWhenThereIsNoSdrRung() {
        assertEquals(
            HdrDecision.ToneMap,
            hdrDecision(HDR_ONLY, displayHdr = false, backendCanToneMap = true),
        )
    }

    // The two answers the consumer chooses between, and only in the case where
    // neither a rung nor a converter exists.
    @Test
    fun theFallbackDecidesOnlyWhenNothingElseCan() {
        assertEquals(
            HdrDecision.PlayUnconverted,
            hdrDecision(HDR_ONLY, false, backendCanToneMap = false, fallback = HdrOnSdrFallback.Play),
        )
        assertEquals(
            HdrDecision.Refuse,
            hdrDecision(HDR_ONLY, false, backendCanToneMap = false, fallback = HdrOnSdrFallback.Refuse),
        )
    }

    @Test
    fun theFallbackNeverOverridesARungOrAConverter() {
        // Refuse is a last resort, not a preference. A consumer that set it must
        // still get the SDR rung when one exists, or a title with a perfectly good
        // SDR rendition would refuse to play.
        assertEquals(
            HdrDecision.CapTo(SDR_1080),
            hdrDecision(MIXED, false, backendCanToneMap = false, fallback = HdrOnSdrFallback.Refuse),
        )
        assertEquals(
            HdrDecision.ToneMap,
            hdrDecision(HDR_ONLY, false, backendCanToneMap = true, fallback = HdrOnSdrFallback.Refuse),
        )
    }

    @Test
    fun anEmptyLadderIsNotARefusal() {
        // No levels reported is a backend that has not enumerated them yet, or a
        // progressive file with no ladder at all. Refusing there would refuse
        // every single-file item.
        assertEquals(
            HdrDecision.AsIs,
            hdrDecision(emptyList(), false, backendCanToneMap = false, fallback = HdrOnSdrFallback.Refuse),
        )
    }
}

private fun rung(height: Int, bitrate: Int, range: DynamicRange): QualityLevel = QualityLevel(
    height = height,
    bitrate = bitrate,
    codec = if (range == DynamicRange.SDR) "h264" else "hvc1",
    dynamicRange = range,
)

private val SDR_1080 = rung(1080, 6_000_000, DynamicRange.SDR)
private val SDR_720 = rung(720, 3_000_000, DynamicRange.SDR)
private val HDR_1080 = rung(1080, 6_500_000, DynamicRange.HDR10)
private val HDR_2160 = rung(2160, 18_000_000, DynamicRange.HDR10)

private val MIXED: List<QualityLevel> = listOf(HDR_2160, HDR_1080, SDR_1080, SDR_720)
private val SDR_ONLY: List<QualityLevel> = listOf(SDR_1080, SDR_720)
private val HDR_ONLY: List<QualityLevel> = listOf(HDR_2160, HDR_1080)
