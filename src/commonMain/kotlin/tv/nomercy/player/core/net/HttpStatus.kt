// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.net

/**
 * The status codes this kit reacts to, by name.
 *
 * Only the ones something branches on. A table of all sixty would be a table
 * nobody reads, and the point here is that `status == UNAUTHORIZED` says what
 * the branch is FOR while `status == 401` makes a reader remember.
 *
 * Internal: a consumer already has whatever HTTP library it uses, and a second
 * set of these on the kit's public surface is a second thing to keep in step.
 */
internal object HttpStatus {

    const val BAD_REQUEST: Int = 400
    const val UNAUTHORIZED: Int = 401
    const val FORBIDDEN: Int = 403
    const val NOT_FOUND: Int = 404
    const val REQUEST_TIMEOUT: Int = 408
    const val GONE: Int = 410
    const val TOO_MANY_REQUESTS: Int = 429
    const val LAST_CLIENT_ERROR: Int = 499

    const val INTERNAL_SERVER_ERROR: Int = 500
    const val BAD_GATEWAY: Int = 502
    const val SERVICE_UNAVAILABLE: Int = 503
    const val GATEWAY_TIMEOUT: Int = 504

    /** 400..499 — the request was wrong, and repeating it unchanged stays wrong. */
    val CLIENT_ERRORS: IntRange = BAD_REQUEST..LAST_CLIENT_ERROR
}
