// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
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
 * Per-codec: a device that opens HEVC Main10 and not AVC High10 says so
 * directly through [VideoCodecCapability.profiles] rather than through one
 * flag ANDed across every codec it lists at all.
 */
public actual fun platformDecodeProfile(): DeviceDecodeProfile {
    val survey = DecoderSurvey()
    val video = VideoProfileBuilder(survey)
    return DeviceDecodeProfile(
        video = VIDEO_MIME_TYPES.mapNotNull { (mime, codec) -> video.capabilityFor(mime, codec) },
        audio = AUDIO_MIME_TYPES.mapNotNull { (mime, codec) -> survey.audioCapabilityFor(mime, codec) },
        containers = survey.containers(),
        supportsHdr = survey.supportsHdr(),
        // No client-imposed cap, for the reason web gives: the server hard-
        // transcodes above this, and a guess forces transcoding of compatible
        // files over a LAN.
        maxBitrateKbps = DeviceDecodeProfile.NO_CAP,
    )
}

// One pass over MediaCodecList, the display and the software engine, asked
// once and then queried per codec. Built as an object rather than a run of
// local functions closing over the same values, because that run was one
// method carrying every branch in this file.
private class DecoderSurvey {

    private val decoders: List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .codecInfos
        .filterNot(MediaCodecInfo::isEncoder)

    // ffmpeg opens what the file holds rather than what the chip offers, so the
    // software engine contributes the whole video set — but only once a payload
    // for THIS device has installed. Declaring a codec because a library could
    // be present is how a client asks for a stream it then cannot play, which is
    // worse than never having asked.
    val software: Boolean = MpvVideoEngineProvider.isAvailable()

    private val displayHdrTypes: Set<Int> = runCatching {
        PlatformEnvironment.requireContext().androidContext
            .getSystemService(android.content.Context.DISPLAY_SERVICE)
            .let { it as android.hardware.display.DisplayManager }
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?.hdrCapabilities
            ?.supportedHdrTypes
            ?.toSet()
    }.getOrNull() ?: emptySet()

    private val metrics: DisplayMetrics = PlatformEnvironment.requireContext()
        .androidContext
        .resources
        .displayMetrics
    val maxWidth: Int = DecodeResolution.clamp(maxOf(metrics.widthPixels, metrics.heightPixels))
    val maxHeight: Int = DecodeResolution.clamp(minOf(metrics.widthPixels, metrics.heightPixels))

    fun decodersFor(mime: String): List<MediaCodecInfo> = decoders
        .filter { info -> info.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }

    fun supports(mime: String, profile: Int? = null): Boolean = decodersFor(mime)
        .any { info ->
            profile == null || info.getCapabilitiesForType(mime).profileLevels.any { it.profile == profile }
        }

    fun displayHdrFormats(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (Display.HdrCapabilities.HDR_TYPE_HDR10 in displayHdrTypes) add(HdrFormat.HDR10)
            if (Display.HdrCapabilities.HDR_TYPE_HLG in displayHdrTypes) add(HdrFormat.HLG)
            if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in displayHdrTypes) add(HdrFormat.DOLBY_VISION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in displayHdrTypes
        ) {
            add(HdrFormat.HDR10_PLUS)
        }
    }

    fun profileNamesFor(mime: String, codec: String): List<String> {
        if (software) return SOFTWARE_PROFILES[codec] ?: emptyList()
        val table = PROFILE_NAMES[mime] ?: return emptyList()
        return decodersFor(mime)
            .flatMap { info -> info.getCapabilitiesForType(mime).profileLevels.toList() }
            .mapNotNull { level -> table[level.profile] }
            .distinct()
    }

    // Media3 plays HLS, DASH and progressive MP4 out of the box, and libmpv
    // plays all of them, plus MKV/TS which libmpv demuxes natively; neither is
    // conditional on the device.
    fun containers(): List<String> = if (software) {
        listOf(
            DecodeContainer.HLS,
            DecodeContainer.MP4,
            DecodeContainer.DASH,
            DecodeContainer.MKV,
            DecodeContainer.TS,
        )
    } else {
        listOf(DecodeContainer.HLS, DecodeContainer.MP4, DecodeContainer.DASH, DecodeContainer.TS)
    }

    fun supportsHdr(): Boolean = displayHdrFormats().isNotEmpty() ||
        supports(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10)

    // Passthrough support is a bitstream question, not a decode one: it asks
    // whether the current output route (HDMI/ARC/optical) accepts the encoded
    // format unmodified, and `AudioManager` exposes exactly that per-encoding.
    private fun passthroughSupported(encoding: Int): Boolean = runCatching {
        AudioManager.getDirectPlaybackSupport(
            AudioFormat.Builder().setEncoding(encoding).build(),
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        ) != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
    }.getOrDefault(false)

    fun audioCapabilityFor(mime: String, codec: String): AudioCodecCapability? {
        val decode: Boolean = supports(mime)
        val passthrough: Boolean = AUDIO_PASSTHROUGH_ENCODING[codec]
            ?.let { encoding ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && passthroughSupported(encoding)
            }
            ?: false
        if (!decode && !passthrough) return null
        return AudioCodecCapability(
            codec = codec,
            maxChannels = if (codec == DecodeCodec.AAC) DeviceDecodeProfile.STEREO else MAX_SURROUND_CHANNELS,
            passthrough = passthrough,
            decode = decode,
        )
    }

}

