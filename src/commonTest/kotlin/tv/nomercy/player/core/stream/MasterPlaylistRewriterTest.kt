// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.stream

import tv.nomercy.player.core.media.DynamicRange
import tv.nomercy.player.core.media.QualityDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The fixtures are RFC 8216's own master-playlist shapes, not one server's
// output. A rewriter tested against a single server's formatting passes until it
// meets a second server.

private val MASTER = """
    #EXTM3U
    #EXT-X-INDEPENDENT-SEGMENTS

    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="en",URI="a/en.m3u8"
    #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="s/en.m3u8"

    #EXT-X-STREAM-INF:BANDWIDTH=1280000,AVERAGE-BANDWIDTH=1000000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=854x480,AUDIO="aac",SUBTITLES="subs"
    v/480.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=7680000,AVERAGE-BANDWIDTH=6000000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080,VIDEO-RANGE=SDR,AUDIO="aac"
    v/1080.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=24000000,AVERAGE-BANDWIDTH=20000000,CODECS="av01.0.13M.10,mp4a.40.2",RESOLUTION=3840x2160,VIDEO-RANGE=PQ,AUDIO="aac"
    v/2160.m3u8

    #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=180000,CODECS="avc1.640028",RESOLUTION=1920x1080,URI="i/1080.m3u8"
""".trimIndent()

class MasterPlaylistRewriterTest {

    private val sd = QualityDescriptor(480, 1_000_000, DynamicRange.Sdr, "avc1.640028")
    private val hd = QualityDescriptor(1080, 6_000_000, DynamicRange.Sdr, "avc1.640028")
    private val uhd = QualityDescriptor(2160, 20_000_000, DynamicRange.Hdr10, "av01.0.13M.10")

    @Test
    fun everyVariantIsReadWithItsIdentity() {
        assertEquals(listOf(sd, hd, uhd), MasterPlaylistRewriter.variants(MASTER))
    }

    @Test
    fun averageBandwidthIsPreferredOverThePeak() {
        // BANDWIDTH is the peak the spec requires; AVERAGE-BANDWIDTH is the
        // honest number for choosing a rendition.
        assertEquals(6_000_000, MasterPlaylistRewriter.variants(MASTER)[1].bitrate)
    }

    @Test
    fun aQuotedCodecListContainingCommasIsNotSplitOnThem() {
        // CODECS="avc1.640028,mp4a.40.2" is the common case, and splitting the
        // attribute list on commas without tracking quotes mangles every line
        // that has one.
        assertEquals("avc1.640028", MasterPlaylistRewriter.variants(MASTER)[0].codec)
        assertEquals("av01.0.13M.10", MasterPlaylistRewriter.variants(MASTER)[2].codec)
    }

    @Test
    fun videoRangePqIsReadAsHdr() {
        assertEquals(DynamicRange.Hdr10, MasterPlaylistRewriter.variants(MASTER)[2].dynamicRange)
        assertEquals(DynamicRange.Sdr, MasterPlaylistRewriter.variants(MASTER)[1].dynamicRange)
    }

    @Test
    fun aVariantWithNoResolutionCannotBeIdentifiedAndIsNotGuessedAt() {
        assertNull(
            MasterPlaylistRewriter.descriptorOf(
                "#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS=\"mp4a.40.2\"",
            ),
        )
    }

    @Test
    fun droppingAVariantDropsItsUriLineWithIt() {
        val rewritten = MasterPlaylistRewriter.rewrite(MASTER, listOf(sd, hd))

        assertTrue(rewritten.contains("v/480.m3u8"))
        assertTrue(rewritten.contains("v/1080.m3u8"))
        // The tag and its URI are one variant. Dropping the tag and leaving the
        // URI produces a playlist the engine reads as a media playlist.
        assertTrue(!rewritten.contains("v/2160.m3u8"))
        assertTrue(!rewritten.contains("RESOLUTION=3840x2160"))
    }

    @Test
    fun everythingItWasNotTaughtAboutSurvivesUntouched() {
        val rewritten = MasterPlaylistRewriter.rewrite(MASTER, listOf(sd, hd))

        // A rewriter that drops what it does not recognise breaks playback in a
        // way nobody can see in the diff.
        assertTrue(rewritten.contains("#EXT-X-INDEPENDENT-SEGMENTS"))
        assertTrue(rewritten.contains("TYPE=SUBTITLES"))
        assertTrue(rewritten.contains("GROUP-ID=\"aac\""))
        assertTrue(rewritten.startsWith("#EXTM3U"))
    }

    @Test
    fun aTrickPlayTrackFollowsTheRenditionsItPreviews() {
        val kept = MasterPlaylistRewriter.rewrite(MASTER, listOf(sd, hd, uhd))
        assertTrue(kept.contains("#EXT-X-I-FRAME-STREAM-INF"))

        val dropped = MasterPlaylistRewriter.rewrite(MASTER, listOf(sd, uhd))

        // Matched on height, not on the ladder: an I-frame track's bitrate is a
        // thumbnail bitrate and is never in the ladder, so matching it there
        // would drop every one of them and take scrubbing previews with it. Its
        // URI is an attribute rather than the next line, so it drops alone.
        assertTrue(!dropped.contains("i/1080.m3u8"))
        assertTrue(dropped.contains("v/480.m3u8"))
    }

    @Test
    fun keepingEverythingChangesNothing() {
        val rewritten = MasterPlaylistRewriter.rewrite(MASTER, listOf(sd, hd, uhd))

        assertEquals(MASTER, rewritten)
    }

    @Test
    fun keepingNothingLeavesAPlaylistWithNoVariantsRatherThanNoPlaylist() {
        val rewritten = MasterPlaylistRewriter.rewrite(MASTER, emptyList())

        // The engine gets a well-formed master with nothing to play, which it
        // reports as an error naming the manifest. Handing it an empty string
        // would produce a parse failure that names nothing.
        assertTrue(rewritten.startsWith("#EXTM3U"))
        assertTrue(MasterPlaylistRewriter.variants(rewritten).isEmpty())
    }

    @Test
    fun aCommentBetweenTheTagAndItsUriIsNotMistakenForTheUri() {
        val withComment = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=854x480
            # a comment the server left behind
            v/480.m3u8
        """.trimIndent()

        val rewritten = MasterPlaylistRewriter.rewrite(
            withComment,
            listOf(QualityDescriptor(480, 1_280_000)),
        )

        assertTrue(rewritten.contains("v/480.m3u8"))
    }

    @Test
    fun aPlaylistWithNoVariantsIsReturnedUnchanged() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.ts"

        assertEquals(media, MasterPlaylistRewriter.rewrite(media, emptyList()))
    }
}
