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
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Credentials on the wire, over a real exchange.
//
// The thing worth proving is not that a header can be attached — it is that the
// provider is consulted per request. A token captured once works for an hour and
// then 401s partway through a film, and no test that fetches a single URL can
// tell the two apart.
class AuthHeadersTest {

    private lateinit var server: MockWebServer
    private lateinit var auth: AuthHeaders
    private lateinit var client: OkHttpClient

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        auth = AuthHeaders()
        client = OkHttpClient.Builder().addInterceptor(auth.asInterceptor()).build()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun fetch(path: String = "/master.m3u8"): RecordedRequest {
        server.enqueue(MockResponse().setBody("#EXTM3U"))
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
        return server.takeRequest()
    }

    @Test
    fun aRequestCarriesWhateverTheProviderReturns() {
        auth.provider = { mapOf("Authorization" to "Bearer first") }

        assertEquals("Bearer first", fetch().getHeader("Authorization"))
    }

    @Test
    fun everyRequestAsksAgainRatherThanReusingTheFirstAnswer() {
        // The whole reason this holds a function. A refresh that lands mid-film
        // has to reach the next segment, and a captured value never would.
        var token = "first"
        auth.provider = { mapOf("Authorization" to "Bearer $token") }

        assertEquals("Bearer first", fetch("/segment-1.ts").getHeader("Authorization"))
        token = "second"

        assertEquals("Bearer second", fetch("/segment-2.ts").getHeader("Authorization"))
    }

    @Test
    fun noProviderMeansNoHeaderRatherThanAnEmptyOne() {
        // An empty Authorization is not the same as none. Some servers treat a
        // present-but-blank credential as a failed attempt and answer 401 where
        // they would otherwise have served the file.
        assertNull(fetch().getHeader("Authorization"))
    }

    @Test
    fun aHeaderTheCallerSetAlreadyIsNotOverwritten() {
        // A caller that set an Authorization on a specific request meant it, and
        // a blanket provider overwriting it would be the engine disagreeing with
        // the code that asked for the fetch.
        auth.provider = { mapOf("Authorization" to "Bearer blanket") }
        server.enqueue(MockResponse().setBody("#EXTM3U"))

        val request: Request = Request.Builder()
            .url(server.url("/specific.m3u8"))
            .header("Authorization", "Bearer specific")
            .build()
        client.newCall(request).execute().close()

        assertEquals("Bearer specific", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun anExpiredCredentialIsDistinguishableFromAStreamThatFailed() {
        // Playback stops either way, and the recovery is completely different:
        // one is retried after a refresh, the other is reported to the viewer.
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(500))

        val unauthorized: Boolean = client
            .newCall(Request.Builder().url(server.url("/a")).build())
            .execute().use { it.isAuthFailure() }
        val broken: Boolean = client
            .newCall(Request.Builder().url(server.url("/b")).build())
            .execute().use { it.isAuthFailure() }

        assertEquals(true, unauthorized)
        assertEquals(false, broken)
    }
}
