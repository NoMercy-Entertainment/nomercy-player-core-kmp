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
import kotlin.test.assertTrue

// The divergences the full surface forced, as assertions rather than as prose.
//
// P09's verdict was written about transport — load, play, pause, time — and one
// interface fitted three engines there. The full surface is where they stop
// agreeing, and a table in a document goes stale the first time someone changes
// a mapper. These are the same statements, in a form that fails when they stop
// being true.
//
// Nothing here drives an engine. Each assertion is about a decision the library
// makes on that engine's behalf, which is exactly the part a document was being
// asked to remember.
class FullSurfaceDivergenceTest {

    // One real edition title, as libmpv writes them. Copied from a run against
    // Apple's bipbop ladder rather than composed, because what this file is
    // about is what the engines ACTUALLY report.
    private val EDITION_TITLE = "Bitrate: 650 kbps (h264 [Main] 640x480 29.9167 fps) (aac [LC] 2ch 22050 Hz)"


    private val mixedLadder = listOf(
        QualityLevel(height = 2160, bitrate = 20_000_000, codec = "hvc1", dynamicRange = DynamicRange.HDR10),
        QualityLevel(height = 1080, bitrate = 6_000_000, codec = "h264", dynamicRange = DynamicRange.SDR),
        QualityLevel(height = 720, bitrate = 3_000_000, codec = "h264", dynamicRange = DynamicRange.SDR),
    )

    @Test
    fun pinningMeansAnExactTrackOnTwoEnginesAndACeilingOnApple() {
        // The first divergence the full surface forced, and the one a consumer
        // will notice. Media3 and libmpv select a variant; AVFoundation caps a
        // bitrate and keeps adapting below it. A chrome that shows "1080p" as a
        // committed choice is telling the truth on two platforms out of three.
        //
        // Asserted through the matcher, which is what both selecting engines use
        // to turn a descriptor into their own index.
        val target: QualityLevel = mixedLadder[1]

        assertEquals(1, QualityMatcher.match(target, mixedLadder), "the selecting engines cannot resolve a rung")
    }

    @Test
    fun onlyMedia3CanTellHdrFromSdr() {
        // Media3 reads the transfer function per track. The desktop reads its
        // ladder out of an HLS master's editions, and an edition title carries
        // a resolution and a bitrate and nothing about range — so every desktop
        // RUNG reports SDR and an HDR ladder is indistinguishable from an SDR
        // one before anything is playing.
        //
        // mpv can say what the PLAYING stream is, through video-params, which
        // libVLC could not say at all. That is a different question from which
        // rung to climb to, and only the ladder question is asked here.
        assertEquals(DynamicRange.SDR, MpvEditionTitle.parse(EDITION_TITLE).dynamicRange)
    }

    @Test
    fun theHdrConstraintOnlyExistsWhereTheRangeIsKnown() {
        // Follows from the above rather than being a separate choice: a cap needs
        // to know which rungs are HDR, and on the desktop nothing is.
        val asDesktopSeesIt: List<QualityLevel> = mixedLadder.map {
            it.copy(dynamicRange = MpvEditionTitle.parse(EDITION_TITLE).dynamicRange)
        }

        assertEquals(
            null,
            HdrAbrConstraint.abrCeiling(asDesktopSeesIt, displayHdr = false),
            "a ladder that reports all-SDR was capped, which cannot be right",
        )
        assertTrue(
            HdrAbrConstraint.abrCeiling(mixedLadder, displayHdr = false) != null,
            "a ladder that reports HDR was not capped",
        )
    }

    @Test
    fun codecNamesReachTheSameFamilyFromThreeVocabularies() {
        // The divergence that would silently break a preference restored across
        // platforms. A desktop engine names a codec h264 or hevc; a manifest
        // says avc1.640028 and hvc1.2.4.L153.B0; the library compares families.
        assertEquals("h264", MpvEditionTitle.parse(EDITION_TITLE).codec)
        // Media3's half of this is asserted in the Android host tests, where
        // its mapper is visible. A divergence report split across two source
        // sets is the honest shape when the things diverging are per-platform.
    }

    @Test
    fun everyEngineReportsAnUnknownAsSomethingSortable() {
        // Each engine has its own sentinel for "I do not know yet" — Media3's
        // NO_VALUE, AVFoundation's NaN, and on the desktop a property that is
        // simply absent — and each one sorts a ladder or draws a scrubber
        // wrongly if it escapes. The desktop's is visible from here as a rung
        // whose title priced nothing; Apple's NaN is asserted in
        // AVTrackMapperTest and Media3's NO_VALUE in ExoTrackMapperTest.
        assertEquals(0, MpvEditionTitle.parse("no bitrate here (h264 640x480)").bitrate)
    }

    @Test
    fun theCrossfadeIsOneImplementationOnAllThree() {
        // The thing that did NOT diverge, and the reason: the fade is shared and
        // each engine contributes only a gain and a play. Asserted by the curve's
        // own property, which is what all three inherit.
        val curve = CrossfadeCurve.EQUAL_POWER
        val midpoint: Double = curve.gain(HALF) * curve.gain(HALF) + curve.gain(1.0 - HALF) * curve.gain(1.0 - HALF)

        assertTrue(midpoint > 1.0 - TOLERANCE && midpoint < 1.0 + TOLERANCE, "power moved at the midpoint")
    }

    private companion object {
        const val HALF = 0.5
        const val TOLERANCE = 0.01
    }
}
