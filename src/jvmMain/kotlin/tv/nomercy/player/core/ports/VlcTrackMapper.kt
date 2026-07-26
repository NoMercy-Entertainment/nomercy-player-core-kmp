// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// libVLC's view of a stream, turned into the library's.
//
// The decisions here take values rather than libVLC objects, for the same reason
// the Media3 mapper's do: a TrackInfo cannot be constructed without a media
// handle, and what is actually easy to get wrong is a string and an integer.
//
// libVLC names codecs in its own vocabulary — "h264", "hevc", "mp4a" — and the
// library compares against the RFC 6381 families every other engine reports. A
// selection made on Android and restored on the desktop has to survive that
// translation or it silently falls back.
public object VlcTrackMapper {

    // libVLC's codec name to the family the rest of the library compares on.
    // Unmapped names pass through: a codec nobody translated is still better
    // identified by its own name than by a blank that collides with every other
    // unknown.
    public fun codecFamily(codecName: String?): String = when (codecName?.lowercase()) {
        null, "" -> "unknown"
        "h264", "avc1", "avc" -> "avc1"
        "hevc", "h265", "hvc1", "hev1" -> "hvc1"
        "av01", "av1" -> "av01"
        "vp09", "vp9" -> "vp09"
        "mp4a", "aac" -> "mp4a"
        "a52", "ac-3", "ac3" -> "ac-3"
        "eac3", "ec-3" -> "ec-3"
        "opus" -> "opus"
        "flac" -> "flac"
        else -> codecName.lowercase()
    }

    // libVLC reports no transfer characteristics through its track info, so
    // every rung reads as SDR. Guessing HDR from a codec would be wrong on the
    // majority of HEVC, which is exactly the mistake the Media3 mapper avoids
    // by reading the transfer function — a field libVLC does not expose here.
    //
    // Named rather than inlined, so the day it does expose one there is a single
    // place to change and a test already pointing at it.
    public fun dynamicRange(): DynamicRange = DynamicRange.SDR

    // libVLC leaves language empty rather than null on plenty of tracks, and a
    // menu row titled "" is a row a viewer cannot choose.
    public fun languageOf(language: String?): String =
        language?.takeIf { it.isNotBlank() } ?: "und"

    public fun labelOf(description: String?, language: String?): String =
        description?.takeIf { it.isNotBlank() } ?: languageOf(language)

    // libVLC reports bitrate as 0 for a track it has not measured, which is most
    // local files. Zero is honest; a made-up number would sort a ladder wrongly.
    public fun bitrateOf(bitRate: Int): Int = bitRate.coerceAtLeast(0)
}
