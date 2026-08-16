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
                // Positional, not format.id: HLS renditions routinely share one
                // non-null Format.id across every language variant, which made
                // every row in the picker compare equal to "the selected track"
                // — confirmed live, real TV, 2026-08-15 (every subtitle option
                // showed selected at once). Positional is unique by construction
                // within one Tracks snapshot; selectByTypeOnMain resolves back
                // through the same flattened order.
                id = "audio:$index",
                language = format.language ?: UNKNOWN_LANGUAGE,
                label = resolveLabel(format.label, format.language),
                channels = format.channelCount.takeIf { it != Format.NO_VALUE } ?: DEFAULT_CHANNELS,
                codec = codecFamily(format.codecs, format.sampleMimeType),
            )
        }

    public fun subtitleTracks(tracks: Tracks): List<SubtitleTrack> =
        formatsOf(tracks, C.TRACK_TYPE_TEXT).mapIndexed { index, format ->
            SubtitleTrack(
                // Positional — see AudioTrack's identical id above.
                id = "text:$index",
                language = format.language ?: UNKNOWN_LANGUAGE,
                label = resolveSubtitleLabel(format.label, format.language),
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

    // The selected audio and text tracks, by the same rule as video: one
    // selection means a pinned choice, none means the engine's default.
    public fun selectedAudioTrack(tracks: Tracks): AudioTrack? =
        selectedIndexOf(tracks, C.TRACK_TYPE_AUDIO)?.let { index -> audioTracks(tracks).getOrNull(index) }

    public fun selectedSubtitleTrack(tracks: Tracks): SubtitleTrack? =
        selectedIndexOf(tracks, C.TRACK_TYPE_TEXT)?.let { index -> subtitleTracks(tracks).getOrNull(index) }

    // The selected format's position within formatsOf(tracks, type) — not its
    // Format.id, which is not reliably unique (see AudioTrack.id's own doc).
    private fun selectedIndexOf(tracks: Tracks, type: Int): Int? {
        val selected: Format = tracks.groups
            .filter { it.type == type }
            .flatMap(::selectedFormatsIn)
            .singleOrNull() ?: return null
        val index = formatsOf(tracks, type).indexOfFirst { it === selected }
        return index.takeIf { it >= 0 }
    }

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
            codec = codecFamily(format.codecs, format.sampleMimeType),
            dynamicRange = dynamicRange(format.colorInfo?.colorTransfer),
            width = format.width.takeIf { it != Format.NO_VALUE },
            label = format.label,
        )
    }

    // The three decisions below take values rather than a Format on purpose.
    //
    // They are where this mapper is actually wrong or right, and a Format cannot
    // be built without an Android runtime — so testing them through one means
    // either a device or an emulated framework, for logic that is a string and
    // an integer. Taking the values directly is what makes them provable
    // anywhere, and the Format-shaped wrappers above are the thin part.

    // The transfer function, not the mime type. HDR10 and SDR are both HEVC and
    // differ only in how the samples are interpreted, so reading the codec to
    // decide the range gets it wrong on exactly the streams where it matters.
    internal fun dynamicRange(colorTransfer: Int?): DynamicRange = when (colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> DynamicRange.HDR10
        C.COLOR_TRANSFER_HLG -> DynamicRange.HDR10
        else -> DynamicRange.SDR
    }

    // "hvc1.2.4.L153.B0" is what a manifest carries and "hvc1" is what a
    // selection compares on. Keeping the profile would make the same rung
    // unmatchable across two manifests of the same film.
    internal fun codecFamily(codecs: String?, mimeType: String?): String {
        val declared: String? = codecs?.substringBefore('.')?.takeIf { it.isNotBlank() }
        return declared ?: mimeFamilyOf(mimeType)
    }

    internal fun mimeFamilyOf(mimeType: String?): String = when (mimeType) {
        MimeTypes.VIDEO_H264 -> "avc1"
        MimeTypes.VIDEO_H265 -> "hvc1"
        MimeTypes.VIDEO_AV1 -> "av01"
        MimeTypes.AUDIO_AAC -> "mp4a"
        MimeTypes.AUDIO_OPUS -> "opus"
        MimeTypes.AUDIO_AC3 -> "ac-3"
        MimeTypes.AUDIO_E_AC3 -> "ec-3"
        else -> mimeType?.substringAfter('/') ?: UNKNOWN_CODEC
    }

    // The language when there is no label, because a menu row with an empty
    // title is a row a viewer cannot choose. Blank counts as no label too —
    // HLS variants routinely carry NAME="" — a plain Elvis operator only
    // catches null.
    internal fun resolveLabel(label: String?, language: String?): String =
        label?.takeIf { it.isNotBlank() }
            ?: language?.takeIf { it.isNotBlank() }
            ?: UNKNOWN_LANGUAGE

    // resolveSubtitleLabel/subtitleKindOf live in LanguageNames.kt (commonMain)
    // — shared with AppPlaylistItem's sidecar-track path, which hits the exact
    // same bare-kind-word ambiguity from the server's wire response rather
    // than from a Media3 Format.

    internal fun subtitleFormatOf(mimeType: String?): String = when (mimeType) {
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
