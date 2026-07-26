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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Url resolution, which is a parser and one joining rule.
//
// The joining rule is the part worth guarding. Standard URL resolution drops
// the base's path segment as soon as the relative part starts with a slash, and
// every artwork url this player handles starts with a slash.
class UrlResolutionTest {

    private val imageBase = "https://image.tmdb.org/t/p/w500"

    @Test
    fun aBaseWithAPathKeepsItsPath() {
        // new URL("/xyz.jpg", "https://image.tmdb.org/t/p/w500") gives
        // https://image.tmdb.org/xyz.jpg — the right host and the wrong
        // directory, which 404s.
        val resolved: ResolvedUrl = UrlResolution.resolve("/xyz.jpg", imageBase)

        assertEquals("https://image.tmdb.org/t/p/w500/xyz.jpg", resolved.href)
    }

    @Test
    fun aTrailingSlashOnTheBaseDoesNotDoubleUp() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/xyz.jpg",
            UrlResolution.resolve("/xyz.jpg", "$imageBase/").href,
        )
    }

    @Test
    fun aPathWithoutALeadingSlashStillJoins() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/xyz.jpg",
            UrlResolution.resolve("xyz.jpg", imageBase).href,
        )
    }

    @Test
    fun anAbsoluteUrlIsLeftAlone() {
        val already = "https://cdn.example/video.m3u8"

        assertEquals(already, UrlResolution.resolve(already, imageBase).href)
    }

    @Test
    fun aSchemeWithoutSlashesIsStillAbsolute() {
        // Testing for "://" would call this relative and prepend a base to it.
        val resolved: ResolvedUrl = UrlResolution.resolve("mailto:someone@example.test", imageBase)

        assertEquals("mailto:someone@example.test", resolved.href)
        assertEquals("mailto", resolved.scheme)
        assertFalse(resolved.relative)
    }

    @Test
    fun aColonInsideAPathSegmentIsNotAScheme() {
        // A folder named for an episode is the everyday case. The scheme
        // grammar allows no space before the colon, which is what keeps this
        // relative and joinable.
        val resolved: ResolvedUrl = UrlResolution.resolve("Season 1: Pilot/ep.mkv", "https://server.test/media")

        assertEquals("https://server.test/media/Season 1: Pilot/ep.mkv", resolved.href)
        assertTrue(resolved.relative.not(), "a joined url should not still read as relative")
    }

    @Test
    fun aSchemeShapedStringIsTreatedAsAbsolute() {
        // "chapter:1" satisfies the RFC 3986 scheme grammar, so it is absolute
        // and is left alone. That is correct, and it is also the trap: an
        // identifier that happens to look like a scheme must not be handed to
        // resolveUrl expecting it to be joined to the base.
        assertEquals("chapter:1", UrlResolution.resolve("chapter:1", "https://server.test").href)
    }

    @Test
    fun withNoBaseThePathIsReturnedAndMarkedRelative() {
        val resolved: ResolvedUrl = UrlResolution.resolve("/poster.jpg", null)

        assertEquals("/poster.jpg", resolved.href)
        assertTrue(resolved.relative, "an unresolvable path was reported as absolute")
    }

    @Test
    fun theCallerCanReachThePartsWithoutWritingAParser() {
        val resolved: ResolvedUrl =
            UrlResolution.resolve("https://server.test:7626/media/show.m3u8?token=abc&t=90#chapter-2", null)

        assertEquals("https", resolved.scheme)
        assertEquals("https://server.test:7626", resolved.origin)
        assertEquals("/media/show.m3u8", resolved.path)
        assertEquals("m3u8", resolved.extension)
        assertEquals("?token=abc&t=90", resolved.query)
        assertEquals("#chapter-2", resolved.fragment)
    }

    @Test
    fun theExtensionComesFromThePathNotTheQuery() {
        // A signed url ends in a signature, not a container. Reading the
        // extension off the whole string picks the wrong one and a caller
        // choosing a demuxer by it gets it wrong.
        val resolved: ResolvedUrl = UrlResolution.resolve("https://cdn.test/a/b.m3u8?sig=deadbeef.mp4", null)

        assertEquals("m3u8", resolved.extension)
    }

    @Test
    fun aQueryParameterCanBeReadBack() {
        val resolved: ResolvedUrl = UrlResolution.resolve("https://cdn.test/a?token=abc&t=90", null)

        assertEquals(listOf("token" to "abc", "t" to "90"), resolved.queryParameters())
    }

    @Test
    fun aRepeatedParameterKeepsBothValues() {
        // Dropping one would hide a malformed url rather than let a caller see
        // that the server sent two.
        val resolved: ResolvedUrl = UrlResolution.resolve("https://cdn.test/a?t=10&t=20", null)

        assertEquals(listOf("t" to "10", "t" to "20"), resolved.queryParameters())
    }

    @Test
    fun aDataUrlGetsNoInventedOrigin() {
        // data: and blob: are one opaque string. An origin here would have a
        // caller compare two of them by a host neither has.
        val resolved: ResolvedUrl = UrlResolution.resolve("data:text/vtt;base64,V0VCVlRU", null)

        assertEquals("data", resolved.scheme)
        assertEquals("", resolved.origin)
        assertFalse(resolved.relative)
    }

    @Test
    fun onlyArtworkNeedsToBeAbsolute() {
        assertTrue(UrlCategory.POSTER.needsAbsolute)
        assertTrue(UrlCategory.CAST.needsAbsolute)
        assertFalse(UrlCategory.MEDIA.needsAbsolute, "media was forced absolute; a local file has no origin")
        assertFalse(UrlCategory.SUBTITLE.needsAbsolute)
    }
}
