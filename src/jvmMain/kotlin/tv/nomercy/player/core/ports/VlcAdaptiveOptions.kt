// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.media.QualityDescriptor

// The height limit libVLC honours, for a ladder it is opening from its original
// URL.
//
// The second half of the desktop's answer, not the whole of it. The narrowing
// that CAN express "these exact renditions" is a rewritten master playlist —
// VlcMasterPlaylist, on the same MasterPlaylistRewriter Android uses — and this
// is what remains for the cases that cannot be rewritten: a manifest that could
// not be read, and a caller that supplied a device ladder for a stream this never
// saw the master of.
//
// Measured against the installed VLC 3.0.23 rather than assumed, because the
// original version of this file passed two options and only one of them did
// anything:
//
//   --adaptive-maxheight=900 against a ladder of 1920x818 and 3840x1635 never
//   opened a 3840 rung. It works.
//
//   --adaptive-bw=800 against the same ladder switched onto an 821 kbps
//   rendition on the second segment. It is the assumed bandwidth of the
//   FIXED-RATE adaptation logic, ignored by the default one, and documented in
//   KiB/s — so the number here was in the wrong unit for an option that was not
//   being read. An option that silently does nothing is worse than no option: it
//   is why capping was believed to be in place.
public object VlcAdaptiveOptions {

    // The options that keep adaptation inside [keep].
    //
    // Empty when there is nothing to constrain, because an option that repeats
    // the default still tells libVLC to take a code path it would not otherwise
    // take — and on a demuxer this old, the untaken path is the tested one.
    //
    // Height alone. It cannot tell an HDR rendition from an SDR one at the same
    // resolution, which NoMercy's own ladder has — that case is what the manifest
    // rewrite exists for.
    public fun optionsFor(keep: Collection<QualityDescriptor>): List<String> {
        if (keep.isEmpty()) return emptyList()

        return listOf(":adaptive-maxheight=${keep.maxOf { it.height }}")
    }
}
