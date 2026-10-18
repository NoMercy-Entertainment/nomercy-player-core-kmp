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

// Headers a load carries sit on top of the host's provider and leave it asking.
class AuthHeadersCarriedTest {

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

    private fun fetch(): RecordedRequest {
        server.enqueue(MockResponse().setBody("#EXTM3U"))
        client.newCall(Request.Builder().url(server.url("/segment.ts")).build()).execute().close()
        return server.takeRequest()
    }

    @Test
    fun aRefreshedHostTokenReachesTheNextRequestAfterALoadCarriedHeaders() {
        var token = "first"
        auth.provider = { mapOf("Authorization" to "Bearer $token") }
        auth.carried = mapOf("X-Session" to "abc")

        assertEquals("Bearer first", fetch().getHeader("Authorization"))
        token = "second"

        assertEquals("Bearer second", fetch().getHeader("Authorization"))
    }

    @Test
    fun theCarriedHeadersAreSentToo() {
        auth.provider = { mapOf("Authorization" to "Bearer first") }
        auth.carried = mapOf("X-Session" to "abc")

        assertEquals("abc", fetch().getHeader("X-Session"))
    }

    @Test
    fun aCarriedHeaderWinsOverTheProviderForTheSameName() {
        auth.provider = { mapOf("Authorization" to "Bearer host") }
        auth.carried = mapOf("Authorization" to "Bearer load")

        assertEquals("Bearer load", fetch().getHeader("Authorization"))
    }
}
