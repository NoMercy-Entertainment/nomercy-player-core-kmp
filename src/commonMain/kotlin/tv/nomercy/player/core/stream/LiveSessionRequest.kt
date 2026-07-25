// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// What the client tells the server it can decode, so live transcoding produces
// a stream that plays rather than one discovered unplayable halfway through.
//
// The field names are the media server's, verbatim, from
// `LiveTranscodeController.StartSession` and its `ClientCapabilitiesDto`. This
// is one half of a contract and the other half already exists; inventing a
// nicer shape here would mean a request the server ignores.
//
// Codecs go over as their names rather than their ordinals. The server
// deserialises either, and a name survives someone adding a codec to the middle
// of the enum where an ordinal quietly becomes a different codec.
@Serializable
public data class ClientCapabilities(
    @SerialName("video_codecs") val videoCodecs: List<String>,
    @SerialName("audio_codecs") val audioCodecs: List<String>,
    @SerialName("containers") val containers: List<String>,
    @SerialName("max_width") val maxWidth: Int,
    @SerialName("max_height") val maxHeight: Int,
    @SerialName("supports_hdr") val supportsHdr: Boolean,
    @SerialName("supports_10bit") val supports10Bit: Boolean,
    @SerialName("max_bitrate_kbps") val maxBitrateKbps: Int,
    @SerialName("max_audio_channels") val maxAudioChannels: Int = STEREO,
) {
    public companion object {
        public const val STEREO: Int = 2
    }
}

// The request that starts a live-transcoding session.
//
// Only the file, the capabilities and the start position are required. A null
// preferred quality or audio language means "decide for me", which is what an
// older server does with fields it has never heard of anyway — the absent case
// has to be the safe one, or every server upgrade breaks a client.
@Serializable
public data class LiveSessionRequest(
    @SerialName("video_file_id") val videoFileId: String,
    @SerialName("client_caps") val clientCaps: ClientCapabilities,
    @SerialName("start_time_seconds") val startTimeSeconds: Double = 0.0,
    @SerialName("preferred_quality") val preferredQuality: String? = null,
    @SerialName("audio_language") val audioLanguage: String? = null,
)

// The codec names the server's enums use. Spelled out rather than derived,
// because they are the wire and a rename on either side has to be a visible
// change on both.
public object ServerCodecNames {
    public const val H264: String = "H264"
    public const val H265: String = "H265"
    public const val VP9: String = "Vp9"
    public const val AV1: String = "Av1"

    public const val AAC: String = "Aac"
    public const val FLAC: String = "Flac"
    public const val OPUS: String = "Opus"
    public const val AC3: String = "Ac3"
    public const val EAC3: String = "Eac3"
    public const val MP3: String = "Mp3"
}

// Omits nulls, so a request carrying no preference reaches the server as a
// request with no such field rather than one asking for null.
private val wire: Json = Json { explicitNulls = false; encodeDefaults = true }

public fun LiveSessionRequest.toJson(): String = wire.encodeToString(this)
