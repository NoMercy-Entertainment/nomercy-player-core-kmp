// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.stream

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An audio-only rung must not survive into a narrowed ladder.
 *
 * Apple's BipBop declares `gear0` with no RESOLUTION and `CODECS="mp4a.40.2"` —
 * audio and nothing else, 41 kbps. The rewriter kept every resolution-less
 * variant on the reasoning that "an audio-only or muxed rung legitimately
 * carries none", so libVLC was handed a narrowed playlist whose last entry was
 * that rung, opened it, and played the sound over a black rectangle with the
 * clock running. It is the only demuxed stream in the testbed, which is why it
 * was the only item broken this way.
 *
 * The distinction is declared, not guessed: a rung with no resolution AND a
 * CODECS list naming only audio cannot carry a picture. One that declares no
 * codecs at all still can, and is kept.
 */
class AudioOnlyRungTest {

    private val bipbop = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=263851,CODECS="mp4a.40.2, avc1.4d400d",RESOLUTION=416x234
        gear1/prog_index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1030138,CODECS="mp4a.40.2, avc1.4d401f",RESOLUTION=1280x720
        gear4/prog_index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=41457,CODECS="mp4a.40.2"
        gear0/prog_index.m3u8
    """.trimIndent()

    @Test
    fun theAudioOnlyRungIsDropped() {
        // Read out of the manifest rather than hand-built. A descriptor carries
        // its codec and dynamic range too, so a constructed one does not equal
        // the parsed one and every rung gets dropped for the wrong reason.
        val keep = MasterPlaylistRewriter.variants(bipbop)

        val out = MasterPlaylistRewriter.rewrite(bipbop, keep)

        assertTrue("gear1/prog_index.m3u8" in out, "the 234 rung should survive")
        assertTrue("gear4/prog_index.m3u8" in out, "the 720 rung should survive")
        assertTrue("gear0/prog_index.m3u8" !in out, "the audio-only rung must not survive")
    }

    @Test
    fun aRungDeclaringNoCodecsAtAllIsKept() {
        // No resolution and no codecs is an unknown, not an audio track. Dropping
        // it would discard a rendition on the strength of an attribute the server
        // chose not to send.
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=263851,RESOLUTION=416x234
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=41457
            unknown.m3u8
        """.trimIndent()

        val out = MasterPlaylistRewriter.rewrite(manifest, MasterPlaylistRewriter.variants(manifest))

        assertTrue("unknown.m3u8" in out, "a rung declaring nothing is not an audio rung")
    }
}
