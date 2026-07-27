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
import okhttp3.Response

// The headers a manifest or segment request needs, asked for again every time.
//
// A NoMercy stream sits behind Keycloak, and a token outlives neither a long
// film nor a paused one. Capturing the header once at construction gives a
// player that works for an hour and then 401s partway through — on the segment
// after the token expired, which presents as the server dropping the stream
// rather than as a credential that ran out.
//
// So this holds a function rather than a value and calls it per request. The
// host owns refresh; the engine only ever asks what the headers are now.
//
// Mutable rather than constructor-injected because the engine is built before a
// session exists. A player constructed at app start has no token yet, and one
// that demanded it up front could not be created until the viewer had signed in.
public class AuthHeaders {

    // Volatile because the engine reads it from Media3's loader threads while
    // the host writes it from wherever a token refresh completes.
    @Volatile
    public var provider: () -> Map<String, String> = { emptyMap() }

    internal fun asInterceptor(): Interceptor = Interceptor { chain ->
        chain.proceed(authorized(chain.request()))
    }

    // Existing headers win. A caller that set an Authorization on a specific
    // request meant it, and a blanket provider overwriting it would be the
    // engine quietly disagreeing with the code that asked for the fetch.
    private fun authorized(request: Request): Request {
        val headers: Map<String, String> = provider()
        if (headers.isEmpty()) return request

        val builder: Request.Builder = request.newBuilder()
        for ((name, value) in headers) {
            if (request.header(name) == null) builder.header(name, value)
        }
        return builder.build()
    }
}

// Whether a response says the credential rather than the stream was the problem.
//
// Worth naming because the two look identical from a player's point of view —
// playback stops either way — and the recovery is completely different. One is
// retried after a refresh; the other is reported to the viewer.
internal fun Response.isAuthFailure(): Boolean = code == UNAUTHORIZED || code == FORBIDDEN

private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
