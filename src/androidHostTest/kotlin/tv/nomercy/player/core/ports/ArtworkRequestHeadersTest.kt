// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The artwork fetch sends the token to the media host only, never to a third-party cover host. */
class ArtworkRequestHeadersTest {
    @After
    fun reset() {
        clearArtworkHeaderProviders()
    }

    @Test
    fun urlAwareProviderSendsTheHeaderOnlyToItsHost() {
        PlatformEnvironment.installArtworkRequestHeaders { url ->
            if (url.startsWith("https://media.example.test/")) mapOf("Authorization" to "Bearer test") else emptyMap()
        }

        assertEquals(emptyMap<String, String>(), artworkHeadersFor("https://images.example.org/x.jpg"))
        assertEquals(
            mapOf("Authorization" to "Bearer test"),
            artworkHeadersFor("https://media.example.test/x.jpg"),
        )
    }

    @Test
    fun theArtworkInterceptorSendsTheTokenOnlyWhereTheProviderAllows() {
        PlatformEnvironment.installArtworkRequestHeaders { url ->
            if (url.startsWith("https://media.example.test/")) mapOf("Authorization" to "Bearer test") else emptyMap()
        }
        val sent = mutableListOf<okhttp3.Request>()
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(artworkHeaderInterceptor)
            .addInterceptor { chain ->
                sent += chain.request()
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(okhttp3.ResponseBody.create(null, ByteArray(0)))
                    .build()
            }
            .build()

        client.newCall(okhttp3.Request.Builder().url("https://images.example.org/x.jpg").build()).execute().close()
        client.newCall(okhttp3.Request.Builder().url("https://media.example.test/x.jpg").build()).execute().close()

        assertEquals(null, sent[0].header("Authorization"))
        assertEquals("Bearer test", sent[1].header("Authorization"))
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyProviderStillAppliesToEveryHost() {
        PlatformEnvironment.installArtworkAuthHeaders { mapOf("Authorization" to "Bearer old") }

        assertEquals(
            mapOf("Authorization" to "Bearer old"),
            artworkHeadersFor("https://images.example.org/x.jpg"),
        )
    }
}
