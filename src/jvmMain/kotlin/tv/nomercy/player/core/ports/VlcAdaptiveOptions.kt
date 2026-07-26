// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.media.QualityDescriptor

// How the desktop narrows a ladder, which is not by rewriting the manifest.
//
// Android intercepts the playlist because Media3 has no other way to be told
// which variants exist. libVLC does: its adaptive demuxer takes per-media
// options, so the constraint is handed to the engine rather than hidden from it
// by editing what it reads.
//
// That is a real divergence and the better answer on this platform. Rewriting a
// manifest means becoming a parser of record for a format that keeps growing;
// passing an option means libVLC stays the parser and this stays a caller.
public object VlcAdaptiveOptions {

    // The options that keep adaptation inside [keep].
    //
    // Empty when there is nothing to constrain, because an option that repeats
    // the default still tells libVLC to take a code path it would not otherwise
    // take — and on a demuxer this old, the untaken path is the tested one.
    public fun optionsFor(keep: Collection<QualityDescriptor>): List<String> {
        if (keep.isEmpty()) return emptyList()

        val tallest: Int = keep.maxOf { it.height }
        val fastest: Int = keep.maxOf { it.bitrate }

        // Height and bandwidth rather than an explicit variant list: libVLC's
        // adaptive demuxer has no "these exact renditions" option, and the pair
        // is what it does understand. A rung above either limit is not selected.
        return listOf(
            ":adaptive-maxheight=$tallest",
            ":adaptive-bw=${fastest / BITS_PER_KILOBIT}",
        )
    }

    // libVLC's adaptive-bw is in kilobits per second; the ladder is in bits.
    // Passing bits would read as a bandwidth a thousand times larger than any
    // real connection, which is the same as no constraint at all.
    private const val BITS_PER_KILOBIT = 1_000
}
