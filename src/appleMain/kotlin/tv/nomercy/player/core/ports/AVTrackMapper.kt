// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// AVFoundation's vocabulary against the library's.
//
// The decisions here take values rather than AVFoundation objects, the same way
// the other two mappers do. An AVMediaSelectionOption cannot be constructed in a
// test, and what is easy to get wrong is a locale string and a characteristic
// name — which is a string comparison either way.
public object AVTrackMapper {

    // AVFoundation reports BCP-47 with a region and a script — "pt-BR",
    // "zh-Hans-CN" — where the rest of the library compares plain language tags.
    // Keeping the region makes a Dutch track selected on Android unmatchable on
    // an iPhone; dropping it entirely makes Brazilian and European Portuguese
    // the same track. The tag is kept and the comparison is what has to be
    // lenient, so this only normalises the case and the separator.
    public fun languageOf(tag: String?): String {
        val trimmed: String = tag?.trim().orEmpty()
        if (trimmed.isEmpty()) return "und"
        return trimmed.replace('_', '-').lowercase()
    }

    // AVFoundation gives a display name already localised for the device, which
    // is the right label — it says "Nederlands" on a Dutch phone. Empty happens
    // on tracks a muxer never named.
    public fun labelOf(displayName: String?, language: String?): String =
        displayName?.takeIf { it.isNotBlank() } ?: languageOf(language)

    // The characteristics AVFoundation attaches to a legible option. Forced
    // subtitles are the alien dialogue in an otherwise English film, and a menu
    // that does not mark them shows two identical English rows.
    public fun isForced(characteristics: List<String>): Boolean =
        characteristics.any { it == FORCED_CHARACTERISTIC }

    // A track for the hard of hearing carries the same language as the plain
    // one, so without this the two are indistinguishable in a menu.
    public fun describesMusicAndSound(characteristics: List<String>): Boolean =
        characteristics.any { it == DESCRIBES_MUSIC_AND_SOUND }

    // AVFoundation's estimated data rate is a float in bits per second and can
    // be negative or NaN before the asset is read. A bitrate that is neither
    // sorts a ladder into nonsense.
    public fun bitrateOf(estimatedDataRate: Float): Int = when {
        estimatedDataRate.isNaN() -> 0
        estimatedDataRate <= 0f -> 0
        else -> estimatedDataRate.toInt()
    }

    private const val FORCED_CHARACTERISTIC = "public.subtitles.forced-only"
    private const val DESCRIBES_MUSIC_AND_SOUND = "public.accessibility.describes-music-and-sound"
}
