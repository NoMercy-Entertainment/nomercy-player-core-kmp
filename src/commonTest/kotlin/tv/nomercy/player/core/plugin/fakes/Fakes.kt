// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin.fakes

import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.BeforeDispatchResult
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.events.EventEmitter
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugin.PluginHost
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.Logger
import tv.nomercy.player.core.ports.RealtimeChannel
import tv.nomercy.player.core.ports.RealtimeFactoryOptions
import tv.nomercy.player.core.ports.Storage
import tv.nomercy.player.testing.FakeRealtimeChannel

// Records every line with the prefix it was written under, so a test can assert
// that a plugin's logger really is scoped rather than that a logger exists.
// Children share the parent's sink: one list, ordered, whoever wrote it.
class RecordingLogger(private val prefix: String, private val sink: MutableList<String>) : Logger {
    constructor(prefix: String) : this(prefix, mutableListOf())

    val lines: MutableList<String> get() = sink

    override fun error(message: String, vararg args: Any?) { sink.add("$prefix ERROR $message") }
    override fun warn(message: String, vararg args: Any?) { sink.add("$prefix WARN $message") }
    override fun info(message: String, vararg args: Any?) { sink.add("$prefix INFO $message") }
    override fun debug(message: String, vararg args: Any?) { sink.add("$prefix DEBUG $message") }
    override fun child(scope: String): Logger = RecordingLogger("$prefix[$scope]", sink)
}

// Exposes the exact keys written, which is the only way to prove namespacing
// actually happened rather than being claimed.
class RecordingStorage : Storage {
    val map: MutableMap<String, String> = mutableMapOf()
    override suspend fun get(key: String): String? = map[key]
    override suspend fun set(key: String, value: String) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
}

// A host built on the real EventEmitter rather than a stub map, so the plugin
// runtime is exercised against the same dispatch, before-seam and disposal
// semantics a real player gives it. The ports are stubs because there is
// nothing to prove about them here.
class FakePluginHost(
    override val rootLogger: Logger = RecordingLogger("[nmplayer]"),
    override val rootStorage: Storage = RecordingStorage(),
) : PluginHost {
    val bus: EventEmitter<Unit> = EventEmitter()
    val emitted: MutableList<Pair<String, Any?>> = mutableListOf()
    val reported: MutableList<PlayerError> = mutableListOf()
    val sockets: MutableList<FakeRealtimeChannel> = mutableListOf()
    val fetched: MutableList<Pair<String, FetchOptions>> = mutableListOf()
    val translated: MutableList<Pair<String, Map<String, String>>> = mutableListOf()
    var nextFetch: FetchResponse = FetchResponse(200)

    override fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription = bus.on(key, fn)

    override fun <T> emit(key: EventKey<T>, data: T) {
        emitted.add(key.name to data)
        bus.emit(key, data)
    }

    override suspend fun <T> dispatchBefore(
        key: EventKey<BeforeEvent<T>>,
        data: T,
    ): BeforeDispatchResult<T> = bus.dispatchBefore(key, data)

    override suspend fun fetch(url: String, opts: FetchOptions): FetchResponse {
        fetched.add(url to opts)
        return nextFetch
    }

    override fun websocket(url: String, opts: RealtimeFactoryOptions): RealtimeChannel =
        FakeRealtimeChannel().also { sockets.add(it) }

    override fun t(namespacedKey: String, vars: Map<String, String>): String {
        translated.add(namespacedKey to vars)
        return namespacedKey
    }

    override fun report(error: PlayerError) { reported.add(error) }
}
