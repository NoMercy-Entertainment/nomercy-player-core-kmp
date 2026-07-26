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

private const val CEILING = 50_000_000L

// A master playlist's shape, not a fragment of one: the header, the attribute
// lists with BANDWIDTH among other keys, and the URI on its own line after each
// STREAM-INF. A sanitizer that only worked on a bare attribute would pass a test
// and corrupt a real manifest.
private val PLAYLIST = """
    #EXTM3U
    #EXT-X-VERSION:6
    #EXT-X-STREAM-INF:BANDWIDTH=6000000,AVERAGE-BANDWIDTH=5500000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080
    1080p/index.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=3000000,CODECS="avc1.4d401f",RESOLUTION=1280x720
    720p/index.m3u8
""".trimIndent()

// Three problems in one costume, and each one breaks something different.
class BandwidthSanitizerTest {

    @Test
    fun aPlaylistWithinTheLimitsIsUntouched() {
        val result = BandwidthSanitizer.sanitize(PLAYLIST, CEILING)

        assertEquals(PLAYLIST, result.playlist)
        assertTrue(!result.changed, "a playlist inside every limit was rewritten")
    }

    @Test
    fun aValueAboveIntMaxIsCappedBecauseTheParserCannotReadIt() {
        // Not a quality problem. Media3's parser overflows and the whole
        // manifest fails — not the variant, the manifest, so nothing plays.
        val absurd: String = PLAYLIST.replace("BANDWIDTH=6000000", "BANDWIDTH=99999999999")

        val result = BandwidthSanitizer.sanitize(absurd, ceiling = Long.MAX_VALUE)

        assertTrue(result.playlist.contains("BANDWIDTH=${Int.MAX_VALUE}"))
        assertEquals(listOf(99_999_999_999L to Int.MAX_VALUE.toLong()), result.adjustments.map { it.from to it.to })
    }

    @Test
    fun theParserLimitAppliesEvenWithAHigherCeiling() {
        // The ceiling is about the device and the Int limit is about the parser.
        // A caller who raises the first must not lift the second.
        val absurd: String = PLAYLIST.replace("BANDWIDTH=6000000", "BANDWIDTH=5000000000")

        val result = BandwidthSanitizer.sanitize(absurd, ceiling = 4_000_000_000L)

        assertTrue(result.playlist.contains("BANDWIDTH=${Int.MAX_VALUE}"))
    }

    @Test
    fun aRungAboveWhatTheDeviceCanDecodeIsBroughtDown() {
        // Left alone, adaptation climbs into it and the picture stutters on a
        // connection that was fine.
        val result = BandwidthSanitizer.sanitize(PLAYLIST, ceiling = 4_000_000L)

        assertTrue(result.playlist.contains("BANDWIDTH=4000000"))
        assertEquals(1, result.adjustments.size)
    }

    @Test
    fun aRungBelowTheFloorIsRaised() {
        // A rung that looks free is a rung adaptation parks on, and the viewer
        // watches the worst copy on a good connection.
        val cheap: String = PLAYLIST.replace("BANDWIDTH=3000000", "BANDWIDTH=1000")

        val result = BandwidthSanitizer.sanitize(cheap, CEILING)

        assertTrue(result.playlist.contains("BANDWIDTH=${BandwidthSanitizer.DEFAULT_FLOOR}"))
    }

    @Test
    fun onlyBandwidthMoves() {
        // AVERAGE-BANDWIDTH, RESOLUTION and CODECS share the line. A regex that
        // caught the wrong key would rewrite a codec string into a number.
        val result = BandwidthSanitizer.sanitize(PLAYLIST, ceiling = 4_000_000L)

        assertTrue(result.playlist.contains("AVERAGE-BANDWIDTH=5500000"), "an average was rewritten")
        assertTrue(result.playlist.contains("""CODECS="avc1.640028,mp4a.40.2""""), "a codec list was touched")
        assertTrue(result.playlist.contains("RESOLUTION=1920x1080"), "a resolution was touched")
    }

    @Test
    fun theUriLinesSurvive() {
        // Everything after a STREAM-INF is the variant's address. Losing one is
        // a manifest that parses and plays nothing.
        val result = BandwidthSanitizer.sanitize(PLAYLIST, ceiling = 1_000_000L)

        assertTrue(result.playlist.contains("1080p/index.m3u8"))
        assertTrue(result.playlist.contains("720p/index.m3u8"))
    }

    @Test
    fun aBandwidthTooLongToParseBecomesTheFloorRatherThanTheCeiling() {
        // It is already past every limit, and a rung whose bandwidth cannot be
        // read is not one anything should adapt into.
        val nonsense: String = PLAYLIST.replace("BANDWIDTH=6000000", "BANDWIDTH=999999999999999999999")

        val result = BandwidthSanitizer.sanitize(nonsense, CEILING)

        assertTrue(result.playlist.contains("BANDWIDTH=${BandwidthSanitizer.DEFAULT_FLOOR}"))
    }

    @Test
    fun aPlaylistWithNoBandwidthAtAllIsReturnedAsItArrived() {
        // A media playlist rather than a master one. Every segment list goes
        // through the same transport.
        val media = "#EXTM3U\n#EXTINF:6.0,\nsegment0.ts\n"

        val result = BandwidthSanitizer.sanitize(media, CEILING)

        assertEquals(media, result.playlist)
        assertTrue(!result.changed)
    }
}
