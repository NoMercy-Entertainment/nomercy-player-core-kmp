// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.testing.FakeFetcher
import tv.nomercy.player.testing.FakeMediaBackend
import kotlin.test.Test
import kotlin.test.assertEquals

// A scheme that signs the REQUEST rather than the url had nowhere to go:
// transformUrl was the only seam, and an HMAC over the body or a sigv4 header
// covers the method, the headers and the payload together.
class SignRequestTest {

    private class HmacAuth : AuthController() {
        override fun transformUrl(url: String): String = "$url?token=abc"

        override suspend fun signRequest(url: String, request: FetchOptions): FetchOptions =
            request.copy(headers = request.headers + ("X-Signature" to "signed:$url"))
    }

    private suspend fun player(fetcher: FakeFetcher): ComposedPlayer =
        ComposedPlayer(backend = FakeMediaBackend(), fetcher = fetcher).apply {
            auth(HmacAuth())
        }

    @Test
    fun aPluginsFetchCarriesTheSignatureWithoutTheAuthorKnowingAboutIt() = runTest {
        val fetcher = FakeFetcher().respondWith(status = 200, body = "ok")
        val subject = player(fetcher)

        subject.fetch("https://media.example.test/subs.vtt", FetchOptions())

        assertEquals(1, fetcher.calls.size)
        assertEquals(
            "signed:https://media.example.test/subs.vtt?token=abc",
            fetcher.calls.first().options.headers["X-Signature"],
        )
    }

    @Test
    fun theSignatureCoversTheTransformedUrlRatherThanTheOriginal() = runTest {
        // A signature over the url the caller passed would not match the url
        // that was actually sent, which is a rejection at the server that reads
        // as a broken token.
        val fetcher = FakeFetcher().respondWith(status = 200, body = "ok")
        val subject = player(fetcher)

        subject.fetch("https://media.example.test/subs.vtt", FetchOptions())

        assertEquals("https://media.example.test/subs.vtt?token=abc", fetcher.calls.first().url)
    }

    @Test
    fun aPlayerWithNoAuthSendsTheRequestUntouched() = runTest {
        val fetcher = FakeFetcher().respondWith(status = 200, body = "ok")
        val subject = ComposedPlayer(backend = FakeMediaBackend(), fetcher = fetcher)

        subject.fetch("https://media.example.test/subs.vtt", FetchOptions())

        assertEquals("https://media.example.test/subs.vtt", fetcher.calls.first().url)
        assertEquals(emptyMap(), fetcher.calls.first().options.headers)
    }
}
