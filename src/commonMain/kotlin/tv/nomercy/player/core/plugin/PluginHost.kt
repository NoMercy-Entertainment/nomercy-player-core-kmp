// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.BeforeDispatchResult
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.Logger
import tv.nomercy.player.core.ports.RealtimeChannel
import tv.nomercy.player.core.ports.SocketOptions
import tv.nomercy.player.core.ports.Storage

// The slice of the player a plugin can reach, and nothing else.
//
// A concrete player implements this. The plugin runtime depends on this and not
// on the player, which is why the whole runtime is testable against an
// in-memory fake with no backend, no controllers and no ports.
//
// A plugin never holds one of these. It gets the Plugin base's helpers, which
// scope every call to the plugin's own id and clean up after it. This interface
// is the contract between the runtime and whoever hosts it.
public interface PluginHost {
    // Root, unscoped. The Plugin base narrows both to the plugin before a
    // plugin author ever sees them.
    public val rootLogger: Logger
    public val rootStorage: Storage

    public fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription
    public fun <T> emit(key: EventKey<T>, data: T)
    public suspend fun <T> dispatchBefore(key: EventKey<BeforeEvent<T>>, data: T): BeforeDispatchResult<T>

    // The token, the refresh-and-retry and the base URL live behind this, so a
    // plugin cannot get authentication wrong by not knowing about it.
    public suspend fun fetch(url: String, opts: FetchOptions): FetchResponse
    public fun websocket(url: String, opts: SocketOptions): RealtimeChannel

    // Takes an already-namespaced key: the Plugin base builds plugin.<id>.<key>.
    public fun t(namespacedKey: String, vars: Map<String, String>): String

    public fun report(error: PlayerError)
}
