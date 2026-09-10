// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// applyTunneling's sourceIsHls check, over a real Regex rather than a mocked
// URL — the same shape the web trio's own HLS_EXT_RE test takes. A wrong
// answer here means real HLS-over-TS content getting tunneling wrongly
// enabled, which TunnelingRule documents as a real breakage mode.
class HlsExtRegexTest {

    @Test
    fun aPlainManifestUrlMatches() {
        assertTrue(HLS_EXT_RE.containsMatchIn("https://server.test/master.m3u8"))
    }

    @Test
    fun aQueryStringAfterTheExtensionStillMatches() {
        assertTrue(HLS_EXT_RE.containsMatchIn("https://server.test/master.m3u8?token=abc"))
    }

    @Test
    fun aFragmentAnchorAfterTheExtensionStillMatches() {
        // The exact case the old substringBefore('?').endsWith(".m3u8") check
        // missed — a resume-position anchor with no query string in front of it.
        assertTrue(HLS_EXT_RE.containsMatchIn("https://server.test/master.m3u8#t=30"))
    }

    @Test
    fun aFragmentAnchorAfterAQueryStringStillMatches() {
        assertTrue(HLS_EXT_RE.containsMatchIn("https://server.test/master.m3u8?token=abc#t=30"))
    }

    @Test
    fun caseIsIgnored() {
        assertTrue(HLS_EXT_RE.containsMatchIn("https://server.test/MASTER.M3U8"))
    }

    @Test
    fun aNonHlsUrlDoesNotMatch() {
        assertFalse(HLS_EXT_RE.containsMatchIn("https://server.test/movie.mp4"))
    }

    @Test
    fun m3u8AppearingOnlyInAQueryValueDoesNotMatch() {
        // The extension has to end the path (or be followed by ? / # / end of
        // string) — a coincidental substring elsewhere in the URL is not a
        // manifest.
        assertFalse(HLS_EXT_RE.containsMatchIn("https://server.test/redirect?to=master.m3u8x"))
    }
}
