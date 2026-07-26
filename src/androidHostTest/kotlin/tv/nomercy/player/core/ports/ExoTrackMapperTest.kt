// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.google.common.collect.ImmutableList
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The mapping from Media3's world to the library's, against formats this test
// builds.
//
// Every field here is one a real manifest fills in and a device would show the
// consequences of getting wrong: a dynamic range read from the codec instead of
// the transfer function, a codec string kept at full RFC 6381 length so the same
// rung never matches itself across two manifests, a null language that leaves a
// menu row with no title.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SDK_UNDER_TEST])
class ExoTrackMapperTest {

    private fun video(
        height: Int,
        bitrate: Int,
        codecs: String,
        mime: String = MimeTypes.VIDEO_H265,
        transfer: Int = C.COLOR_TRANSFER_SDR,
    ): Format = Format.Builder()
        .setSampleMimeType(mime)
        .setCodecs(codecs)
        .setHeight(height)
        .setWidth(height * 16 / 9)
        .setPeakBitrate(bitrate)
        .setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build())
        .build()

    private fun tracksOf(type: Int, vararg formats: Format, selected: Int = -1): Tracks {
        val group = TrackGroup(*formats)
        val supported = IntArray(formats.size) { C.FORMAT_HANDLED }
        val chosen = BooleanArray(formats.size) { it == selected }
        return Tracks(ImmutableList.of(Tracks.Group(group, false, supported, chosen)))
    }

    @Test
    fun aLadderComesBackAsDescriptorsRatherThanPositions() {
        val tracks = tracksOf(
            C.TRACK_TYPE_VIDEO,
            video(height = 1080, bitrate = 6_000_000, codecs = "hvc1.2.4.L153.B0"),
            video(height = 720, bitrate = 3_000_000, codecs = "hvc1.2.4.L120.B0"),
        )

        val levels: List<QualityLevel> = ExoTrackMapper.qualityLevels(tracks)

        assertEquals(listOf(1080, 720), levels.map { it.height })
        assertEquals(listOf(6_000_000, 3_000_000), levels.map { it.bitrate })
    }

    @Test
    fun theCodecIsTheFamilyRatherThanTheWholeDescriptor() {
        // "hvc1.2.4.L153.B0" is what a manifest carries. Keeping the profile
        // makes the same rung unmatchable across two manifests of one film,
        // because the level digits change with the encode.
        val tracks = tracksOf(C.TRACK_TYPE_VIDEO, video(1080, 6_000_000, codecs = "hvc1.2.4.L153.B0"))

        assertEquals("hvc1", ExoTrackMapper.qualityLevels(tracks).single().codec)
    }

    @Test
    fun theRangeComesFromTheTransferFunctionNotTheCodec() {
        // HDR10 and SDR are both HEVC. Reading the codec to decide the range
        // gets it wrong on exactly the streams where the answer matters.
        val sdr = tracksOf(C.TRACK_TYPE_VIDEO, video(2160, 20_000_000, "hvc1.2.4.L153.B0"))
        val hdr = tracksOf(
            C.TRACK_TYPE_VIDEO,
            video(2160, 20_000_000, "hvc1.2.4.L153.B0", transfer = C.COLOR_TRANSFER_ST2084),
        )

        assertEquals(DynamicRange.SDR, ExoTrackMapper.qualityLevels(sdr).single().dynamicRange)
        assertEquals(DynamicRange.HDR10, ExoTrackMapper.qualityLevels(hdr).single().dynamicRange)
    }

    @Test
    fun aFormatWithNoHeightIsNotARung() {
        // Media3 reports NO_VALUE before it has read the container, and a rung
        // of height -1 sorts to the bottom of every menu.
        val headless = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()

        assertTrue(ExoTrackMapper.qualityLevels(tracksOf(C.TRACK_TYPE_VIDEO, headless)).isEmpty())
    }

    @Test
    fun anAudioTrackWithoutALabelIsStillChoosable() {
        // A menu row with an empty title is a row a viewer cannot pick.
        val unlabelled = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setLanguage("nl")
            .setChannelCount(6)
            .build()

        val track: AudioTrack = ExoTrackMapper.audioTracks(tracksOf(C.TRACK_TYPE_AUDIO, unlabelled)).single()

        assertEquals("nl", track.language)
        assertEquals("nl", track.label)
        assertEquals(6, track.channels)
        assertEquals("mp4a", track.codec)
    }

    @Test
    fun aForcedSubtitleIsMarkedAsOne() {
        // Forced subtitles are the alien dialogue in an otherwise English film.
        // Unmarked, they appear as a second identical English row.
        val forced = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .setSelectionFlags(C.SELECTION_FLAG_FORCED)
            .build()
        val plain = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("en")
            .build()

        val tracks: List<SubtitleTrack> = ExoTrackMapper.subtitleTracks(
            tracksOf(C.TRACK_TYPE_TEXT, forced, plain),
        )

        assertEquals(listOf(true, false), tracks.map { it.forced })
        assertEquals(listOf("vtt", "srt"), tracks.map { it.format })
    }

    @Test
    fun anAdaptingEngineReportsNoPinnedQuality() {
        // Two rungs selected means the engine is adapting between them.
        // Reporting whichever happens to be playing would make a menu show a
        // selection the viewer never made.
        val group = TrackGroup(
            video(1080, 6_000_000, "hvc1.2.4.L153.B0"),
            video(720, 3_000_000, "hvc1.2.4.L120.B0"),
        )
        val adapting = Tracks(
            ImmutableList.of(
                Tracks.Group(group, true, IntArray(2) { C.FORMAT_HANDLED }, BooleanArray(2) { true }),
            ),
        )

        assertEquals(null, ExoTrackMapper.selectedQuality(adapting))
    }

    @Test
    fun aPinnedRungIsReportedAsItself() {
        val pinned = tracksOf(
            C.TRACK_TYPE_VIDEO,
            video(1080, 6_000_000, "hvc1.2.4.L153.B0"),
            video(720, 3_000_000, "hvc1.2.4.L120.B0"),
            selected = 1,
        )

        assertEquals(720, ExoTrackMapper.selectedQuality(pinned)?.height)
    }
}

// Named rather than newest, so CI does not pick a platform this library does
// not claim to support.
private const val SDK_UNDER_TEST = 34
