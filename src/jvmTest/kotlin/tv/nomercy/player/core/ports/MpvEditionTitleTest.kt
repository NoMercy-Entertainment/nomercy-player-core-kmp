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

// The five titles a real libmpv wrote for Apple's bipbop ladder, verbatim.
//
// Copied out of a run against the engine rather than composed here, because the
// thing this parser gets wrong is always a shape nobody imagined: the first
// version read the audio sample rate as the bitrate on every one of these.
private val BIPBOP: List<String> = listOf(
    "Bitrate: 232 kbps (h264 [Main] 400x300 29.9167 fps) (aac [LC] 2ch 22050 Hz)",
    "Bitrate: 650 kbps (h264 [Main] 640x480 29.9167 fps) (aac [LC] 2ch 22050 Hz)",
    "Bitrate: 992 kbps (h264 [Main] 640x480 29.9167 fps) (aac [LC] 2ch 22050 Hz)",
    "Bitrate: 1.928 Mbps (h264 [Main] 960x720 29.9167 fps 1928 kbps) (aac [LC] 2ch 22050 Hz 1928 kbps)",
    "Bitrate: 41 kbps (aac [LC] 2ch 22050 Hz 6 kbps)",
)

class MpvEditionTitleTest {

    @Test
    fun theSampleRateIsNotTheBitrate() {
        // 22050 Hz appears in every one of these. Read as a bitrate it makes
        // five renditions cost the same 22 kbit, and adaptation then picks the
        // tallest whatever the connection is doing.
        val bitrates: List<Int> = BIPBOP.map { title -> MpvEditionTitle.parse(title).bitrate }

        assertEquals(listOf(232_000, 650_000, 992_000, 1_928_000, 41_000), bitrates)
    }

    @Test
    fun theDimensionsAreTheVideosNotTheFrameRate() {
        val sizes: List<Pair<Int?, Int>> = BIPBOP.map { title ->
            val level: QualityLevel = MpvEditionTitle.parse(title)
            level.width to level.height
        }

        assertEquals(
            listOf(400 to 300, 640 to 480, 640 to 480, 960 to 720, null to 0),
            sizes,
        )
    }

    @Test
    fun anAudioOnlyRungIsARungWithNoPicture() {
        // bipbop's last variant carries no video at all. It has to survive as a
        // rung — dropping it would hide a stream the master declares — and it
        // has to be the SMALLEST one, or adaptation on a poor connection would
        // rank it above a real picture.
        val audioOnly: QualityLevel = MpvEditionTitle.parse(BIPBOP.last())

        assertEquals(0, audioOnly.height)
        assertEquals("aac", audioOnly.codec)
    }

    @Test
    fun aTitleWithNothingInItIsARungThatIsNotPriced() {
        val bare: QualityLevel = MpvEditionTitle.parse("")

        assertEquals(0, bare.bitrate)
        assertEquals(0, bare.height)
        assertEquals(null, bare.label)
    }

    @Test
    fun aBitrateInMegabitsAndGigabitsScales() {
        assertEquals(2_000_000, MpvEditionTitle.parse("Bitrate: 2 Mbps (h264 1920x1080)").bitrate)
        assertEquals(1_000_000_000, MpvEditionTitle.parse("Bitrate: 1 Gbps (h264 1920x1080)").bitrate)
    }
}
