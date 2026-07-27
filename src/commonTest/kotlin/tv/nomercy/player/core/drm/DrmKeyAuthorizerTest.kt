// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

import kotlin.test.Test
import kotlin.test.assertEquals

// Proving who is asking for the decryption key.
//
// Small on purpose. The engine already knows the URL and already makes the
// request; the only thing a client adds is the credentials, and getting that
// wrong is a film that plays for whoever has the link.
class DrmKeyAuthorizerTest {

    private val keyRequest = KeyRequest(url = "https://server.example/key/abc")

    @Test
    fun theViewersCredentialsRideAlongWithTheKeyFetch() {
        val authorizer = DrmKeyAuthorizer { mapOf("Authorization" to "Bearer token") }

        val authorized: KeyRequest = authorizer.authorize(keyRequest)

        assertEquals("Bearer token", authorized.headers["Authorization"])
    }

    @Test
    fun theUrlIsLeftExactlyAsTheManifestGaveIt() {
        // It came from the stream, not from us. A client that rewrote it would
        // work against one server's layout and break the moment the encoder
        // named its keys differently.
        val authorizer = DrmKeyAuthorizer { mapOf("Authorization" to "Bearer token") }

        val authorized: KeyRequest = authorizer.authorize(keyRequest)

        assertEquals("https://server.example/key/abc", authorized.url)
    }

    @Test
    fun credentialsAreReadAtEachRequestRatherThanCapturedOnce() {
        // A token expires and a film outlives it. A key fetched an hour in with
        // the token the session started with is a 401 and a stall in the middle
        // of the third act.
        var token = "first"
        val authorizer = DrmKeyAuthorizer { mapOf("Authorization" to "Bearer $token") }
        authorizer.authorize(keyRequest)

        token = "refreshed"
        val later: KeyRequest = authorizer.authorize(keyRequest)

        assertEquals("Bearer refreshed", later.headers["Authorization"])
    }

    @Test
    fun aRequestThatAlreadySetAHeaderKeepsIt() {
        // The caller knew something this does not. Silently replacing it is the
        // kind of failure that only shows up against one server.
        val authorizer = DrmKeyAuthorizer { mapOf("Authorization" to "Bearer generic") }
        val explicit = KeyRequest(url = keyRequest.url, headers = mapOf("Authorization" to "Bearer specific"))

        val authorized: KeyRequest = authorizer.authorize(explicit)

        assertEquals("Bearer specific", authorized.headers["Authorization"])
    }

    @Test
    fun withNoCredentialsTheRequestGoesOutUnchanged() {
        // An unauthenticated server, or a session that has not signed in yet.
        // Adding an empty header is a request some servers reject outright.
        val authorizer = DrmKeyAuthorizer { emptyMap() }

        val authorized: KeyRequest = authorizer.authorize(keyRequest)

        assertEquals(emptyMap(), authorized.headers)
    }

    @Test
    fun otherHeadersTheEngineNeedsAreNotDiscarded() {
        // The engine puts its own on the request. Replacing the map instead of
        // adding to it drops a range header, which on a key is harmless and on
        // the next thing to reuse this is not.
        val authorizer = DrmKeyAuthorizer { mapOf("Authorization" to "Bearer token") }
        val withAgent = KeyRequest(url = keyRequest.url, headers = mapOf("User-Agent" to "NoMercy"))

        val authorized: KeyRequest = authorizer.authorize(withAgent)

        assertEquals("NoMercy", authorized.headers["User-Agent"])
        assertEquals("Bearer token", authorized.headers["Authorization"])
    }
}
