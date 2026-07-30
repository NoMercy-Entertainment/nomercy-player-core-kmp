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
import kotlin.test.assertTrue

// Moving a playlist without breaking what it points at.
class HlsAbsoluteUrisTest {

    @Test
    fun aVariantUriResolvesAgainstThePlaylistsOwnDirectory() {
        val absolute: String = HlsAbsoluteUris.rewrite(SINTEL_MASTER, SINTEL_MASTER_URL)

        assertTrue(
            absolute.contains(
                "https://raw.githubusercontent.com/NoMercy-Entertainment/nomercy-media/master/" +
                    "Films/Sintel.(2010)/video_1920x818_SDR/video_1920x818_SDR.m3u8",
            ),
            "the variant did not resolve against the master's directory: $absolute",
        )
    }

    @Test
    fun aUriAttributeInsideATagIsResolvedToo() {
        // The audio group's URI is an attribute rather than the line beneath the
        // tag. Missing it leaves a picture with no sound.
        val absolute: String = HlsAbsoluteUris.rewrite(SINTEL_MASTER, SINTEL_MASTER_URL)

        assertTrue(
            absolute.contains("URI=\"https://raw.githubusercontent.com/"),
            "an attribute URI stayed relative: $absolute",
        )
    }

    @Test
    fun theRestOfTheTagIsLeftAlone() {
        // Only the URI value changes. The attributes around it are what the demuxer
        // chooses a rendition on, and a rewrite that reordered or requoted them
        // would change the choice.
        val absolute: String = HlsAbsoluteUris.rewrite(SINTEL_MASTER, SINTEL_MASTER_URL)

        assertTrue(absolute.contains("NAME=\"English aac\""), "an attribute was lost: $absolute")
        assertTrue(absolute.contains("VIDEO-RANGE=PQ"), "an attribute was lost: $absolute")
    }

    @Test
    fun anAlreadyAbsoluteUriIsUnchanged() {
        val manifest: String = listOf(
            "#EXTM3U",
            "#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=64x36",
            "https://cdn.example.com/a/b.m3u8",
        ).joinToString("\n")

        assertEquals(manifest, HlsAbsoluteUris.rewrite(manifest, SINTEL_MASTER_URL))
    }

    @Test
    fun aTagWithNoUriIsUnchanged() {
        val manifest: String = "#EXTM3U\n#EXT-X-VERSION:6\n"

        assertEquals(manifest.trimEnd(), HlsAbsoluteUris.rewrite(manifest, SINTEL_MASTER_URL).trimEnd())
    }

    @Test
    fun aBaseUrlNothingCanParseLeavesTheManifestAlone() {
        // Better an unrewritten playlist than one whose URIs were resolved against
        // a guess: the first fails in a way libVLC reports, the second silently
        // fetches the wrong thing.
        assertEquals(SINTEL_MASTER, HlsAbsoluteUris.rewrite(SINTEL_MASTER, "not a url at all"))
    }
}