// Turns what [DecoderSurvey] found into the video half of the profile. Split
// from the survey because surveying the device and describing a codec are two
// jobs, and holding both put every branch in this file behind one object.
private class VideoProfileBuilder(private val survey: DecoderSurvey) {

    fun capabilityFor(mime: String, codec: String): VideoCodecCapability? {
        if (!survey.software && !survey.supports(mime)) return null

        val caps: MediaCodecInfo.VideoCapabilities? = capsFor(mime)

        return VideoCodecCapability(
            codec = codec,
            profiles = survey.profileNamesFor(mime, codec),
            maxBitDepth = bitDepthFor(mime),
            maxWidth = dimension(caps?.supportedWidths?.upper, survey.maxWidth),
            maxHeight = dimension(caps?.supportedHeights?.upper, survey.maxHeight),
            maxFramerate = framerate(caps),
            hdrFormats = hdrFormatsFor(mime, codec),
            maxBitrateKbps = bitrateKbps(caps),
        )
    }

    // The most permissive hardware decoder for the type — the same one
    // MediaCodec itself would pick when asked to decode it. VideoCapabilities
    // is per-codec-instance rather than per-mime, which is why this is a search
    // rather than a lookup.
    private fun capsFor(mime: String): MediaCodecInfo.VideoCapabilities? = survey.decodersFor(mime)
        .firstNotNullOfOrNull { info ->
            runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
        }

    private fun bitDepthFor(mime: String): Int = when {
        survey.software -> MAX_SOFTWARE_BIT_DEPTH
        TEN_BIT_PROFILE_OF[mime]?.let { profile -> survey.supports(mime, profile) } == true -> TEN_BIT_DEPTH
        else -> EIGHT_BIT_DEPTH
    }

    private fun hdrFormatsFor(mime: String, codec: String): List<String> = when {
        survey.software -> listOf(HdrFormat.HDR10, HdrFormat.HDR10_PLUS, HdrFormat.DOLBY_VISION, HdrFormat.HLG)
        codec == DecodeCodec.H265 || codec == DecodeCodec.AV1 || codec == DecodeCodec.VP9 -> hdrFromDisplay(mime)
        else -> emptyList()
    }

    private fun hdrFromDisplay(mime: String): List<String> {
        val hasEditingFeature = survey.decodersFor(mime).any { info ->
            info.getCapabilitiesForType(mime).isFeatureSupported(FEATURE_HdrEditing)
        }
        val fromDisplay = survey.displayHdrFormats()
        return if (hasEditingFeature || fromDisplay.isNotEmpty()) fromDisplay else emptyList()
    }

    // The software engine decodes what the file holds, so it answers with the
    // ceiling rather than with a chip's limits; the hardware path clamps what
    // the decoder declares and falls back to the panel when it declares nothing.
    private fun dimension(declared: Int?, screenFallback: Int): Int = if (survey.software) {
        DecodeResolution.UHD
    } else {
        declared?.let(DecodeResolution::clamp) ?: screenFallback
    }

    private fun framerate(caps: MediaCodecInfo.VideoCapabilities?): Int = if (survey.software) {
        MAX_FRAMERATE
    } else {
        caps?.supportedFrameRates?.upper?.toInt() ?: MAX_FRAMERATE
    }

    private fun bitrateKbps(caps: MediaCodecInfo.VideoCapabilities?): Int = if (survey.software) {
        DeviceDecodeProfile.NO_CAP
    } else {
        caps?.bitrateRange?.upper?.let { bps -> bps / BITS_PER_KILOBIT } ?: DeviceDecodeProfile.NO_CAP
    }
}

