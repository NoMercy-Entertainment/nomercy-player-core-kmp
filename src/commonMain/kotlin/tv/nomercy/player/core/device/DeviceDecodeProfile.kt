// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

/**
 * What this device can actually decode.
 *
 * A device fact, which is why it lives here: answering it means asking
 * `MediaCodecList` on Android and `AVFoundation` on Apple, and every consumer
 * of this trio needs a real probe regardless of which server it talks to.
 *
 * Deliberately NOT a wire shape. This carried the media server's
 * `client_caps` field names and its PascalCase codec spellings, which put one
 * backend's contract inside a library any consumer is meant to use — the web
 * keeps the equivalent (`browserCaps.ts`) in its APP for that reason. A
 * consumer maps this into whatever its own server asks for.
 *
 * Per-codec rather than a flat allow-list plus one global boolean: a device
 * that decodes HEVC Main10 but not AVC High10 has no way to say so under a
 * single `supports10Bit` flag, and the same failure mode applies to HDR
 * format, profile/level, resolution+fps ceiling, max bitrate, and audio
 * passthrough vs decode-only.
 */
public data class DeviceDecodeProfile(
    /**
     * Best first, and the order is information rather than a set: a consumer
     * that asks a server to transcode wants the most reliably decodable target
     * named first, which on every platform so far is H.264.
     */
    val video: List<VideoCodecCapability> = emptyList(),
    val audio: List<AudioCodecCapability> = emptyList(),
    val containers: List<String> = emptyList(),

    /** DISPLAY trait, not decoder — kept as-is. */
    val supportsHdr: Boolean = false,

    /** Zero means no client-imposed cap, and zero is nearly always right. */
    val maxBitrateKbps: Int = NO_CAP,
) {
    public companion object {
        public const val STEREO: Int = 2
        public const val NO_CAP: Int = 0
    }
}

/**
 * One codec's decode ceiling, exhaustive rather than additive-flag: a profile
 * absent from [profiles] means this device does not open it, not "unlisted so
 * far" — an AVC entry with no `high10`/`main10` profile is 8-bit-only H.264 on
 * this device, exactly the bug case a flat allow-list could not express.
 */
public data class VideoCodecCapability(
    val codec: String,                       // DecodeCodec.H264 / H265 / AV1 / VP9
    val profiles: List<String> = emptyList(), // e.g. "high10", "main10", "main"
    val maxBitDepth: Int = 8,                 // 8 / 10 / 12
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFramerate: Int = 60,
    val hdrFormats: List<String> = emptyList(), // HdrFormat.HDR10 / HDR10_PLUS / DOLBY_VISION / HLG
    val maxBitrateKbps: Int = DeviceDecodeProfile.NO_CAP,
)

/** One codec's audio decode/passthrough answer — a device can have both, either, or neither. */
public data class AudioCodecCapability(
    val codec: String,                 // DecodeCodec.AAC / AC3 / EAC3 / DTS / TRUEHD / FLAC / OPUS / MP3
    val maxChannels: Int = DeviceDecodeProfile.STEREO,
    val passthrough: Boolean = false,  // bitstream to an external receiver, no decode
    val decode: Boolean = true,        // device can decode+downmix itself
)

/** The HDR format names this library reports, lowercase and vendor-neutral. */
public object HdrFormat {
    public const val HDR10: String = "hdr10"
    public const val HDR10_PLUS: String = "hdr10plus"
    public const val DOLBY_VISION: String = "dolbyvision"
    public const val HLG: String = "hlg"
}

/** The codec names this library reports, lowercase and vendor-neutral. */
public object DecodeCodec {
    public const val H264: String = "h264"
    public const val H265: String = "h265"
    public const val AV1: String = "av1"
    public const val VP9: String = "vp9"

    public const val AAC: String = "aac"
    public const val EAC3: String = "eac3"
    public const val AC3: String = "ac3"
    public const val DTS: String = "dts"
    public const val TRUEHD: String = "truehd"
    public const val FLAC: String = "flac"
    public const val OPUS: String = "opus"
    public const val MP3: String = "mp3"
}

public object DecodeContainer {
    public const val HLS: String = "hls"
    public const val MP4: String = "mp4"
    public const val WEBM: String = "webm"
    public const val DASH: String = "dash"
    public const val TS: String = "ts"
    public const val MKV: String = "mkv"
}

/**
 * The three widths worth reporting, because a probe that reports its exact
 * panel size invites a decision built for that one panel.
 */
public object DecodeResolution {
    public const val UHD: Int = 3840
    public const val FHD: Int = 1920
    public const val HD: Int = 1280

    public fun clamp(raw: Int): Int = when {
        raw >= UHD -> UHD
        raw >= FHD -> FHD
        else -> HD
    }
}

/** What this platform reports. */
public expect fun platformDecodeProfile(): DeviceDecodeProfile
