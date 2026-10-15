// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import okhttp3.OkHttpClient
import kotlin.concurrent.thread

/**
 * The one OkHttpClient every client in the library is derived from.
 *
 * A client built from scratch creates its own SSL context and connection pool.
 * On an SM-A137F that cost 428 ms (the engine's data source) and 333 ms (the
 * media session's artwork source) on the main thread of every playback start.
 * Clients made by [derive] share the base's SSL setup, pool and dispatcher, so
 * only the first build pays, and [warm] moves that one off the main thread.
 */
public object SharedHttpClient {
    private val base: OkHttpClient by lazy { OkHttpClient() }

    /** A client with the base's SSL setup and pool, plus the settings in [configure]. */
    public fun derive(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient =
        base.newBuilder().apply(configure).build()

    /** Builds the base on a background thread, so the first playback does not wait for it. */
    public fun warm() {
        thread(name = "nm-http-warm", isDaemon = true) { base }
    }
}
