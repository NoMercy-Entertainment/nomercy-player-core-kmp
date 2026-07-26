// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks

// Media3's view of a stream, turned into the library's.
//
// Pure on purpose: it takes a Tracks snapshot and returns lists, touching no
// player and no thread. Everything that can be got wrong here — a dynamic range
// read from the wrong field, a language tag that arrives null, a codec string
// that is a full RFC 6381 descriptor rather than a family — is got wrong
// silently, and a function that can be handed a fabricated Format is the only
// way to see it before a device does.
public object ExoTrackMapper {

    public fun qualityLevels(tracks: Tracks): List<QualityLevel> =
        formatsOf(tracks, C.TRACK_TYPE_VIDEO).mapNotNull(::qualityOf)

    public fun audioTracks(tracks: Tracks): List<AudioTrack> =
        formatsOf(tracks, C.TRACK_TYPE_AUDIO).mapIndexed { index, format ->
            AudioTrack(
                id = format.id ?: "audio:$index",
                language = format.language ?: UNKNOWN_LANGUAGE,
                // The language when there is no label, because a menu row with
                // an empty title is a row a viewer cannot choose.
                label = format.label ?: format.language ?: UNKNOWN_LANGUAGE,
                channels = format.channelCount.takeIf { it != Format.NO_VALUE } ?: DEFAULT_CHANNELS,
                codec = codecFamilyOf(format),
            )
        }

    public fun subtitleTracks(tracks: Tracks): List<SubtitleTrack> =
        formatsOf(tracks, C.TRACK_TYPE_TEXT).mapIndexed { index, format ->
            SubtitleTrack(
                id = format.id ?: "text:$index",
                language = format.language ?: UNKNOWN_LANGUAGE,
                label = format.label ?: format.language ?: UNKNOWN_LANGUAGE,
                format = subtitleFormatOf(format.sampleMimeType),
                // Forced is a flag on the track rather than a separate one, and
                // a chrome that ignored it shows two identical-looking English
                // entries the viewer has to guess between.
                forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
            )
        }

    // The selected video format, or null when the engine is adapting. Null is
    // the honest answer for automatic: reporting whichever rung happens to be
    // playing would make a menu show a pinned selection the viewer never made.
    public fun selectedQuality(tracks: Tracks): QualityLevel? =
        tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap(::selectedFormatsIn)
            .singleOrNull()
            ?.let(::qualityOf)

    private fun selectedFormatsIn(group: Tracks.Group): List<Format> =
        (0 until group.length)
            .filter(group::isTrackSelected)
            .map(group::getTrackFormat)

    private fun formatsOf(tracks: Tracks, type: Int): List<Format> =
        tracks.groups
            .filter { it.type == type }
            .flatMap { group -> (0 until group.length).map(group::getTrackFormat) }

    private fun qualityOf(format: Format): QualityLevel? {
        if (format.height == Format.NO_VALUE) return null

        return QualityLevel(
            height = format.height,
            // Peak first, average second. A ladder sorted by average puts a
            // variable-bitrate rung below a constant one it actually exceeds.
            bitrate = format.peakBitrate.takeIf { it != Format.NO_VALUE }
                ?: format.averageBitrate.takeIf { it != Format.NO_VALUE }
                ?: 0,
            codec = codecFamilyOf(format),
            dynamicRange = dynamicRangeOf(format),
            width = format.width.takeIf { it != Format.NO_VALUE },
            label = format.label,
        )
    }

    // The transfer function, not the mime type. HDR10 and SDR are both HEVC and
    // differ only in how the samples are interpreted, so reading the codec to
    // decide the range gets it wrong on exactly the streams where it matters.
    private fun dynamicRangeOf(format: Format): DynamicRange = when (format.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> DynamicRange.HDR10
        C.COLOR_TRANSFER_HLG -> DynamicRange.HDR10
        else -> DynamicRange.SDR
    }

    // "hvc1.2.4.L153.B0" is what a manifest carries and "hvc1" is what a
    // selection compares on. Keeping the profile would make the same rung
    // unmatchable across two manifests of the same film.
    private fun codecFamilyOf(format: Format): String {
        val declared: String? = format.codecs?.substringBefore('.')?.takeIf { it.isNotBlank() }
        return declared ?: mimeFamilyOf(format.sampleMimeType)
    }

    private fun mimeFamilyOf(mimeType: String?): String = when (mimeType) {
        MimeTypes.VIDEO_H264 -> "avc1"
        MimeTypes.VIDEO_H265 -> "hvc1"
        MimeTypes.VIDEO_AV1 -> "av01"
        MimeTypes.AUDIO_AAC -> "mp4a"
        MimeTypes.AUDIO_OPUS -> "opus"
        MimeTypes.AUDIO_AC3 -> "ac-3"
        MimeTypes.AUDIO_E_AC3 -> "ec-3"
        else -> mimeType?.substringAfter('/') ?: UNKNOWN_CODEC
    }

    private fun subtitleFormatOf(mimeType: String?): String = when (mimeType) {
        MimeTypes.TEXT_VTT -> "vtt"
        MimeTypes.APPLICATION_SUBRIP -> "srt"
        MimeTypes.TEXT_SSA -> "ass"
        MimeTypes.APPLICATION_TTML -> "ttml"
        MimeTypes.APPLICATION_PGS -> "pgs"
        else -> mimeType?.substringAfter('/') ?: "vtt"
    }

    private const val DEFAULT_CHANNELS = 2
    private const val UNKNOWN_LANGUAGE = "und"
    private const val UNKNOWN_CODEC = "unknown"
}
