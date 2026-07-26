// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// What adaptation is allowed to climb to on this display.
//
// A ladder with HDR rungs on a display that cannot show them is not a quality
// choice, it is a picture that comes out washed out or grey — and adaptation
// will climb into it on its own the moment the connection is good enough, so a
// viewer who never touched a menu gets the broken picture as a reward for good
// bandwidth.
//
// The rule is the web kit's: on an SDR display, cap at the highest SDR rung. It
// is pure and takes a boolean rather than a Display, so the decision is provable
// without a device and the device only has to answer one question.
public object HdrAbrConstraint {

    // The rung adaptation must not exceed, or null when nothing needs capping.
    //
    // Null on an HDR display, and null on an SDR display whose ladder has no HDR
    // in it — because a cap that changes nothing still narrows what adaptation
    // may pick if anything about the comparison is subtly wrong, and the safest
    // constraint is the one not applied.
    public fun abrCeiling(levels: List<QualityLevel>, displayHdr: Boolean): QualityLevel? {
        if (displayHdr) return null
        if (levels.none { it.isHdr() }) return null

        // The best SDR rung, by the same ordering a quality menu uses: height
        // first, then bitrate. Falling back to the lowest rung would be a worse
        // picture than the display can show, which is the opposite mistake.
        return levels.filter { !it.isHdr() }.maxWithOrNull(
            compareBy<QualityLevel> { it.height }.thenBy { it.bitrate },
        )
    }

    // True when every rung is HDR and the display is not. There is nothing to
    // cap to, and the caller has to decide between a bad picture and no picture
    // — which is a decision a library must surface rather than make.
    public fun isUnplayable(levels: List<QualityLevel>, displayHdr: Boolean): Boolean =
        !displayHdr && levels.isNotEmpty() && levels.all { it.isHdr() }

    private fun QualityLevel.isHdr(): Boolean = dynamicRange != DynamicRange.SDR
}
