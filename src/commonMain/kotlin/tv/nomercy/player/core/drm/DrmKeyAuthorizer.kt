// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

// The key fetch an AES-128 stream makes on its own.
//
// The URL is not ours to choose. It arrives in the manifest, on the EXT-X-KEY
// line, and every engine fetches it through its own HTTP layer without asking
// permission. So there is no key client here and there should not be one: the
// only thing missing from that fetch is proof of who is asking.
public data class KeyRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

// Puts the viewer's credentials on the key request.
//
// This is the whole of what AES-128 needs from a client, and it is the scheme
// that is realistic today: no licence server, no device certificate, no vendor
// SDK. A key served only to an authenticated player is real protection against
// the thing self-hosted libraries actually face, which is a link being passed
// around rather than a studio-grade attack.
//
// The headers are asked for at each request rather than captured once, because
// an access token expires and a stream outlives it. A key fetched an hour into a
// film with the token the session started with is a 401 and a stall.
public class DrmKeyAuthorizer(private val credentials: () -> Map<String, String>) {

    // The request's own headers win. A caller that set one deliberately knows
    // something this does not, and silently replacing it would be the kind of
    // failure that only shows up against one server.
    public fun authorize(request: KeyRequest): KeyRequest {
        val auth: Map<String, String> = credentials()
        if (auth.isEmpty()) return request

        return request.copy(headers = auth + request.headers)
    }
}
