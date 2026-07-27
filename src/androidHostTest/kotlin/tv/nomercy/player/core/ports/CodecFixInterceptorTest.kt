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
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The repair, over a real HTTP round trip.
//
// A test that called intercept() with a hand-built Response would prove the
// regex and nothing about the interceptor: consuming an OkHttp body twice is
// the mistake this class is most likely to make, and it only shows up when
// something downstream actually reads the stream.
class CodecFixInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        repairs = 0
        client = OkHttpClient.Builder()
            .addInterceptor(CodecFixInterceptor { count -> repairs += count })
            .build()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private var repairs: Int = 0

    private fun fetch(body: String, contentType: String): String {
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", contentType))
        val request: Request = Request.Builder().url(server.url("/master.m3u8")).build()
        return client.newCall(request).execute().use { it.body?.string().orEmpty() }
    }

    @Test
    fun everyRepairIsReportedAndASilentPassIsNot() {
        // The count is how a host learns its library still holds files with bad
        // metadata. Reported per manifest rather than per file, because the same
        // master is fetched repeatedly and a per-fetch count would read as the
        // problem growing.
        fetch(
            """
            #EXT-X-STREAM-INF:CODECS="avc1.F40028"
            #EXT-X-STREAM-INF:CODECS="avc1.f4001f"
            """.trimIndent(),
            PLAYLIST,
        )
        assertEquals(2, repairs, "both malformed rungs should have been counted")

        repairs = 0
        fetch("""#EXT-X-STREAM-INF:CODECS="avc1.64001f"""", PLAYLIST)

        assertEquals(0, repairs, "a manifest with nothing wrong reported a repair")
    }

    @Test
    fun aMalformedProfileByteIsRepaired() {
        val manifest: String = fetch(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.F40028,mp4a.40.2"
            1080p.m3u8
            """.trimIndent(),
            PLAYLIST,
        )

        // 0x64 is High profile. With an F there Media3 rejects the variant as
        // unplayable and the ladder silently loses a rung.
        assertTrue(manifest.contains("avc1.640028"), "the profile byte was not repaired: $manifest")
        assertFalse(manifest.contains("avc1.F40028"), "the malformed string survived: $manifest")
    }

    @Test
    fun theLowercaseSpellingIsRepairedToo() {
        // Codec strings are hex and both cases occur in the wild. A gate that
        // only covered the uppercase one would pass while half the affected
        // library still refused to play.
        val manifest: String = fetch(
            """#EXT-X-STREAM-INF:CODECS="avc1.f4001f"""",
            PLAYLIST,
        )

        assertTrue(manifest.contains("avc1.64001f"), "the lowercase spelling was left alone: $manifest")
    }

    @Test
    fun everythingElseInTheManifestIsUntouched() {
        val source = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.F40028,mp4a.40.2",RESOLUTION=1920x1080
            1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=640x360
            360p.m3u8
        """.trimIndent()

        val manifest: String = fetch(source, PLAYLIST)

        // Only the profile byte moves. A rewrite that reflowed the manifest
        // would be a rewrite of things the server meant, and the bandwidths and
        // URIs are what the ladder is made of.
        assertEquals(source.replace("avc1.F40028", "avc1.640028"), manifest)
    }

    @Test
    fun aManifestWithNothingWrongArrivesIntact() {
        // The no-change path re-attaches a body that has already been read.
        // Getting that wrong hands Media3 an empty manifest, which reads as a
        // server that returned nothing rather than as an interceptor bug.
        val source = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.64001f,mp4a.40.2"
            360p.m3u8
        """.trimIndent()

        assertEquals(source, fetch(source, PLAYLIST))
    }

    @Test
    fun aResponseThatIsNotAPlaylistIsNotSearched() {
        // Those four characters will eventually occur inside a media segment,
        // and rewriting them there corrupts the video.
        val segment = """some bytes that happen to contain CODECS="avc1.F40028" inside them"""

        assertEquals(segment, fetch(segment, "video/mp2t"))
    }
}

private const val PLAYLIST = "application/vnd.apple.mpegurl"
