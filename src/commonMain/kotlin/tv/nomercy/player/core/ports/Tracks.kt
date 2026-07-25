// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

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
)
