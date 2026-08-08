// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.format

import kotlin.math.roundToInt

/**
 * A raw volume as the kit's 0..100 scale.
 *
 * One formula rather than the same clamp written at each place that shows a
 * volume, which is how a slider and the readout beside it come to disagree by a
 * unit — the web has this for exactly that reason, shared by its slider value
 * and its `--vol-pct` custom property.
 *
 * Rounded, not truncated: 99.6 shown as 99 while the slider sits at the top is
 * the report that a volume control "never reaches full".
 */
public fun clampVolume(value: Double): Int = when {
    // NaN has no nearest integer, and roundToInt throws on it rather than
    // returning anything — an unusable reading must not take the chrome down.
    value.isNaN() -> 0
    else -> value.coerceIn(MINIMUM, MAXIMUM).roundToInt()
}

private const val MINIMUM = 0.0
private const val MAXIMUM = 100.0
