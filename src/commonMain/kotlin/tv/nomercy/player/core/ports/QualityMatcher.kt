// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Where a quality descriptor becomes an engine's own index, and the only place
// it does.
//
// Every engine numbers its variants differently, and the list a viewer sees is
// filtered by what the device can decode — so position three on screen is not
// position three in the engine. Selecting by index across that gap is how
// "switch to 1080p" ends up playing 480p, silently, on one device and not
// another.
//
// The whole library therefore selects by descriptor, and exactly one function
// crosses back. Keeping the crossing in one testable place is what makes the
// rule enforceable rather than aspirational: an engine binding that indexed
// directly would have to route around this to do it.
public object QualityMatcher {

    // The engine index of the variant that matches [target], or null when the
    // engine has nothing like it.
    //
    // Null rather than a nearest guess. A viewer who picked HDR and got SDR was
    // not served — they were quietly given something else — and an engine that
    // has dropped a variant since the menu was drawn is a real case on a live
    // stream. The caller can decide to fall back; this cannot decide for it.
    public fun match(target: QualityLevel, candidates: List<QualityLevel>): Int? {
        val exact: Int = candidates.indexOfFirst { it.isSameStreamAs(target) }
        if (exact >= 0) return exact

        // Height, range and codec are identity; bitrate is not. The same variant
        // is re-advertised at a slightly different rate often enough — a
        // re-packaged master, a manifest refresh — that treating a bitrate
        // change as a different stream would lose the selection on a reload.
        return candidates
            .withIndex()
            .filter { (_, candidate) -> candidate.isSameContentAs(target) }
            .minByOrNull { (_, candidate) -> abs(candidate.bitrate - target.bitrate) }
            ?.index
    }

    private fun QualityLevel.isSameStreamAs(other: QualityLevel): Boolean =
        isSameContentAs(other) && bitrate == other.bitrate

    // Codec comparison is case-insensitive on purpose. The same codec arrives as
    // "hvc1" from one engine and "HVC1" from another, and a menu selection that
    // survived a device rotation but not an engine swap would be a bug nobody
    // could reproduce.
    private fun QualityLevel.isSameContentAs(other: QualityLevel): Boolean =
        height == other.height &&
            dynamicRange == other.dynamicRange &&
            codec.equals(other.codec, ignoreCase = true)

    private fun abs(value: Int): Int = if (value < 0) -value else value
}
