// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.math.PI
import kotlin.math.cos

// How volume moves during a crossfade.
//
// LINEAR sounds wrong and that is not a matter of taste: two linear ramps sum
// to less power in the middle than either track alone, so every transition
// dips. EQUAL_POWER is the cosine fade whose in and out gains satisfy
// in² + out² = 1 across the whole window, which is why the level holds.
public enum class CrossfadeCurve(public val wire: String) {
    LINEAR("linear"),
    EQUAL_POWER("equal-power"),
    ;

    // Gain in 0..1 for a progress value in 0..1. Out-of-range progress clamps
    // rather than extrapolating: a late frame must not produce a gain above 1.
    public fun gain(linear: Double): Double {
        val clamped: Double = linear.coerceIn(0.0, 1.0)
        return when (this) {
            LINEAR -> clamped
            EQUAL_POWER -> cos((1.0 - clamped) * PI / 2)
        }
    }
}
