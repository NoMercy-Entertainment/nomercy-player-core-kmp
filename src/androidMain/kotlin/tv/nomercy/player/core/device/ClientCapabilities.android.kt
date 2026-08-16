// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
import android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
import android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import tv.nomercy.player.core.ports.PlatformEnvironment
import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider

/**
 * The device's own decoder list, plus what libmpv adds when its payload
 * actually installed.
 *
 * Both halves, and only together. The hardware list is the truth about what
 * plays without draining the battery; libmpv is the truth about what plays at
 * all. Declaring only the first has the server transcode files we decode
 * perfectly well in software; declaring only the second has it send 10-bit AVC
 * to a device that would show a black rectangle.
 *
 * The web probe is the shape this follows, because the server reads one
 * contract and two clients disagreeing about it is two answers to one question.
 */
public actual fun platformDecodeProfile(): DeviceDecodeProfile {
    val decoders: List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .codecInfos
        .filterNot(MediaCodecInfo::isEncoder)

    fun supports(mime: String, profile: Int? = null): Boolean = decoders
        .filter { info -> info.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
        .any { info ->
            profile == null || info.getCapabilitiesForType(mime).profileLevels.any { it.profile == profile }
        }

    // ffmpeg opens what the file holds rather than what the chip offers, so the
    // software engine contributes the whole video set — but only once a payload
    // for THIS device has installed. Declaring a codec because a library could
    // be present is how a client asks for a stream it then cannot play, which is
    // worse than never having asked.
    val software: Boolean = MpvVideoEngineProvider.isAvailable()

    val video: List<String> = VIDEO_MIME_TYPES
        .filter { (mime, _) -> software || supports(mime) }
        .map { (_, codec) -> codec }

    val audio: List<String> = AUDIO_MIME_TYPES
        .filter { (mime, _) -> supports(mime) }
        .map { (_, codec) -> codec }

    // EVERY declared codec, not any of them, and the difference is a black
    // screen.
    //
    // supports_10bit is ONE flag for ALL codecs on the server side —
    // `bitDepthOk = video.BitDepth <= 8 || client.Supports10Bit`, with no codec
    // in the condition. This phone decodes HEVC Main 10 and does NOT decode AVC
    // High 10 (`profile/levels: [ 8/32768 (High/5.1) ]`, measured). Answering
    // "any" would set the flag on the strength of HEVC, and a Hi10P AVC file
    // would then win DirectPlay and draw nothing.
    //
    // So the flag is only as true as the weakest codec we listed. The cost is a
    // 10-bit HEVC file transcoded that this device could have played; the cost
    // the other way is a viewer looking at black with every log line green.
    val tenBit: Boolean = software || video.isNotEmpty() && video.all { codec ->
        TEN_BIT_PROFILES.filter { (_, listed) -> listed == codec }
            .any { (mime, _) -> supports(mime, TEN_BIT_PROFILE_OF.getValue(mime)) }
    }

    val metrics: DisplayMetrics = PlatformEnvironment.requireContext()
        .androidContext
        .resources
        .displayMetrics

    return DeviceDecodeProfile(
        videoCodecs = video,
        audioCodecs = audio,
        // Media3 plays HLS, DASH and progressive MP4 out of the box, and libmpv
        // plays all of them; neither is conditional on the device.
        containers = listOf(DecodeContainer.HLS, DecodeContainer.MP4, DecodeContainer.DASH),
        maxWidth = DecodeResolution.clamp(maxOf(metrics.widthPixels, metrics.heightPixels)),
        maxHeight = DecodeResolution.clamp(minOf(metrics.widthPixels, metrics.heightPixels)),
        supportsHdr = supports(MediaFormat.MIMETYPE_VIDEO_HEVC, HEVCProfileMain10),
        supports10Bit = tenBit,
        // Stereo unless something proves otherwise. The count that matters is
        // the OUTPUT route's — HDMI, Bluetooth, the speaker — and it changes
        // while the app runs, so a number taken once at start-up is wrong more
        // often than right. The bedroom television is exactly this case.
        maxAudioChannels = DeviceDecodeProfile.STEREO,
        // No client-imposed cap, for the reason web gives: the server hard-
        // transcodes above this, and a guess forces transcoding of compatible
        // files over a LAN.
        maxBitrateKbps = DeviceDecodeProfile.NO_CAP,
    )
}

private val VIDEO_MIME_TYPES: List<Pair<String, String>> = listOf(
    // H264 first, as web does it: the server transcodes to the client's first
    // listed codec, and that is the one every downstream target also opens.
    MediaFormat.MIMETYPE_VIDEO_AVC to DecodeCodec.H264,
    MediaFormat.MIMETYPE_VIDEO_HEVC to DecodeCodec.H265,
    MediaFormat.MIMETYPE_VIDEO_AV1 to DecodeCodec.AV1,
)

// Which wire codec each 10-bit profile belongs to, so the flag can be answered
// per codec even though the server's field is not.
private val TEN_BIT_PROFILES: List<Pair<String, String>> = listOf(
    MediaFormat.MIMETYPE_VIDEO_HEVC to DecodeCodec.H265,
    MediaFormat.MIMETYPE_VIDEO_AVC to DecodeCodec.H264,
    MediaFormat.MIMETYPE_VIDEO_AV1 to DecodeCodec.AV1,
)

private val TEN_BIT_PROFILE_OF: Map<String, Int> = mapOf(
    MediaFormat.MIMETYPE_VIDEO_HEVC to HEVCProfileMain10,
    MediaFormat.MIMETYPE_VIDEO_AVC to AVCProfileHigh10,
    MediaFormat.MIMETYPE_VIDEO_AV1 to AV1ProfileMain10,
)

// The MIME type each wire name is asked for, as a table rather than a run of
// ifs — the run was one line repeated with two words changed, which is where a
// copied line keeps the previous line's constant.
private val AUDIO_MIME_TYPES: List<Pair<String, String>> = listOf(
    MediaFormat.MIMETYPE_AUDIO_AAC to DecodeCodec.AAC,
    MediaFormat.MIMETYPE_AUDIO_EAC3 to DecodeCodec.EAC3,
    MediaFormat.MIMETYPE_AUDIO_AC3 to DecodeCodec.AC3,
    MediaFormat.MIMETYPE_AUDIO_FLAC to DecodeCodec.FLAC,
    MediaFormat.MIMETYPE_AUDIO_OPUS to DecodeCodec.OPUS,
    MediaFormat.MIMETYPE_AUDIO_MPEG to DecodeCodec.MP3,
)
