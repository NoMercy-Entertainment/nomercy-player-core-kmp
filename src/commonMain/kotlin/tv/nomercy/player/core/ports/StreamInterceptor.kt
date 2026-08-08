// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * A chance to rewrite a response before the engine sees it.
 *
 * This is where a token is refreshed after a 401, a manifest is patched, or a
 * CDN host is swapped. It RETURNS a response rather than mutating one, because
 * the web's does and because a mutated response cannot be replaced by a
 * different one — which is exactly what a retry has to do.
 */
public fun interface StreamInterceptor {
    public suspend fun intercept(url: String, response: FetchResponse): FetchResponse
}
