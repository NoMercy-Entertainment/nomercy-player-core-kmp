// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CEILING = 4_000_000L

private val PLAYLIST = """
    #EXTM3U
    #EXT-X-STREAM-INF:BANDWIDTH=6000000,AVERAGE-BANDWIDTH=5500000,RESOLUTION=1920x1080
    1080p/index.m3u8
""".trimIndent()

// The interceptor over a real HTTP exchange.
//
// The rule has its own tests and they are the ones that matter. What this adds
// is the plumbing the rule cannot check: that a one-shot body read once is
// still readable by the player, that a segment is not dragged into memory to be
// searched for an attribute it cannot contain, and that a playlist served as
// text/plain — which happens — is still sanitized.
class BandwidthSanitizingInterceptorTest {

    private val server = MockWebServer()

    @AfterTest
    fun shutDown() {
        server.shutdown()
    }

    private fun clientFor(adjustments: MutableList<BandwidthSanitizer.Adjustment>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(BandwidthSanitizingInterceptor(CEILING) { adjustments += it })
            .build()

    private fun fetch(client: OkHttpClient, path: String): String {
        val request = Request.Builder().url(server.url(path)).build()
        return client.newCall(request).execute().use { it.body?.string().orEmpty() }
    }

    @Test
    fun aPlaylistArrivesSanitizedAndStillReadable() {
        // The failure this catches is the classic interceptor mistake: reading a
        // one-shot body and handing the original response back, which gives the
        // player an empty manifest.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody(PLAYLIST),
        )
        val adjustments: MutableList<BandwidthSanitizer.Adjustment> = mutableListOf()

        val body: String = fetch(clientFor(adjustments), "/master.m3u8")

        assertTrue(body.contains("BANDWIDTH=4000000"), "the playlist was not sanitized: $body")
        assertTrue(body.contains("1080p/index.m3u8"), "the variant URI did not survive")
        assertEquals(1, adjustments.size)
    }

    @Test
    fun aPlaylistServedAsPlainTextIsStillSanitized() {
        // Servers that serve .m3u8 as text/plain are common enough that trusting
        // the header alone means the sanitizer silently never runs.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(PLAYLIST))

        val body: String = fetch(clientFor(mutableListOf()), "/master.m3u8")

        assertTrue(body.contains("BANDWIDTH=4000000"), "a text/plain playlist was skipped")
    }

    @Test
    fun aSegmentIsNotReadIntoMemory() {
        // Searching a segment for an attribute it cannot contain would turn
        // every video into a buffer the size of the file.
        val segment = "not a playlist, just bytes"
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp2t").setBody(segment))

        val body: String = fetch(clientFor(mutableListOf()), "/1080p/segment0.ts")

        assertEquals(segment, body)
    }

    @Test
    fun aPlaylistInsideTheLimitsComesBackByteForByte() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody(PLAYLIST),
        )
        val adjustments: MutableList<BandwidthSanitizer.Adjustment> = mutableListOf()
        val generous = OkHttpClient.Builder()
            .addInterceptor(BandwidthSanitizingInterceptor(ceiling = Long.MAX_VALUE) { adjustments += it })
            .build()

        val body: String = fetch(generous, "/master.m3u8")

        assertEquals(PLAYLIST, body)
        assertTrue(adjustments.isEmpty())
    }
}
