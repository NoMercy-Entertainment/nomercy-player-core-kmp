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
import kotlin.test.assertNull
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
    // A rendition whose RESOLUTION is not <int>x<int> is off the ladder.
    //
    // Tears of Steel shipped RESOLUTION=video_3840x1714 — a directory name in
    // the attribute. The height parsed as 1714, the width parsed as nothing,
    // and the rung was accepted as the HIGHEST on the ladder. Its media 404s,
    // so the film played audio, drew subtitles and chapter markers, and showed
    // a black rectangle. That reads as a broken decoder and is not one.
    @Test
    fun aResolutionThatIsNotWidthByHeightIsNotAVariant() {
        assertNull(
            MasterPlaylistRewriter.descriptorOf(
                """#EXT-X-STREAM-INF:BANDWIDTH=734000,CODECS="avc1.4d4015",RESOLUTION=video_3840x1714""",
            ),
        )
    }

    @Test
    fun theRemainingVariantsAreTheOnesAPlayerCanDescribe() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=734000,RESOLUTION=video_3840x1714
            video_video_3840x1714/x.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=734000,RESOLUTION=1920x1080
            video_1920x1080/x.m3u8
        """.trimIndent()

        assertEquals(listOf(1080), MasterPlaylistRewriter.variants(manifest).map { it.height })
    }

}
