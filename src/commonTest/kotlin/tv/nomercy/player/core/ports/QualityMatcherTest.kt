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
import kotlin.test.assertNull

// An interleaved SDR/HDR ladder at two heights, which is what a real master
// produces and what makes index selection dangerous: the HDR 1080p rung sits
// between the two SDR ones, so an off-by-one is a viewer who asked for HDR and
// got a different resolution.
private val LADDER = listOf(
    QualityLevel(height = 1080, bitrate = 6_000_000, codec = "h264", dynamicRange = DynamicRange.SDR),
    QualityLevel(height = 1080, bitrate = 6_500_000, codec = "hvc1", dynamicRange = DynamicRange.HDR10),
    QualityLevel(height = 720, bitrate = 3_000_000, codec = "h264", dynamicRange = DynamicRange.SDR),
    QualityLevel(height = 720, bitrate = 3_200_000, codec = "hvc1", dynamicRange = DynamicRange.HDR10),
)

class QualityMatcherTest {

    @Test
    fun everyDescriptorResolvesToItsOwnIndex() {
        // The round trip that the whole descriptor rule rests on. If this drifts
        // by one, every quality selection in the library picks a neighbour.
        LADDER.forEachIndexed { index, level ->
            assertEquals(index, QualityMatcher.match(level, LADDER), "$level resolved to the wrong rung")
        }
    }

    @Test
    fun theSameHeightInADifferentRangeIsADifferentStream() {
        // 1080p SDR and 1080p HDR10 are not interchangeable: a device that
        // cannot decode HDR plays the second as a black screen or not at all.
        val hdr = QualityLevel(height = 1080, bitrate = 6_500_000, codec = "hvc1", dynamicRange = DynamicRange.HDR10)

        assertEquals(1, QualityMatcher.match(hdr, LADDER))
    }

    @Test
    fun aBitrateThatMovedStillFindsItsVariant() {
        // The same rung re-advertised at a slightly different rate — a
        // re-packaged master, a manifest refresh — must not lose the selection.
        val moved = QualityLevel(height = 720, bitrate = 3_100_000, codec = "h264", dynamicRange = DynamicRange.SDR)

        assertEquals(2, QualityMatcher.match(moved, LADDER))
    }

    @Test
    fun theNearestBitrateWinsAmongIdenticalContent() {
        val duplicated = listOf(
            QualityLevel(height = 1080, bitrate = 5_000_000, codec = "h264", dynamicRange = DynamicRange.SDR),
            QualityLevel(height = 1080, bitrate = 8_000_000, codec = "h264", dynamicRange = DynamicRange.SDR),
        )
        val target = QualityLevel(height = 1080, bitrate = 7_600_000, codec = "h264", dynamicRange = DynamicRange.SDR)

        assertEquals(1, QualityMatcher.match(target, duplicated))
    }

    @Test
    fun codecCaseDoesNotChangeTheAnswer() {
        // One engine says hvc1 and another HVC1. A selection that survived a
        // rotation but not an engine swap would be unreproducible.
        val shouted = QualityLevel(height = 720, bitrate = 3_200_000, codec = "HVC1", dynamicRange = DynamicRange.HDR10)

        assertEquals(3, QualityMatcher.match(shouted, LADDER))
    }

    @Test
    fun aVariantTheEngineNoLongerHasIsNullRatherThanNearest() {
        // A live manifest drops rungs. Answering with the nearest would hand a
        // viewer who chose 4K a 720p stream and report success.
        val gone = QualityLevel(height = 2160, bitrate = 20_000_000, codec = "hvc1", dynamicRange = DynamicRange.HDR10)

        assertNull(QualityMatcher.match(gone, LADDER))
    }

    @Test
    fun aDifferentCodecAtTheSameHeightIsNotAMatch() {
        // AV1 1080p and H.264 1080p are different streams, and a device that
        // cannot decode one plays nothing.
        val av1 = QualityLevel(height = 1080, bitrate = 6_000_000, codec = "av01", dynamicRange = DynamicRange.SDR)

        assertNull(QualityMatcher.match(av1, LADDER))
    }

    @Test
    fun anEmptyLadderIsNullRatherThanAnException() {
        // The engine has not read the manifest yet, which is a moment a chrome
        // can easily ask during.
        assertNull(QualityMatcher.match(LADDER.first(), emptyList()))
    }

    @Test
    fun rangeDecidesWhenTheCodecCannot() {
        // The case the first version of this file could not see: every rung in
        // LADDER carries a different codec per range, so codec alone
        // disambiguated and dropping the range check broke nothing. A real
        // ladder often encodes both SDR and HDR in HEVC, and then the range is
        // the only thing separating a stream a device can play from one it
        // cannot.
        val sameCodecBothRanges = listOf(
            QualityLevel(height = 2160, bitrate = 15_000_000, codec = "hvc1", dynamicRange = DynamicRange.SDR),
            QualityLevel(height = 2160, bitrate = 15_000_000, codec = "hvc1", dynamicRange = DynamicRange.HDR10),
        )

        assertEquals(0, QualityMatcher.match(sameCodecBothRanges[0], sameCodecBothRanges))
        assertEquals(1, QualityMatcher.match(sameCodecBothRanges[1], sameCodecBothRanges))
    }

    @Test
    fun aRangeTheLadderDoesNotCarryIsNoMatch() {
        val onlySdr = listOf(
            QualityLevel(height = 2160, bitrate = 15_000_000, codec = "hvc1", dynamicRange = DynamicRange.SDR),
        )
        val dolby = QualityLevel(
            height = 2160,
            bitrate = 15_000_000,
            codec = "hvc1",
            dynamicRange = DynamicRange.DOLBY_VISION,
        )

        assertNull(QualityMatcher.match(dolby, onlySdr))
    }
}
