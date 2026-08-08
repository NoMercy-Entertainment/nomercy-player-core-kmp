// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.jvm.JvmInline

/**
 * A header value that may have to be fetched rather than read.
 *
 * A token expires while a film plays, so a literal string is only correct for
 * the first request of a two-hour session. [Provided] is called per request,
 * which is what lets a host hand over a token it refreshed five minutes ago
 * without the player holding a stale one.
 */
public sealed interface AuthHeaderValue {

    @JvmInline
    public value class Literal(public val value: String) : AuthHeaderValue

    /** Asked each time the header is needed. */
    public fun interface Provided : AuthHeaderValue {
        public suspend fun value(): String
    }
}

/** What a browser does with cookies on a cross-origin request. */
public enum class RequestCredentials(public val id: String) {
    OMIT("omit"),
    SAME_ORIGIN("same-origin"),
    INCLUDE("include"),
}

/**
 * How the player authenticates everything it fetches.
 *
 * [mediaAuthorization] is separate from [headers] and that separation is
 * load-bearing: a manifest and its segments go out through the engine rather
 * than through our fetch, and on several engines the only thing that can be
 * attached to them is one header value computed per URL.
 *
 * [refreshOnUnauthenticated] with [retryAfterRefresh] is the whole reason a
 * player survives a token expiring mid-film. Without them a 401 on segment four
 * hundred ends playback, and the viewer sees a stall with no message.
 */
public data class AuthConfig(
    /** Bearer token for requests the player itself makes. */
    val bearerToken: AuthHeaderValue? = null,

    /** A value for the media request at this URL, or null to send none. */
    val mediaAuthorization: ((url: String) -> String?)? = null,

    /** Extra headers, each of which may also be fetched per request. */
    val headers: Map<String, AuthHeaderValue> = emptyMap(),

    val credentials: RequestCredentials? = null,

    /** Rewrites a URL before it is requested — a signed CDN path, a proxy. */
    val transformUrl: (suspend (url: String) -> String)? = null,

    /** Called on a 401, to refresh whatever expired. */
    val refreshOnUnauthenticated: (suspend () -> Unit)? = null,

    /** How many times to retry after a refresh before giving up. */
    val retryAfterRefresh: Int? = null,
)
