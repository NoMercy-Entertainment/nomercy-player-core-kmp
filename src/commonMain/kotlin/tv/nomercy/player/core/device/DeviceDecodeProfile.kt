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
 */
public data class DeviceDecodeProfile(
    /**
     * Best first, and the order is information rather than a set: a consumer
     * that asks a server to transcode wants the most reliably decodable target
     * named first, which on every platform so far is H.264.
     */
    val videoCodecs: List<String> = emptyList(),
    val audioCodecs: List<String> = emptyList(),
    val containers: List<String> = emptyList(),
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,

    /** A DISPLAY trait. */
    val supportsHdr: Boolean = false,

    /**
     * A DECODER trait, and deliberately not the same question as HDR.
     *
     * SDR 10-bit HEVC is routine, and a capable decoder opens it with no HDR
     * display anywhere near it. This is also the field that decides whether a
     * Hi10P file reaches an Android phone as-is, and no Android device has
     * answered yes to it in hardware.
     */
    val supports10Bit: Boolean = false,

    val maxAudioChannels: Int = STEREO,

    /** Zero means no client-imposed cap, and zero is nearly always right. */
    val maxBitrateKbps: Int = NO_CAP,
) {
    public companion object {
        public const val STEREO: Int = 2
        public const val NO_CAP: Int = 0
    }
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
    public const val FLAC: String = "flac"
    public const val OPUS: String = "opus"
    public const val MP3: String = "mp3"
}

public object DecodeContainer {
    public const val HLS: String = "hls"
    public const val MP4: String = "mp4"
    public const val WEBM: String = "webm"
    public const val DASH: String = "dash"
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