private const val MAX_SOFTWARE_BIT_DEPTH: Int = 12
private const val MAX_SURROUND_CHANNELS: Int = 6
private const val TEN_BIT_DEPTH: Int = 10
private const val EIGHT_BIT_DEPTH: Int = 8

// What the software engine reports, and the fallback when a hardware decoder
// declines to name its own ceiling.
private const val MAX_FRAMERATE: Int = 60

// The Android API reports bitrate in bits per second; DeviceDecodeProfile
// carries kilobits.
private const val BITS_PER_KILOBIT: Int = 1000

private val VIDEO_MIME_TYPES: List<Pair<String, String>> = listOf(
    // H264 first, as web does it: the server transcodes to the client's first
    // listed codec, and that is the one every downstream target also opens.
    MediaFormat.MIMETYPE_VIDEO_AVC to DecodeCodec.H264,
    MediaFormat.MIMETYPE_VIDEO_HEVC to DecodeCodec.H265,
    MediaFormat.MIMETYPE_VIDEO_AV1 to DecodeCodec.AV1,
    MediaFormat.MIMETYPE_VIDEO_VP9 to DecodeCodec.VP9,
)

// Which wire codec each 10-bit profile belongs to, so bit depth can be
// answered per codec even though `CodecProfileLevel` is not organised that way.
private val TEN_BIT_PROFILE_OF: Map<String, Int> = mapOf(
    MediaFormat.MIMETYPE_VIDEO_HEVC to CodecProfileLevel.HEVCProfileMain10,
    MediaFormat.MIMETYPE_VIDEO_AVC to CodecProfileLevel.AVCProfileHigh10,
    MediaFormat.MIMETYPE_VIDEO_AV1 to CodecProfileLevel.AV1ProfileMain10,
)

// CodecProfileLevel profile constants named the way the wire contract spells
// them, per codec — the constant namespace is one flat set of ints shared
// across every codec, so this table is keyed by MIME first.
private val PROFILE_NAMES: Map<String, Map<Int, String>> = mapOf(
    MediaFormat.MIMETYPE_VIDEO_AVC to mapOf(
        CodecProfileLevel.AVCProfileBaseline to VideoProfileName.BASELINE,
        CodecProfileLevel.AVCProfileMain to VideoProfileName.MAIN,
        CodecProfileLevel.AVCProfileHigh to VideoProfileName.HIGH,
        CodecProfileLevel.AVCProfileHigh10 to VideoProfileName.HIGH10,
    ),
    MediaFormat.MIMETYPE_VIDEO_HEVC to mapOf(
        CodecProfileLevel.HEVCProfileMain to VideoProfileName.MAIN,
        CodecProfileLevel.HEVCProfileMain10 to VideoProfileName.MAIN10,
    ),
    MediaFormat.MIMETYPE_VIDEO_AV1 to mapOf(
        CodecProfileLevel.AV1ProfileMain8 to VideoProfileName.MAIN,
        CodecProfileLevel.AV1ProfileMain10 to VideoProfileName.MAIN10,
    ),
)

// libmpv reports no profileLevels through MediaCodecList (it isn't in it), so
// this is what it opens rather than what a hardware decoder enumerates.
private val SOFTWARE_PROFILES: Map<String, List<String>> = mapOf(
    DecodeCodec.H264 to listOf(
        VideoProfileName.BASELINE,
        VideoProfileName.MAIN,
        VideoProfileName.HIGH,
        VideoProfileName.HIGH10,
    ),
    DecodeCodec.H265 to listOf(VideoProfileName.MAIN, VideoProfileName.MAIN10),
    DecodeCodec.AV1 to listOf(VideoProfileName.MAIN, VideoProfileName.MAIN10),
    DecodeCodec.VP9 to listOf("profile0", "profile2"),
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

// `AudioFormat.ENCODING_*` per wire codec, for the passthrough probe. DTS and
// TrueHD have no `MediaCodec` decoder on Android at all — they only ever reach
// a device as bitstream to an external receiver, never as `decode`.
private val AUDIO_PASSTHROUGH_ENCODING: Map<String, Int> = buildMap {
    put(DecodeCodec.AC3, AudioFormat.ENCODING_AC3)
    put(DecodeCodec.EAC3, AudioFormat.ENCODING_E_AC3)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        put(DecodeCodec.DTS, AudioFormat.ENCODING_DTS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        put(DecodeCodec.TRUEHD, AudioFormat.ENCODING_DOLBY_TRUEHD)
    }
}
