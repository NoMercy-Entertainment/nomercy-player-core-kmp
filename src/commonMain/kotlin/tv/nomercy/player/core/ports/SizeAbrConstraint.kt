// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// What adaptation is allowed to climb to for the space the picture is drawn in.
//
// A rung taller than the pane costs bandwidth, decode and battery to produce
// pixels that are thrown away on the way to the screen: 3840x1635 into a pane
// 800 device-pixels wide arrives, gets scaled down, and holds frame delivery at
// a sixth of the clip's rate on software decode. That is measured on this
// desktop, not assumed.
//
// The rule mirrors the web kit's size-policy adapter decision for decision, and
// the web is the oracle — this landed there first and this file conforms. Pure
// and taking two integers rather than a surface, so every branch is provable
// without a window.
public object SizeAbrConstraint {

    // The rung adaptation must not exceed for a pane this size, or null when
    // nothing needs capping.
    //
    // The SMALLEST rung that still COVERS the pane, rather than the largest that
    // fits inside it. Capping below the pane would upscale, and a soft picture is
    // a worse answer than a wasteful one — the point is to stop paying for pixels
    // nobody can see, not to start showing fewer than there is room for.
    //
    // Null when the pane has not been measured, because a cap taken from a zero
    // is a cap to the bottom of the ladder. Null too when no rung reaches the
    // pane: the ladder is already smaller than the space, and a cap that changes
    // nothing still narrows what adaptation may pick if anything about the
    // comparison is subtly wrong.
    public fun abrCeiling(
        levels: List<QualityLevel>,
        paneWidthPx: Int,
        paneHeightPx: Int,
    ): QualityLevel? {
        if (paneWidthPx <= 0 || paneHeightPx <= 0) return null

        val ceiling: QualityLevel = levels
            .filter { it.covers(paneWidthPx, paneHeightPx) }
            .minWithOrNull(compareBy<QualityLevel> { it.height }.thenBy { it.bitrate })
            ?: return null

        // A ceiling at the top of the ladder is not a ceiling. Applied, it would
        // still narrow what adaptation may pick if anything about the comparison is
        // subtly wrong, and it can change nothing for the better — the same reason
        // HdrAbrConstraint returns null for a ladder with no HDR in it.
        val tallest: Int = levels.maxOf { it.height }
        return ceiling.takeIf { it.height < tallest }
    }

    // The more restrictive of two ceilings, by the ordering a quality menu uses.
    //
    // Two ceilings exist the moment a display's dynamic range and a pane's size
    // both have something to say, and they answer different questions — so
    // neither may overwrite the other. One writer, one answer: whichever is
    // lower.
    public fun narrower(first: QualityLevel?, second: QualityLevel?): QualityLevel? {
        if (first == null || second == null) return first ?: second

        val byHeight: Int = first.height.compareTo(second.height)
        return when {
            byHeight != 0 -> if (byHeight < 0) first else second
            else -> if (first.bitrate <= second.bitrate) first else second
        }
    }

    // Whether this rung has at least as many pixels as the pane needs in both
    // directions.
    //
    // An UNDECLARED width passes rather than failing. It is not a narrow width:
    // the desktop ladder is read from an HLS master playlist through a descriptor
    // that carries no width at all, and treating that absence as zero would drop
    // every rung and cap to the top of the ladder — the opposite of the intent.
    // Falling back to the height instead is just as wrong, and worse because it
    // looks reasonable: on a 1200x300 pane a 1920x818 rung would read as 818 wide,
    // fail the test, and hand the viewer 4K.
    private fun QualityLevel.covers(paneWidthPx: Int, paneHeightPx: Int): Boolean {
        if (height < paneHeightPx) return false
        val declaredWidth: Int = width ?: return true
        return declaredWidth >= paneWidthPx
    }
}
