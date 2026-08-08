// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Which track is selected, and the track itself.
 *
 * The index alone is what the port returned, and an index is only meaningful
 * beside the list it came from — a chrome holding one has to go back and read
 * `subtitles()` to show a name, and any change between the two reads gives it
 * the wrong track's label with the right number. The web returns both together
 * for exactly that reason and these mirror `CurrentSubtitleSelection`,
 * `CurrentAudioTrackSelection` and `CurrentQualitySelection`.
 *
 * Three types rather than one generic, because that is what the web exports and
 * a consumer reading its documentation looks for these names. The port does not
 * get to be cleverer than the contract it is a port of.
 */
public data class CurrentSubtitleSelection(
    /** Zero-based index into the `subtitles()` list. */
    val index: Int,
    /** Full subtitle track metadata at this index. */
    val track: SubtitleTrack,
)

public data class CurrentAudioTrackSelection(
    /** Zero-based index into the `audioTracks()` list. */
    val index: Int,
    /** Full audio track metadata at this index. */
    val track: AudioTrack,
)

public data class CurrentQualitySelection(
    /** Zero-based index into the `qualityLevels()` list. */
    val index: Int,
    /** Full quality-level metadata at this index. */
    val track: QualityLevel,
)

/**
 * Whether this device can decode a codec, and at what cost.
 *
 * Three answers rather than one boolean, because "can decode" and "can decode
 * without dropping frames" are different questions and a client that conflates
 * them plays a slideshow. It is the same distinction the web draws through
 * `mediaCapabilities.decodingInfo`, where `MediaSource.isTypeSupported` answers
 * only the first and is wrong often enough to matter — a Chrome build reports
 * hvc1 support on hardware with no HEVC decoder.
 */
public data class CanPlayResult(
    /** True when this device can decode the codec/container combination. */
    val supported: Boolean,
    /** True when it decodes smoothly, with no dropped frames expected. */
    val smooth: Boolean,
    /** True when it decodes without excessive battery drain. */
    val powerEfficient: Boolean,
)

/**
 * A subtitle track supplied alongside the media rather than inside it.
 *
 * [type] is a free-form flavour — `sdh`, `forced`, `full` — and NOT a container
 * hint: every track added this way is fetched and parsed as WebVTT, the same as
 * the web.
 */
public data class SidecarSubtitleInput(
    /** URL of the subtitle resource. */
    val url: String,
    /** BCP-47 language tag, for example `en` or `nl-NL`. */
    val language: String,
    /** Human-readable label. Falls back to [language] when absent. */
    val label: String? = null,
    /** Flavour, matching `SubtitleTrack.type`. */
    val type: String? = null,
    /** Select this track as soon as it is added. */
    val default: Boolean = false,
)
