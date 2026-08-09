// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.ports.LoadOptions
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// What the engine is told to send, not just what url it is given.
//
// transformUrl signs one request. An HLS engine then resolves the child
// playlist and every segment RELATIVE to the manifest, which drops a query
// parameter, so a token carried that way authorises the first request and
// nothing after it. Measured against a real NoMercy server: master 200, child
// playlist 401 — the picture never arrives while the subtitles, which the
// player fetches itself, are perfect. That is a defect with no symptom pointing
// at auth.
class AuthHeadersReachTheEngineTest {

    private class SigningAuth : AuthController() {
        override fun transformUrl(url: String): String = "$url?token=abc"
        override fun requestHeaders(url: String): Map<String, String> =
            if (url.startsWith(PRIVATE)) mapOf("Authorization" to "Bearer abc") else emptyMap()
    }

    @Test
    fun theEngineIsHandedTheHeadersTheControllerAsksFor() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        player.setup(PlayerConfig())
        player.auth(SigningAuth())

        player.load(TestItem(id = "1", url = "$PRIVATE/show/e07.m3u8"))

        assertEquals(
            "Bearer abc",
            backend.loadedOptions.last().headers?.get("Authorization"),
            "the engine was given a signed url and no way to authorise anything it fetches next",
        )
    }

    @Test
    fun aPublicUrlInTheSameQueueCarriesNothing() = runTest {
        // The half that matters more. A queue holds items from a signed-in
        // server and from a public host in the same session, and headers
        // attached to every load would hand the credential to whoever serves
        // the public file.
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        player.setup(PlayerConfig())
        player.auth(SigningAuth())

        player.load(TestItem(id = "2", url = "https://public.example.test/sintel.m3u8"))

        assertTrue(
            backend.loadedOptions.last().headers.isEmpty(),
            "a public host was sent ${backend.loadedOptions.last().headers}",
        )
    }

    @Test
    fun whatTheCallerPassedIsKeptAlongsideIt() = runTest {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        player.setup(PlayerConfig())
        player.auth(SigningAuth())

        player.load(
            TestItem(id = "3", url = "$PRIVATE/show/e07.m3u8"),
            LoadOptions(headers = mapOf("X-Requested-By" to "testbed")),
        )

        assertEquals(
            mapOf("X-Requested-By" to "testbed", "Authorization" to "Bearer abc"),
            backend.loadedOptions.last().headers,
            "merging replaced the caller's headers instead of adding to them",
        )
    }

    private companion object {
        const val PRIVATE = "https://server.example.test"
    }
}
