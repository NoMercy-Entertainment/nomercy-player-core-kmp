// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.ports.FetchOptions

// Turns an item's url into one the backend can actually fetch.
//
// A NoMercy media server signs playback urls, so the url on a queue item is not
// the url that plays. Doing it here rather than in every loading path means a
// per-library loader that forgets about auth does not exist.
//
// Open with a pass-through default, so a consumer whose media needs no
// authorisation writes nothing and a consumer with a different scheme overrides
// one method.
public open class AuthController {
    public open fun transformUrl(url: String): String = url

    // The escape hatch, for a scheme that signs the REQUEST rather than the url.
    //
    // HMAC over the body, AWS Signature v4, a challenge-response: none of them
    // fit transformUrl, because what they sign is the method, the headers and
    // the payload together. transformUrl was the only seam here, so those
    // schemes had nowhere to go and a consumer using one had to wrap the
    // transport instead — which puts the player's own fetches outside whatever
    // they wrapped.
    //
    // Given the url alongside the request, because a signature covers both and
    // the transformed url is the one that will actually be sent.
    //
    // Suspending, because a signature can need a key the host has to fetch or
    // unwrap first. Pass-through by default: a consumer whose media needs no
    // signing writes nothing.
    public open suspend fun signRequest(url: String, request: FetchOptions): FetchOptions = request

    // What the ENGINE must send with every request for this url.
    //
    // transformUrl is not enough on its own for HLS, and the gap is invisible
    // until a real library is played: a manifest signed with a query parameter
    // opens, and the child playlists and segments named inside it are resolved
    // RELATIVE to it — which drops the query. Every request after the first
    // then goes out unauthenticated, so the picture never arrives while the
    // subtitles, which the player fetches itself, are perfect. Measured against
    // a NoMercy server: master 200, child playlist 401.
    //
    // Given the url, because the answer differs per host. A player queues items
    // from a signed-in server and from a public one in the same session, and a
    // token attached to every request would hand the credential to whoever
    // hosts the public file.
    //
    // Empty by default, and merged with whatever the caller passed rather than
    // replacing it.
    public open fun requestHeaders(url: String): Map<String, String> = emptyMap()

    // Get a fresh token, when the one held has stopped working.
    //
    // Suspending and returning nothing: the controller holds whatever it needs
    // and transformUrl reads it afterwards. Handing a token back through here
    // would put it in a caller's hands for no reason, and the reason there is no
    // getter for one at all is the same.
    //
    // Succeeds by default. A consumer whose media needs no authorisation has no
    // token to refresh, and making them implement a method to say so would be a
    // requirement invented by the library.
    public open suspend fun refresh(): Boolean = true
}
