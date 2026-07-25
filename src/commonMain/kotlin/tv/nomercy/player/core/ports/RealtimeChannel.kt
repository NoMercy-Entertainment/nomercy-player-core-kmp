// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

public data class SocketOptions(val protocols: List<String> = emptyList())

// A long-lived connection that reconnects itself. Sockets and SignalR both fit;
// the host decides which, and a plugin only ever sends and listens.
public interface RealtimeChannel {
    // One of "connecting", "open", "closing", "closed" — the WebSocket vocabulary,
    // because every platform's implementation already speaks it.
    public val readyState: String
    public fun send(data: String)
    public fun close()
}
