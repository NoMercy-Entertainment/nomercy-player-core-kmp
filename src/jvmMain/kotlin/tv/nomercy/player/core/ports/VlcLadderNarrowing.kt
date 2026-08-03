// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.media.DynamicRange as MediaDynamicRange
import tv.nomercy.player.core.media.QualityDescriptor

// Which rungs of a declared ladder this desktop is allowed to adapt into.
//
// Pure, and separate from the file writing, because what is easy to get wrong
// here is a comparison rather than an IO call: the two ceilings answer different
// questions and both have to hold at once.
internal object VlcLadderNarrowing {

    // The manifest's ladder as the shared decisions see it.
    //
    // Width is deliberately absent. A master playlist declares RESOLUTION and the
    // shared descriptor keeps only its height, so claiming a width here would mean
    // inventing one — and SizeAbrConstraint reads an undeclared width as "no
    // opinion" precisely so this platform lands on the same rung the web does.
    internal fun levelsOf(ladder: List<QualityDescriptor>): List<QualityLevel> =
        ladder.map { descriptor: QualityDescriptor ->
            QualityLevel(
                height = descriptor.height,
                bitrate = descriptor.bitrate,
                codec = VlcTrackMapper.codecFamily(descriptor.codec),
                dynamicRange = rangeOf(descriptor.dynamicRange),
                width = descriptor.width,
                label = descriptor.label(),
            )
        }

    // The rungs to keep.
    //
    // Height and dynamic range arrive SEPARATELY rather than as one ceiling rung,
    // and that separation is load-bearing. Reading the range off the ceiling looked
    // tidier and was wrong: on an HDR display the size ceiling is whichever rung is
    // cheapest at its height, which in NoMercy's ladder is the SDR one — so an HDR
    // screen would have been capped to SDR by the constraint that was only ever
    // asked about pixels.
    //
    // [sdrOnly] belongs to whoever asked the display, and it is true exactly when
    // the dynamic-range constraint produced a ceiling of its own.
    //
    // Never narrows to nothing: an empty answer means the comparison was wrong
    // rather than that the item is unplayable, and the caller falls back to what it
    // had. Refusing playback is HdrPolicy's decision to make, not this one's.
    internal fun keep(
        declared: List<QualityDescriptor>,
        playable: Collection<QualityDescriptor>,
        maxHeight: Int?,
        sdrOnly: Boolean,
    ): List<QualityDescriptor> {
        val byDevice: List<QualityDescriptor> = if (playable.isEmpty()) {
            declared
        } else {
            declared.filter { it in playable.toSet() }.ifEmpty { declared }
        }

        val kept: List<QualityDescriptor> = byDevice.filter { descriptor: QualityDescriptor ->
            (maxHeight == null || descriptor.height <= maxHeight) &&
                (!sdrOnly || descriptor.dynamicRange == MediaDynamicRange.Sdr)
        }
        return kept.ifEmpty { byDevice }
    }

    // HLG collapses into HDR10 here, which is what the Android mapper already
    // does: the ports enum has no HLG entry and every decision written against it
    // asks only whether a rung is SDR. A second entry would be a wire-format
    // change for a question nobody asks.
    private fun rangeOf(range: MediaDynamicRange): DynamicRange = when (range) {
        MediaDynamicRange.Sdr -> DynamicRange.SDR
        MediaDynamicRange.Hdr10, MediaDynamicRange.HlG -> DynamicRange.HDR10
        MediaDynamicRange.Hdr10Plus -> DynamicRange.HDR10_PLUS
        MediaDynamicRange.DolbyVision -> DynamicRange.DOLBY_VISION
    }
}
