// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.media.DynamicRange as MediaDynamicRange

public enum class DynamicRange(public val wire: String) {
    SDR("sdr"),
    HDR10("hdr10"),
    HDR10_PLUS("hdr10+"),
    DOLBY_VISION("dolby-vision"),
}

// One rendition, identified by what it is rather than where it sits in a list.
//
// Index identity is the recurring bug in every player that has one: the ladder
// is filtered by device capability, so position 2 in the manifest is not
// position 2 in the list the viewer saw, and "switch to 2" silently picks the
// wrong stream. Two renditions are the same rendition when their descriptors
// match, whatever list either came from.
public data class QualityLevel(
    val height: Int,
    val bitrate: Int,
    val codec: String,
    // Part of the identity, not a display detail: the same height and bitrate
    // in HDR10 is a different stream that a device may not be able to play.
    val dynamicRange: DynamicRange = DynamicRange.SDR,
    val width: Int? = null,
    val label: String? = null,
)

// An audio rendition. Selected by language and label, because "English" and
// "English (Commentary)" are different tracks a viewer chooses between.
public data class AudioTrack(
    val id: String,
    val language: String,
    val label: String,
    val channels: Int = 2,
    val codec: String? = null,
)

// A subtitle or caption track. Selecting null means captions off.
public data class SubtitleTrack(
    val id: String,
    val language: String,
    val label: String,
    val format: String = "vtt",
    // Forced subtitles are the ones shown regardless of the caption setting:
    // the alien dialogue in an otherwise English film.
    val forced: Boolean = false,
    // Where the file is, when this track is a sidecar rather than one the engine
    // found in the container.
    //
    // The web's SubtitleTrack has carried this since v1 and this did not, which
    // made addSubtitleTrack a list a track could be appended to and nothing
    // more: the player was handed a name, a language and no way to reach the
    // bytes, so selecting it asked the engine for a track it had never reported
    // and the picture stayed blank. A .srt or .vtt sitting beside a film — the
    // normal case in a NoMercy library — was unreachable by construction.
    //
    // Null for an engine-reported track. That is the honest answer: the file is
    // inside the container the engine already has open, and there is no separate
    // URL to fetch.
    val url: String? = null,
)

// A manifest's declared dynamic range, in the vocabulary a quality level uses.
//
// The two enums exist because a descriptor is what a PLAYLIST says and a level is
// what a MENU shows, and they are allowed to diverge. The conversion between them
// was written once per platform and was about to be written a third time, which
// is the shape that guarantees the copies drift.
public fun declaredRangeOf(range: MediaDynamicRange): DynamicRange = when (range) {
    MediaDynamicRange.Sdr -> DynamicRange.SDR
    MediaDynamicRange.Hdr10, MediaDynamicRange.HlG -> DynamicRange.HDR10
    MediaDynamicRange.Hdr10Plus -> DynamicRange.HDR10_PLUS
    MediaDynamicRange.DolbyVision -> DynamicRange.DOLBY_VISION
}
