// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

// A named set of band gains.
//
// Extracted from the web player's presets.ts rather than retyped. These are
// nineteen lists of ten numbers each, and a hand-copied one would be wrong
// somewhere nobody would notice until a listener said the rock preset sounded
// off.
public data class EqPreset(
    val name: String,
    val bands: List<EqBand>,
)

public object EqPresets {

    public val CUSTOM: EqPreset = EqPreset(
        name = "Custom",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 0.0),
            EqBand(frequency = 180, gainDb = 0.0),
            EqBand(frequency = 320, gainDb = 0.0),
            EqBand(frequency = 600, gainDb = 0.0),
            EqBand(frequency = 1000, gainDb = 0.0),
            EqBand(frequency = 3000, gainDb = 0.0),
            EqBand(frequency = 6000, gainDb = 0.0),
            EqBand(frequency = 12000, gainDb = 0.0),
            EqBand(frequency = 14000, gainDb = 0.0),
            EqBand(frequency = 16000, gainDb = 0.0),
        ),
    )

    public val CLASSICAL: EqPreset = EqPreset(
        name = "Classical",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 0.375),
            EqBand(frequency = 180, gainDb = 0.375),
            EqBand(frequency = 320, gainDb = 0.375),
            EqBand(frequency = 600, gainDb = 0.375),
            EqBand(frequency = 1000, gainDb = 0.375),
            EqBand(frequency = 3000, gainDb = 0.375),
            EqBand(frequency = 6000, gainDb = -4.5),
            EqBand(frequency = 12000, gainDb = -4.5),
            EqBand(frequency = 14000, gainDb = -4.5),
            EqBand(frequency = 16000, gainDb = -6.0),
        ),
    )

    public val CLUB: EqPreset = EqPreset(
        name = "Club",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 0.375),
            EqBand(frequency = 180, gainDb = 0.375),
            EqBand(frequency = 320, gainDb = 2.25),
            EqBand(frequency = 600, gainDb = 3.75),
            EqBand(frequency = 1000, gainDb = 3.75),
            EqBand(frequency = 3000, gainDb = 3.75),
            EqBand(frequency = 6000, gainDb = 2.25),
            EqBand(frequency = 12000, gainDb = 0.375),
            EqBand(frequency = 14000, gainDb = 0.375),
            EqBand(frequency = 16000, gainDb = 0.375),
        ),
    )

    public val DANCE: EqPreset = EqPreset(
        name = "Dance",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 6.0),
            EqBand(frequency = 180, gainDb = 4.5),
            EqBand(frequency = 320, gainDb = 1.5),
            EqBand(frequency = 600, gainDb = 0.0),
            EqBand(frequency = 1000, gainDb = 0.0),
            EqBand(frequency = 3000, gainDb = -3.75),
            EqBand(frequency = 6000, gainDb = -4.5),
            EqBand(frequency = 12000, gainDb = -4.5),
            EqBand(frequency = 14000, gainDb = 0.0),
            EqBand(frequency = 16000, gainDb = 0.0),
        ),
    )

    public val FLAT: EqPreset = EqPreset(
        name = "Flat",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 0.375),
            EqBand(frequency = 180, gainDb = 0.375),
            EqBand(frequency = 320, gainDb = 0.375),
            EqBand(frequency = 600, gainDb = 0.375),
            EqBand(frequency = 1000, gainDb = 0.375),
            EqBand(frequency = 3000, gainDb = 0.375),
            EqBand(frequency = 6000, gainDb = 0.375),
            EqBand(frequency = 12000, gainDb = 0.375),
            EqBand(frequency = 14000, gainDb = 0.375),
            EqBand(frequency = 16000, gainDb = 0.375),
        ),
    )

    public val LAPTOP_SPEAKERS_HEADPHONES: EqPreset = EqPreset(
        name = "Laptop speakers/headphones",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 3.0),
            EqBand(frequency = 180, gainDb = 6.75),
            EqBand(frequency = 320, gainDb = 3.375),
            EqBand(frequency = 600, gainDb = -2.25),
            EqBand(frequency = 1000, gainDb = -1.5),
            EqBand(frequency = 3000, gainDb = 1.125),
            EqBand(frequency = 6000, gainDb = 3.0),
            EqBand(frequency = 12000, gainDb = 6.0),
            EqBand(frequency = 14000, gainDb = 7.875),
            EqBand(frequency = 16000, gainDb = 9.0),
        ),
    )

    public val LARGE_HALL: EqPreset = EqPreset(
        name = "Large hall",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 6.375),
            EqBand(frequency = 180, gainDb = 6.375),
            EqBand(frequency = 320, gainDb = 3.75),
            EqBand(frequency = 600, gainDb = 3.75),
            EqBand(frequency = 1000, gainDb = 0.375),
            EqBand(frequency = 3000, gainDb = -3.0),
            EqBand(frequency = 6000, gainDb = -3.0),
            EqBand(frequency = 12000, gainDb = -3.0),
            EqBand(frequency = 14000, gainDb = 0.375),
            EqBand(frequency = 16000, gainDb = 0.375),
        ),
    )

    public val PARTY: EqPreset = EqPreset(
        name = "Party",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 4.5),
            EqBand(frequency = 180, gainDb = 4.5),
            EqBand(frequency = 320, gainDb = 0.375),
            EqBand(frequency = 600, gainDb = 0.375),
            EqBand(frequency = 1000, gainDb = 0.375),
            EqBand(frequency = 3000, gainDb = 0.375),
            EqBand(frequency = 6000, gainDb = 0.375),
            EqBand(frequency = 12000, gainDb = 0.375),
            EqBand(frequency = 14000, gainDb = 4.5),
            EqBand(frequency = 16000, gainDb = 4.5),
        ),
    )

    public val POP: EqPreset = EqPreset(
        name = "Pop",
        bands = listOf(
            EqBand(frequency = 70, gainDb = -1.125),
            EqBand(frequency = 180, gainDb = 3.0),
            EqBand(frequency = 320, gainDb = 4.5),
            EqBand(frequency = 600, gainDb = 4.875),
            EqBand(frequency = 1000, gainDb = 3.375),
            EqBand(frequency = 3000, gainDb = -0.75),
            EqBand(frequency = 6000, gainDb = -1.5),
            EqBand(frequency = 12000, gainDb = -1.5),
            EqBand(frequency = 14000, gainDb = -1.125),
            EqBand(frequency = 16000, gainDb = -1.125),
        ),
    )

    public val REGGAE: EqPreset = EqPreset(
        name = "Reggae",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 0.375),
            EqBand(frequency = 180, gainDb = 0.375),
            EqBand(frequency = 320, gainDb = -0.375),
            EqBand(frequency = 600, gainDb = -3.75),
            EqBand(frequency = 1000, gainDb = 0.375),
            EqBand(frequency = 3000, gainDb = 4.125),
            EqBand(frequency = 6000, gainDb = 4.125),
            EqBand(frequency = 12000, gainDb = 0.375),
            EqBand(frequency = 14000, gainDb = 0.375),
            EqBand(frequency = 16000, gainDb = 0.375),
        ),
    )

    public val ROCK: EqPreset = EqPreset(
        name = "Rock",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 4.875),
            EqBand(frequency = 180, gainDb = 3.0),
            EqBand(frequency = 320, gainDb = -3.375),
            EqBand(frequency = 600, gainDb = -4.875),
            EqBand(frequency = 1000, gainDb = -2.25),
            EqBand(frequency = 3000, gainDb = 2.625),
            EqBand(frequency = 6000, gainDb = 5.625),
            EqBand(frequency = 12000, gainDb = 6.75),
            EqBand(frequency = 14000, gainDb = 6.75),
            EqBand(frequency = 16000, gainDb = 6.75),
        ),
    )

    public val SOFT: EqPreset = EqPreset(
        name = "Soft",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 3.0),
            EqBand(frequency = 180, gainDb = 1.125),
            EqBand(frequency = 320, gainDb = -0.75),
            EqBand(frequency = 600, gainDb = -1.5),
            EqBand(frequency = 1000, gainDb = -0.75),
            EqBand(frequency = 3000, gainDb = 2.625),
            EqBand(frequency = 6000, gainDb = 5.25),
            EqBand(frequency = 12000, gainDb = 6.0),
            EqBand(frequency = 14000, gainDb = 6.75),
            EqBand(frequency = 16000, gainDb = 7.5),
        ),
    )

    public val SKA: EqPreset = EqPreset(
        name = "Ska",
        bands = listOf(
            EqBand(frequency = 70, gainDb = -1.5),
            EqBand(frequency = 180, gainDb = -3.0),
            EqBand(frequency = 320, gainDb = -2.625),
            EqBand(frequency = 600, gainDb = -0.375),
            EqBand(frequency = 1000, gainDb = 2.625),
            EqBand(frequency = 3000, gainDb = 3.75),
            EqBand(frequency = 6000, gainDb = 5.625),
            EqBand(frequency = 12000, gainDb = 6.0),
            EqBand(frequency = 14000, gainDb = 6.75),
            EqBand(frequency = 16000, gainDb = 6.0),
        ),
    )

    public val FULL_BASS: EqPreset = EqPreset(
        name = "Full Bass",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 6.0),
            EqBand(frequency = 180, gainDb = 6.0),
            EqBand(frequency = 320, gainDb = 6.0),
            EqBand(frequency = 600, gainDb = 3.75),
            EqBand(frequency = 1000, gainDb = 1.125),
            EqBand(frequency = 3000, gainDb = -2.625),
            EqBand(frequency = 6000, gainDb = -5.25),
            EqBand(frequency = 12000, gainDb = -6.375),
            EqBand(frequency = 14000, gainDb = -6.75),
            EqBand(frequency = 16000, gainDb = -6.75),
        ),
    )

    public val SOFT_ROCK: EqPreset = EqPreset(
        name = "Soft Rock",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 2.625),
            EqBand(frequency = 180, gainDb = 2.625),
            EqBand(frequency = 320, gainDb = 1.5),
            EqBand(frequency = 600, gainDb = -0.375),
            EqBand(frequency = 1000, gainDb = -2.625),
            EqBand(frequency = 3000, gainDb = -3.375),
            EqBand(frequency = 6000, gainDb = -2.25),
            EqBand(frequency = 12000, gainDb = -0.375),
            EqBand(frequency = 14000, gainDb = 1.875),
            EqBand(frequency = 16000, gainDb = 5.625),
        ),
    )

    public val FULL_TREBLE: EqPreset = EqPreset(
        name = "Full Treble",
        bands = listOf(
            EqBand(frequency = 70, gainDb = -6.0),
            EqBand(frequency = 180, gainDb = -6.0),
            EqBand(frequency = 320, gainDb = -6.0),
            EqBand(frequency = 600, gainDb = -2.625),
            EqBand(frequency = 1000, gainDb = 1.875),
            EqBand(frequency = 3000, gainDb = 6.75),
            EqBand(frequency = 6000, gainDb = 9.75),
            EqBand(frequency = 12000, gainDb = 9.75),
            EqBand(frequency = 14000, gainDb = 9.75),
            EqBand(frequency = 16000, gainDb = 10.5),
        ),
    )

    public val FULL_BASS_TREBLE: EqPreset = EqPreset(
        name = "Full Bass & Treble",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 4.5),
            EqBand(frequency = 180, gainDb = 3.75),
            EqBand(frequency = 320, gainDb = 0.375),
            EqBand(frequency = 600, gainDb = -4.5),
            EqBand(frequency = 1000, gainDb = -3.0),
            EqBand(frequency = 3000, gainDb = 1.125),
            EqBand(frequency = 6000, gainDb = 5.25),
            EqBand(frequency = 12000, gainDb = 6.75),
            EqBand(frequency = 14000, gainDb = 7.5),
            EqBand(frequency = 16000, gainDb = 7.5),
        ),
    )

    public val LIVE: EqPreset = EqPreset(
        name = "Live",
        bands = listOf(
            EqBand(frequency = 70, gainDb = -3.0),
            EqBand(frequency = 180, gainDb = 0.375),
            EqBand(frequency = 320, gainDb = 2.625),
            EqBand(frequency = 600, gainDb = 3.375),
            EqBand(frequency = 1000, gainDb = 3.75),
            EqBand(frequency = 3000, gainDb = 3.75),
            EqBand(frequency = 6000, gainDb = 2.625),
            EqBand(frequency = 12000, gainDb = 1.875),
            EqBand(frequency = 14000, gainDb = 1.875),
            EqBand(frequency = 16000, gainDb = 1.5),
        ),
    )

    public val TECHNO: EqPreset = EqPreset(
        name = "Techno",
        bands = listOf(
            EqBand(frequency = 70, gainDb = 4.875),
            EqBand(frequency = 180, gainDb = 3.75),
            EqBand(frequency = 320, gainDb = 0.375),
            EqBand(frequency = 600, gainDb = -3.375),
            EqBand(frequency = 1000, gainDb = -3.0),
            EqBand(frequency = 3000, gainDb = 0.375),
            EqBand(frequency = 6000, gainDb = 4.875),
            EqBand(frequency = 12000, gainDb = 6.0),
            EqBand(frequency = 14000, gainDb = 6.0),
            EqBand(frequency = 16000, gainDb = 5.625),
        ),
    )

    // In the order the web lists them, because a menu built from this should
    // read the same on both.
    public val BUILTIN: List<EqPreset> = listOf(
        CUSTOM,
        CLASSICAL,
        CLUB,
        DANCE,
        FLAT,
        LAPTOP_SPEAKERS_HEADPHONES,
        LARGE_HALL,
        PARTY,
        POP,
        REGGAE,
        ROCK,
        SOFT,
        SKA,
        FULL_BASS,
        SOFT_ROCK,
        FULL_TREBLE,
        FULL_BASS_TREBLE,
        LIVE,
        TECHNO,
    )

    public fun byName(name: String): EqPreset? =
        BUILTIN.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
