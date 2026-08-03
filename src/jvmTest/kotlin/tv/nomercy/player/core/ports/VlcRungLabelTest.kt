// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals

// What the quality menu is allowed to say a rung is called.
//
// Measured on a two-video-track file through real libVLC: both rungs came back
// `label=und`. The container path was naming a rung with the AUDIO labeller,
// which answers from the track's language, and a video track's language is
// undefined. So a viewer picking a quality chose between "und" and "und", and a
// single-rung file called its only quality "und" too.
class VlcRungLabelTest {

    @Test
    fun aRungIsNamedByItsHeight() {
        assertEquals("1080p", VlcTrackMapper.rungLabel(1080, DynamicRange.SDR))
    }

    @Test
    fun andCarriesItsDynamicRangeWhenItIsNotOrdinary() {
        // The manifest side already reads this way — "1635p HDR10" — and two
        // rungs of the same height with different ranges are otherwise one name
        // twice.
        assertEquals("2160p HDR10", VlcTrackMapper.rungLabel(2160, DynamicRange.HDR10))
        assertEquals("2160p DOLBY-VISION", VlcTrackMapper.rungLabel(2160, DynamicRange.DOLBY_VISION))
    }
}
