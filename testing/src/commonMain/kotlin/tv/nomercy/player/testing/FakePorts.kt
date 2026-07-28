// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.testing

import tv.nomercy.player.core.errors.NotImplementedError
import tv.nomercy.player.core.ports.Fetcher
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.Logger
import tv.nomercy.player.core.ports.RealtimeChannel
import tv.nomercy.player.core.ports.RealtimeEvent
import tv.nomercy.player.core.ports.RealtimeState
import tv.nomercy.player.core.ports.Storage

// The host's side of the plugin surface, in memory.
//
// A plugin talks to four things it does not own — storage, a socket, HTTP and
// the log — and every consumer testing one was writing all four again. These
// are the arranged versions: they record what the plugin did and answer with
// what the test decided, which is the only way to assert on a plugin that
// stores a preference or sends a frame.

// Keys and values, nothing else. The namespacing a plugin gets is applied
// above this by the Plugin base, so what lands here is the prefixed key — which
// is exactly what a test asserting two plugins do not collide needs to see.
public class FakeStorage(initial: Map<String, String> = emptyMap()) : Storage {
    public val entries: MutableMap<String, String> = initial.toMutableMap()

    override suspend fun get(key: String): String? = entries[key]

    override suspend fun set(key: String, value: String) {
        entries[key] = value
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }
}

// A socket that goes nowhere and remembers everything.
//
// Text and binary are recorded apart rather than in one list of Any: a test
// asserting a plugin sent a JSON frame should not have to filter out the
// heartbeat bytes to find it.
public class FakeRealtimeChannel(initial: RealtimeState = RealtimeState.OPEN) : RealtimeChannel {
    public val sentText: MutableList<String> = mutableListOf()
    public val sentBytes: MutableList<ByteArray> = mutableListOf()
    public var closeCode: Int? = null
    public var closeReason: String? = null

    private val listeners: MutableMap<RealtimeEvent, MutableList<(Any?) -> Unit>> = mutableMapOf()
    private var state: RealtimeState = initial

    override val readyState: RealtimeState get() = state

    // Starting OPEN is the common case and the default. Starting CONNECTING and
    // calling this is how the other half gets tested: a subject that queues
    // frames until the socket opens does something different before this runs,
    // and a channel that was open from the first line never exercises it.
    public fun open() {
        state = RealtimeState.OPEN
        deliver(RealtimeEvent.OPEN)
    }

    override fun send(data: String) {
        sentText += data
    }

    override fun send(data: ByteArray) {
        sentBytes += data
    }

    override fun close(code: Int?, reason: String?) {
        if (state == RealtimeState.CLOSED) return
        closeCode = code
        closeReason = reason
        state = RealtimeState.CLOSED
        deliver(RealtimeEvent.CLOSE, reason)
    }

    override fun on(event: RealtimeEvent, fn: (Any?) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() } += fn
    }

    override fun off(event: RealtimeEvent, fn: (Any?) -> Unit) {
        listeners[event]?.removeAll { it === fn }
    }

    // The other direction. Without this a plugin under test can only ever be
    // observed talking, and the half that reacts to what the server said is the
    // half worth testing.
    public fun deliver(event: RealtimeEvent, data: Any? = null) {
        listeners[event]?.toList()?.forEach { it(data) }
    }

    // A server that hung up on its own, as opposed to a close this side asked
    // for. A plugin's reconnect path only runs on this one.
    public fun dropped(code: Int = 1006, reason: String = "abnormal closure") {
        state = RealtimeState.CLOSED
        closeCode = code
        closeReason = reason
        deliver(RealtimeEvent.CLOSE, reason)
    }
}

public data class FakeFetchCall(
    val url: String,
    val options: FetchOptions,
)

// HTTP, arranged in advance.
//
// Queued responses are consumed in order, so a test arranging two calls says so
// in the order they happen rather than by matching urls it has to keep in sync
// with the plugin.
//
// An unqueued call throws. It would be easy to answer an empty 200 instead, and
// that is the version that makes a test pass while proving nothing: a plugin
// cannot tell a stubbed empty body from a server that genuinely returned one,
// so a forgotten arrangement would read as a working code path.
public class FakeFetcher : Fetcher {
    public val calls: MutableList<FakeFetchCall> = mutableListOf()

    private val queued: MutableList<FetchResponse> = mutableListOf()

    public fun respondWith(response: FetchResponse): FakeFetcher {
        queued += response
        return this
    }

    public fun respondWith(status: Int = 200, body: String = ""): FakeFetcher =
        respondWith(FetchResponse(status = status, body = body))

    public fun reset() {
        calls.clear()
        queued.clear()
    }

    override suspend fun fetch(url: String, opts: FetchOptions): FetchResponse {
        calls += FakeFetchCall(url, opts)
        if (queued.isEmpty()) {
            throw NotImplementedError(
                "FakeFetcher had no queued response for $url. " +
                    "Arrange one with respondWith(...) before the call.",
                "fetch",
            )
        }
        return queued.removeAt(0)
    }
}

// Log lines, kept rather than printed.
//
// A test that wants to assert a plugin warned about something reads [lines]; a
// test that does not never notices this exists. Printing instead would make
// every suite noisy for the benefit of the few that care.
public class FakeLogger(private val scope: String = "") : Logger {
    public val lines: MutableList<String> = mutableListOf()

    override fun error(message: String, vararg args: Any?): Unit = record("error", message, args)
    override fun warn(message: String, vararg args: Any?): Unit = record("warn", message, args)
    override fun info(message: String, vararg args: Any?): Unit = record("info", message, args)
    override fun debug(message: String, vararg args: Any?): Unit = record("debug", message, args)

    // Children share the parent's list. A plugin's logger is a child of the
    // host's, and a test asserting "the plugin warned" holds the host's.
    override fun child(scope: String): Logger {
        val next = FakeLogger(if (this.scope.isEmpty()) scope else "${this.scope}:$scope")
        children += next
        return next
    }

    public val children: MutableList<FakeLogger> = mutableListOf()

    // Every line this logger and its children recorded, in the order each was
    // written to its own list.
    public fun allLines(): List<String> = lines + children.flatMap { it.allLines() }

    private fun record(level: String, message: String, args: Array<out Any?>) {
        val prefix: String = if (scope.isEmpty()) "" else "[$scope]"
        val suffix: String = if (args.isEmpty()) "" else " " + args.joinToString(" ")
        lines += "$prefix[$level] $message$suffix"
    }
}

// A port the test did not supply. Named rather than silent: a plugin that
// reached for a transport nobody arranged should say which one.
internal fun noTransport(feature: String): Nothing = throw NotImplementedError(
    "This FakePlayer was built without a $feature transport. " +
        "Pass one to the constructor if the code under test needs it.",
    feature,
)
