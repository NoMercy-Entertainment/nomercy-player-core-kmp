// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.NotImplementedError
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val OK = 200

// The host's HTTP, and what happens when there isn't any.
class FetcherTest {

    @Test
    fun aPlayerWithoutATransportSaysSoByName() = runTest {
        val player = ComposedPlayer(backend = FakeMediaBackend())

        val failure = assertFailsWith<NotImplementedError> { player.fetch("https://example.test/x", FetchOptions()) }

        // Named, because "not implemented" on its own sends whoever reads it
        // looking through the player for a bug that is a missing wiring.
        assertTrue(failure.code.endsWith("/fetch"), "the failure did not name fetch: ${failure.code}")
    }

    @Test
    fun aPlayerWithATransportUsesIt() = runTest {
        var asked: String? = null
        val player = ComposedPlayer(
            backend = FakeMediaBackend(),
            fetcher = { url, _ ->
                asked = url
                FetchResponse(status = OK, body = "hello")
            },
        )

        val response: FetchResponse = player.fetch("https://example.test/subtitle.ass", FetchOptions())

        assertEquals("https://example.test/subtitle.ass", asked)
        assertEquals("hello", response.body)
    }

    @Test
    fun bytesSurviveTheTrip() = runTest {
        // The reason bytes exist at all: a font is not text, and a transport
        // that only carried text would send every one through base64.
        val font = ByteArray(FONT_SIZE) { (it % Byte.MAX_VALUE).toByte() }
        val player = ComposedPlayer(
            backend = FakeMediaBackend(),
            fetcher = { _, _ -> FetchResponse(status = OK, bytes = font) },
        )

        val response: FetchResponse = player.fetch("https://example.test/font.ttf", FetchOptions())

        assertTrue(font.contentEquals(response.bytes), "the bytes changed on the way through")
    }

    @Test
    fun twoResponsesCarryingTheSameBytesAreEqual() {
        // A data class would have compared the arrays by identity, which makes
        // every assertion about a fetched font pass whatever the bytes were.
        val left = FetchResponse(status = OK, bytes = byteArrayOf(1, 2, 3))
        val right = FetchResponse(status = OK, bytes = byteArrayOf(1, 2, 3))

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }

    @Test
    fun responsesCarryingDifferentBytesAreNot() {
        val left = FetchResponse(status = OK, bytes = byteArrayOf(1, 2, 3))
        val right = FetchResponse(status = OK, bytes = byteArrayOf(3, 2, 1))

        assertTrue(left != right)
    }

    private companion object {
        const val FONT_SIZE = 512
    }
}
