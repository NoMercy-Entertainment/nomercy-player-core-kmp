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
public actual fun platformClientCapabilities(): ClientCapabilities {
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

    // The measured gap this whole change exists for. No Android device's AVC
    // decoder offers profile 110 — the Samsung answers
    // `profile/levels: [ 8/32768 (High/5.1) ]`, which is High and stops there —
    // so without libmpv the honest answer is no, and the server transcodes
    // rather than the phone drawing black.
    val tenBit: Boolean = software || TEN_BIT_PROFILES.any { (mime, profile) -> supports(mime, profile) }

    val metrics: DisplayMetrics = PlatformEnvironment.requireContext()
        .androidContext
        .resources
        .displayMetrics

    return ClientCapabilities(
        videoCodecs = video,
        audioCodecs = audio,
        // Media3 plays HLS, DASH and progressive MP4 out of the box, and libmpv
        // plays all of them; neither is conditional on the device.
        containers = listOf(ClientContainer.HLS, ClientContainer.MP4, ClientContainer.DASH),
        maxWidth = ClientResolution.clamp(maxOf(metrics.widthPixels, metrics.heightPixels)),
        maxHeight = ClientResolution.clamp(minOf(metrics.widthPixels, metrics.heightPixels)),
        supportsHdr = supports(MediaFormat.MIMETYPE_VIDEO_HEVC, HEVCProfileMain10),
        supports10Bit = tenBit,
        // Stereo unless something proves otherwise. The count that matters is
        // the OUTPUT route's — HDMI, Bluetooth, the speaker — and it changes
        // while the app runs, so a number taken once at start-up is wrong more
        // often than right. The bedroom television is exactly this case.
        maxAudioChannels = STEREO,
        // No client-imposed cap, for the reason web gives: the server hard-
        // transcodes above this, and a guess forces transcoding of compatible
        // files over a LAN.
        maxBitrateKbps = NO_CAP,
    )
}

private val VIDEO_MIME_TYPES: List<Pair<String, String>> = listOf(
    // H264 first, as web does it: the server transcodes to the client's first
    // listed codec, and that is the one every downstream target also opens.
    MediaFormat.MIMETYPE_VIDEO_AVC to ClientCodec.H264,
    MediaFormat.MIMETYPE_VIDEO_HEVC to ClientCodec.H265,
    MediaFormat.MIMETYPE_VIDEO_AV1 to ClientCodec.AV1,
)

private val TEN_BIT_PROFILES: List<Pair<String, Int>> = listOf(
    MediaFormat.MIMETYPE_VIDEO_HEVC to HEVCProfileMain10,
    MediaFormat.MIMETYPE_VIDEO_AVC to AVCProfileHigh10,
    MediaFormat.MIMETYPE_VIDEO_AV1 to AV1ProfileMain10,
)

// The MIME type each wire name is asked for, as a table rather than a run of
// ifs — the run was one line repeated with two words changed, which is where a
// copied line keeps the previous line's constant.
private val AUDIO_MIME_TYPES: List<Pair<String, String>> = listOf(
    MediaFormat.MIMETYPE_AUDIO_AAC to ClientCodec.AAC,
    MediaFormat.MIMETYPE_AUDIO_EAC3 to ClientCodec.EAC3,
    MediaFormat.MIMETYPE_AUDIO_AC3 to ClientCodec.AC3,
    MediaFormat.MIMETYPE_AUDIO_FLAC to ClientCodec.FLAC,
    MediaFormat.MIMETYPE_AUDIO_OPUS to ClientCodec.OPUS,
    MediaFormat.MIMETYPE_AUDIO_MPEG to ClientCodec.MP3,
)
