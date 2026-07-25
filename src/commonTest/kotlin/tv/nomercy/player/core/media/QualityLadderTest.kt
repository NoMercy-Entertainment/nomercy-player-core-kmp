// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.ports.CapabilitiesProbe
import tv.nomercy.player.core.ports.DecodeCapability
import tv.nomercy.player.core.ports.DecodeProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A device with opinions. Real probes have them too; a permissive one that says
// yes to everything is the thing this filter exists to stop being.
private class OpinionatedProbe(
    private val unsupportedCodecs: Set<String> = emptySet(),
    private val unsmoothAbove: Int = Int.MAX_VALUE,
) : CapabilitiesProbe {
    val asked: MutableList<DecodeProfile> = mutableListOf()

    override suspend fun canDecode(profile: DecodeProfile): DecodeCapability {
        asked += profile
        val supported = profile.contentType !in unsupportedCodecs
        return DecodeCapability(
            supported = supported,
            smooth = supported && (profile.height ?: 0) <= unsmoothAbove,
            powerEfficient = supported && (profile.height ?: 0) <= unsmoothAbove,
        )
    }

    override suspend fun supportedCodecs(): List<String> = emptyList()
}

class QualityLadderTest {

    private val sd = QualityDescriptor(480, 1_000_000, codec = "h264")
    private val hd = QualityDescriptor(1080, 6_000_000, codec = "h264")
    private val hdHdr = QualityDescriptor(1080, 6_000_000, DynamicRange.Hdr10, codec = "hevc")
    private val uhd = QualityDescriptor(2160, 20_000_000, codec = "av1")

    @Test
    fun twoRenditionsWithTheSameFourFieldsAreTheSameRendition() {
        assertEquals(
            QualityDescriptor(1080, 6_000_000, DynamicRange.Sdr, "h264"),
            QualityDescriptor(1080, 6_000_000, DynamicRange.Sdr, "h264"),
        )
    }

    @Test
    fun anHdrAndAnSdrVariantAtTheSameHeightAndBitrateAreNotTheSame() {
        // Picking the wrong one is visible on screen, so dynamic range is part
        // of the identity rather than a decoration hanging off it.
        assertNotEquals(hd, hdHdr)
        assertNotEquals(hd.hashCode(), hdHdr.hashCode())
    }

    @Test
    fun aLadderSortsTheSameWhicheverOrderTheEngineHandedItOver() {
        val fromExo = QualityLadder(listOf(uhd, sd, hdHdr, hd))
        val fromAvPlayer = QualityLadder(listOf(hd, uhd, hdHdr, sd))
        val fromVlc = QualityLadder(listOf(sd, hd, hdHdr, uhd))

        assertEquals(fromExo, fromAvPlayer)
        assertEquals(fromAvPlayer, fromVlc)
        assertEquals(listOf(sd, hd, hdHdr, uhd), fromExo.descriptors)
    }

    @Test
    fun aDuplicateRungIsOneRung() {
        assertEquals(1, QualityLadder(listOf(hd, hd.copy())).size())
    }

    @Test
    fun aRungTheDeviceCannotDecodeLeavesTheLadder() = runTest {
        val probe = OpinionatedProbe(unsupportedCodecs = setOf("av1"))
        val ladder = QualityLadder(listOf(sd, hd, uhd))

        val usable = ladder.usable(probe)

        assertEquals(listOf(sd, hd), usable.map { it.descriptor })
        assertEquals(3, probe.asked.size)
    }

    @Test
    fun aRungTheDeviceCanDecodeButNotSmoothlyIsKeptAndMarked() = runTest {
        val probe = OpinionatedProbe(unsmoothAbove = 1080)
        val ladder = QualityLadder(listOf(sd, hd, uhd))

        val usable = ladder.usable(probe)

        // A stuttering 4K rung on a big screen is still a choice someone may
        // want. Dropping it silently is not this library's decision to make.
        assertEquals(3, usable.size)
        assertTrue(usable.single { it.descriptor == uhd }.let { !it.smooth })
        assertTrue(usable.single { it.descriptor == hd }.smooth)
    }

    @Test
    fun aDeviceThatRejectsEverythingLeavesAnEmptyLadderRatherThanAGuess() = runTest {
        val probe = OpinionatedProbe(unsupportedCodecs = setOf("h264", "hevc", "av1"))
        val ladder = QualityLadder(listOf(sd, hd, hdHdr, uhd))

        val usable = ladder.usable(probe)

        // Empty is the caller's cue to raise core:media/codec-unsupported. It
        // must never fall back to playing something and seeing what happens.
        assertTrue(usable.isEmpty())
    }

    @Test
    fun theBestSmoothRungIsTheHighestOneTheDeviceKeepsUpWith() = runTest {
        val ladder = QualityLadder(listOf(sd, hd, uhd))

        val usable = ladder.usable(OpinionatedProbe(unsmoothAbove = 1080))

        assertEquals(hd, ladder.bestSmooth(usable))
    }

    @Test
    fun nothingSmoothMeansNoAnswerRatherThanTheLeastBadOne() = runTest {
        val ladder = QualityLadder(listOf(uhd))

        val usable = ladder.usable(OpinionatedProbe(unsmoothAbove = 480))

        // Null is the caller's cue to ask the viewer, not to guess for them.
        assertNull(ladder.bestSmooth(usable))
    }

    @Test
    fun anEmptyLadderIsEmptyRatherThanNull() {
        assertTrue(QualityLadder(emptyList()).isEmpty())
        assertFalse(QualityLadder(listOf(hd)).isEmpty())
    }

    @Test
    fun theLabelIsForPeopleAndSaysWhenARungCarriesMoreRange() {
        assertEquals("1080p", hd.label())
        assertEquals("1080p HDR10", hdHdr.label())
        assertEquals("2160p", uhd.label())
    }

    @Test
    fun everyDynamicRangeTokenRoundTrips() {
        DynamicRange.entries.forEach { assertEquals(it, DynamicRange.fromToken(it.token)) }
    }
}
