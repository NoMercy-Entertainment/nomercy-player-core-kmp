// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
import android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
import android.media.MediaCodecList
import android.media.MediaFormat
import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider

/**
 * The device's own decoder list, plus what libmpv adds to it.
 *
 * Both halves matter and only together. The hardware list is the truth about
 * what plays without costing battery; libmpv is the truth about what plays at
 * all. Declaring only the first would have the server transcode files we can
 * decode perfectly well in software; declaring only the second would have it
 * send 10-bit AVC to a device that would show a black rectangle.
 */
public actual fun platformPlaybackCapabilities(): PlaybackCapabilities {
    val codecs: List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .codecInfos
        .filterNot(MediaCodecInfo::isEncoder)

    fun supports(mime: String, profile: Int? = null): Boolean = codecs
        .filter { info -> info.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
        .any { info ->
            profile == null || info.getCapabilitiesForType(mime).profileLevels.any { it.profile == profile }
        }

    val hardware: List<String> = HARDWARE_VIDEO
        .filter { (mime, _) -> supports(mime) }
        .map { (_, codec) -> codec }

    // ffmpeg opens what the file contains rather than what the chip offers, so
    // the software engine's contribution is the whole set — but only when a
    // payload for this device actually installed. Declaring a codec because a
    // library COULD be present is how a device asks for a stream it then cannot
    // play, which is worse than never having asked.
    val software: List<String> = if (MpvVideoEngineProvider.isAvailable()) {
        listOf(PlaybackCodec.H264, PlaybackCodec.HEVC, PlaybackCodec.AV1, PlaybackCodec.VP9)
    } else {
        emptyList()
    }

    // Ten bit, said in the only place the contract has room for it.
    //
    // The server's codec vocabulary carries no bit depth, so "this device
    // decodes 10-bit AVC" cannot be a codec string. Whether it is true changes
    // whether a Hi10P file needs transcoding at all, so it is worth saying even
    // in free text — and the alternative, inventing a fifth codec name, is a
    // value the server drops without complaining.
    val tenBitAvc: Boolean = MpvVideoEngineProvider.isAvailable() ||
        supports(MediaFormat.MIMETYPE_VIDEO_AVC, AVCProfileHigh10)

    val audio: List<String> = HARDWARE_AUDIO
        .filter { (mime, _) -> supports(mime) }
        .map { (_, codec) -> codec }

    return PlaybackCapabilities(
        videoCodecs = (hardware + software).distinct(),
        audioCodecs = audio,
        maxVideoHeight = maxVideoHeight(codecs),
        maxAudioChannels = null,
        hdrSupport = supports(MediaFormat.MIMETYPE_VIDEO_HEVC, HEVCProfileMain10),
        dolbyVision = if (supports(MIMETYPE_DOLBY_VISION)) DolbyVisionProfile.PROFILE_8_1 else DolbyVisionProfile.NONE,
        notes = buildString {
            append(if (software.isEmpty()) "hardware decoders only" else "libmpv present, software decoding available")
            append("; 10-bit AVC ")
            append(if (tenBitAvc) "decodes" else "does NOT decode")
        },
    )
}

// The MIME type each wire codec name is asked for, as a table rather than as a
// run of ifs. The run was the same line eleven times with two words changed,
// which is where a copied line keeps the previous line's constant.
private val HARDWARE_VIDEO: List<Pair<String, String>> = listOf(
    MediaFormat.MIMETYPE_VIDEO_AVC to PlaybackCodec.H264,
    MediaFormat.MIMETYPE_VIDEO_HEVC to PlaybackCodec.HEVC,
    MediaFormat.MIMETYPE_VIDEO_AV1 to PlaybackCodec.AV1,
    MediaFormat.MIMETYPE_VIDEO_VP9 to PlaybackCodec.VP9,
)

private val HARDWARE_AUDIO: List<Pair<String, String>> = listOf(
    MediaFormat.MIMETYPE_AUDIO_AAC to PlaybackCodec.AAC,
    MediaFormat.MIMETYPE_AUDIO_AC3 to PlaybackCodec.AC3,
    MediaFormat.MIMETYPE_AUDIO_EAC3 to PlaybackCodec.EAC3,
    MediaFormat.MIMETYPE_AUDIO_FLAC to PlaybackCodec.FLAC,
    MediaFormat.MIMETYPE_AUDIO_OPUS to PlaybackCodec.OPUS,
)

// The tallest picture any video decoder on the device claims, which is the
// number a ladder should be capped against. Asked of the decoders rather than
// of the display: a 1080p panel still benefits from a 4K decode downscaled, and
// a decoder that cannot open the rung is the one that stops playback.
private fun maxVideoHeight(codecs: List<MediaCodecInfo>): Int? = codecs
    .flatMap { info -> info.supportedTypes.map(info::getCapabilitiesForType) }
    .mapNotNull { capabilities -> capabilities.videoCapabilities?.supportedHeights?.upper }
    .maxOrNull()

// Not in MediaFormat until a later API level than this library's minimum.
private const val MIMETYPE_DOLBY_VISION: String = "video/dolby-vision"

// Null rather than a guess, and null is the server's word for unknown. The
// channel count that matters is the OUTPUT route's — HDMI, Bluetooth, the
// built-in speaker — and it changes while the app is running, so a number taken
// once at start-up would be wrong more often than right. The bedroom television
// is exactly this case.
