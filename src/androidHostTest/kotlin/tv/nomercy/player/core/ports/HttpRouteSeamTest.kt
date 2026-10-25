// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A host rewrites the base in the network layer, so every media request must pass its interceptor.
class HttpRouteSeamTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<String>()
    private val rewrite = Interceptor { chain ->
        seen += chain.request().url.host
        val live = chain.request().url.newBuilder().host(server.hostName).port(server.port).scheme("http").build()
        chain.proceed(chain.request().newBuilder().url(live).build())
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        SharedHttpClient.removeInterceptor(rewrite)
        server.shutdown()
    }

    @Test
    fun theEngineClientSendsItsRequestsThroughAnInstalledInterceptor() {
        SharedHttpClient.addInterceptor(rewrite)
        server.enqueue(MockResponse().setBody("segment"))
        val client = engineHttpClient(AuthHeaders()) { }

        client.newCall(Request.Builder().url("http://placeholder.example/seg.ts").build()).execute().close()

        assertEquals(listOf("placeholder.example"), seen)
        assertEquals("/seg.ts", server.takeRequest().path)
    }

    @Test
    fun aClientDerivedAfterRemovalSkipsTheInterceptor() {
        SharedHttpClient.addInterceptor(rewrite)
        SharedHttpClient.removeInterceptor(rewrite)

        val client = SharedHttpClient.derive { }

        assertTrue(client.interceptors.none { it === rewrite })
    }
}
