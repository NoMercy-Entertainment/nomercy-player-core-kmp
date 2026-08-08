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
import kotlinx.serialization.json.Json

/**
 * What this client can decode, sent with a live-session request so the server
 * transcodes only what it has to.
 *
 * This is the contract the web player already speaks — `client_caps` on
 * `POST api/v1/streaming/live/sessions`, read by `PlaybackDecisionEngine`,
 * which answers DirectPlay or one of the transcode actions. It is not the
 * device hub's `DeviceCapabilities`; that is a separate registry record for
 * device-aware defaults, and it has no caller on any client.
 *
 * Every field name, and the fact that they are all optional, is copied from
 * `ClientCapabilitiesDto` on both sides rather than chosen.
 */
@Serializable
public data class ClientCapabilities(
    /**
     * Best first: the server transcodes to the client's FIRST listed codec, so
     * the order is an instruction rather than a set. Web lists H264 first for
     * that reason — it is the most reliably decodable target.
     */
    @SerialName("video_codecs")
    val videoCodecs: List<String> = emptyList(),

    @SerialName("audio_codecs")
    val audioCodecs: List<String> = emptyList(),

    @SerialName("containers")
    val containers: List<String> = emptyList(),

    @SerialName("max_width")
    val maxWidth: Int? = null,

    @SerialName("max_height")
    val maxHeight: Int? = null,

    /** A DISPLAY trait. */
    @SerialName("supports_hdr")
    val supportsHdr: Boolean = false,

    /**
     * A DECODER trait, and deliberately not the same question as HDR.
     *
     * NoMercy routinely encodes SDR 10-bit HEVC, which a capable decoder opens
     * with no HDR display anywhere near it. This is also the field that decides
     * whether a Hi10P file reaches an Android phone as-is or as a transcode,
     * and no Android device has ever answered yes to it in hardware.
     */
    @SerialName("supports_10bit")
    val supports10Bit: Boolean = false,

    @SerialName("max_audio_channels")
    val maxAudioChannels: Int = STEREO,

    /**
     * Zero means "no client-imposed cap", and zero is nearly always right.
     *
     * The server hard-transcodes whenever the source bitrate exceeds this, so a
     * throttled estimate forces live transcoding of perfectly compatible files
     * over a LAN — which is the primary self-hosted case. In-session adaptation
     * is a separate channel and the correct one for a bandwidth signal.
     */
    @SerialName("max_bitrate_kbps")
    val maxBitrateKbps: Int = NO_CAP,
) {

    /**
     * The payload as the server reads it.
     *
     * Its own encoder, because kotlinx drops any field still at its default and
     * three of these fields have meaningful defaults — `max_bitrate_kbps = 0`
     * says "no cap", and omitting it leaves the server on whatever ITS default
     * is. A caller reaching for a plain `Json` would produce a payload that is
     * valid, silently shorter, and decided differently.
     */
    public fun toJson(): String = ENCODER.encodeToString(serializer(), this)

    private companion object {
        val ENCODER: Json = Json { encodeDefaults = true }
    }
}

/**
 * The codec names the server's enums deserialise from, PascalCase.
 *
 * The wire vocabulary here is the encoder's `VideoCodecType`/`AudioCodecType`
 * through a StringEnumConverter, which is a different spelling from the device
 * hub's lowercase list. Two vocabularies for two contracts, both the server's,
 * neither ours to tidy.
 */
public object ClientCodec {
    public const val H264: String = "H264"
    public const val H265: String = "H265"
    public const val AV1: String = "AV1"

    public const val AAC: String = "AAC"
    public const val EAC3: String = "EAC3"
    public const val AC3: String = "AC3"
    public const val FLAC: String = "FLAC"
    public const val OPUS: String = "Opus"
    public const val MP3: String = "MP3"
}

public object ClientContainer {
    public const val HLS: String = "HLS"
    public const val MP4: String = "MP4"
    public const val WEBM: String = "WebM"
    public const val DASH: String = "DASH"
}

/**
 * The three widths the server is told about, because a client reporting its
 * exact panel size gets a ladder built for that one panel.
 *
 * Web clamps to these and this has to agree with it, or the same television
 * gets a different decision through two clients.
 */
public object ClientResolution {
    public const val UHD: Int = 3840
    public const val FHD: Int = 1920
    public const val HD: Int = 1280

    public fun clamp(raw: Int): Int = when {
        raw >= UHD -> UHD
        raw >= FHD -> FHD
        else -> HD
    }
}

internal const val STEREO: Int = 2
internal const val NO_CAP: Int = 0

/**
 * This device's capabilities, probed rather than declared.
 *
 * Each platform has a different way of being asked and a different way of
 * lying: Android's MediaCodecList is per-device, Apple answers through
 * AVFoundation, and the desktop's answer depends on what ffmpeg was built with
 * and whether a payload installed at all.
 */
public expect fun platformClientCapabilities(): ClientCapabilities
