// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The WebSocket vocabulary, because every implementation already speaks it.
public enum class RealtimeState(public val wire: String) {
    CONNECTING("connecting"),
    OPEN("open"),
    CLOSING("closing"),
    CLOSED("closed"),
}

public enum class RealtimeEvent(public val wire: String) {
    OPEN("open"),
    MESSAGE("message"),
    CLOSE("close"),
    ERROR("error"),
}

// A long-lived connection, whatever protocol carries it.
//
// NoMercy Connect runs over SignalR and the app supplies that implementation;
// a plain WebSocket default ships with the transport work. Neither is baked in
// here, because a consumer's realtime layer is theirs.
public interface RealtimeChannel {
    public fun send(data: String)
    public fun send(data: ByteArray)

    // Both optional: the close code and reason are protocol detail most callers
    // have nothing to say about.
    public fun close(code: Int? = null, reason: String? = null)

    public fun on(event: RealtimeEvent, fn: (Any?) -> Unit)
    public fun off(event: RealtimeEvent, fn: (Any?) -> Unit)

    public val readyState: RealtimeState
}

public data class RealtimeFactoryOptions(
    val protocols: List<String> = emptyList(),
    val reconnect: Boolean = false,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
)

public typealias RealtimeFactory = (url: String, opts: RealtimeFactoryOptions) -> RealtimeChannel
