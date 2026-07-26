// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// How the host makes HTTP requests, for plugins that need to.
//
// A port rather than a client, because an HTTP library in core is an HTTP
// library in every consumer — including the ones that only ever play a local
// file. The host already has one: an app has its own configured client with its
// own auth, retries and certificate pinning, and a player that opened its own
// connections beside it would be a second set of all three.
//
// This is also where the token lives. A plugin never sees one, which is why
// FetchOptions has no auth header for it to get wrong.
public fun interface Fetcher {
    public suspend fun fetch(url: String, opts: FetchOptions): FetchResponse
}
