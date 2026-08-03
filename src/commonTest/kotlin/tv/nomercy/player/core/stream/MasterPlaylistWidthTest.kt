// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.stream

import tv.nomercy.player.core.media.QualityDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

// Both halves of RESOLUTION, because the menu shows both.
//
// `heightOf` read the part after the x and nothing read the part before it, so
// every rung reached the quality pane with a null width. The pane could name it
// only "536p", beside a browser naming the same stream 1280x536.
class MasterPlaylistWidthTest {

    @Test
    fun aVariantCarriesTheWidthItDeclares() {
        val line = "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x536,CODECS=\"avc1.4d401e\""

        val descriptor: QualityDescriptor? = MasterPlaylistRewriter.descriptorOf(line)

        assertEquals(1280, descriptor?.width)
        assertEquals(536, descriptor?.height)
    }

    @Test
    fun andAVariantWithoutOneIsStillNotAWidthOfZero() {
        // No RESOLUTION at all means no descriptor, which is the existing rule.
        // Asserted here so a width added later cannot quietly turn that into a
        // rung of 0 by 0.
        assertEquals(null, MasterPlaylistRewriter.descriptorOf("#EXT-X-STREAM-INF:BANDWIDTH=2000000"))
    }
}
