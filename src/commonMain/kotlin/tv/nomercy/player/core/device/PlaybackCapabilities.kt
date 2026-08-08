// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What this device can actually play, in the shape the media server's device
 * hub already expects.
 *
 * The server has had the receiving half of this for months — `DeclareCapabilities`
 * on the device hub, a `DeviceCapabilityRegistry`, a migration, tests and a
 * transcode path that reads it. Nothing has ever called it, on any client, so
 * every device gets whatever the library happens to hold and a file it cannot
 * decode is a black picture rather than a live transcode.
 *
 * Every name and every nullability here is copied from
 * `NoMercy.Encoder/Devices/DeviceCapabilities.cs` rather than chosen. Null is
 * the server's word for "unknown" and zero is not — a zero maximum height is a
 * device declaring it can show nothing, which is a very different instruction
 * to a transcoder.
 */
@Serializable
public data class PlaybackCapabilities(
    /** Canonical lowercase, and only the values the server knows. */
    @SerialName("VideoCodecs")
    val videoCodecs: List<String> = emptyList(),

    @SerialName("AudioCodecs")
    val audioCodecs: List<String> = emptyList(),

    /** 1080, 1440, 2160. Null is unknown, which is not the same as none. */
    @SerialName("MaxVideoHeight")
    val maxVideoHeight: Int? = null,

    /** 2 stereo, 6 for 5.1, 8 for 7.1. Null is unknown. */
    @SerialName("MaxAudioChannels")
    val maxAudioChannels: Int? = null,

    @SerialName("HdrSupport")
    val hdrSupport: Boolean = false,

    /** [DolbyVisionProfile], by ordinal, because that is how the enum crosses. */
    @SerialName("DolbyVision")
    val dolbyVision: Int = DolbyVisionProfile.NONE,

    @SerialName("RamTier")
    val ramTier: Int = DeviceRamTier.STANDARD,

    /** Null leaves the server's own default in place. */
    @SerialName("PlayerBufferCapMb")
    val playerBufferCapMb: Int? = null,

    /**
     * Free-form, and the only place 10-bit fits today.
     *
     * The server's codec vocabulary is h264/hevc/av1/vp9 with no bit depth in
     * it, so "this device decodes 10-bit AVC in software" cannot be said in a
     * field. Inventing one here would be a value the server drops silently.
     * Saying it in the notes at least puts it where somebody reading a wrong
     * transcode decision will find it.
     */
    @SerialName("Notes")
    val notes: String? = null,
)

/** `DolbyVisionProfile` in the server's enum, by ordinal. */
public object DolbyVisionProfile {
    public const val NONE: Int = 0
    public const val PROFILE_5: Int = 1
    public const val PROFILE_7: Int = 2
    public const val PROFILE_8_1: Int = 3
    public const val PROFILE_8_2: Int = 4
}

/** `DeviceRamTier` in the server's enum, by ordinal. */
public object DeviceRamTier {
    public const val LOW_RAM: Int = 0
    public const val STANDARD: Int = 1
    public const val HIGH_RAM: Int = 2
}

/**
 * The codec identifiers the server recognises, written down once.
 *
 * Four, and no bit-depth variants: the server's own comment lists exactly
 * "h264", "hevc", "av1", "vp9". A fifth string invented on this side is a
 * string the server drops without complaining.
 */
public object PlaybackCodec {
    public const val H264: String = "h264"
    public const val HEVC: String = "hevc"
    public const val AV1: String = "av1"
    public const val VP9: String = "vp9"

    public const val AAC: String = "aac"
    public const val AC3: String = "ac3"
    public const val EAC3: String = "eac3"
    public const val FLAC: String = "flac"
    public const val OPUS: String = "opus"
    public const val TRUEHD: String = "truehd"
    public const val DTS: String = "dts"
}

/**
 * What this device can decode, asked of the platform rather than assumed.
 *
 * Every platform answers differently and every one of them can be wrong in a
 * way that only shows as a black screen: Android's list is per-device and stops
 * short of 10-bit AVC, the desktop's is whatever ffmpeg was built with, and
 * Apple's varies by chip generation.
 */
public expect fun platformPlaybackCapabilities(): PlaybackCapabilities
