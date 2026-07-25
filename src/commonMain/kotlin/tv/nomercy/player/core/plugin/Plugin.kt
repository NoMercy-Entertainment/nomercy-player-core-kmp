// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.errors.Severity
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

private const val CLOSED = "closed"
private const val CLOSING = "closing"

// What a plugin author writes against.
//
//     class LyricsPlugin : Plugin<LyricsOptions>() {
//         companion object Manifest : PluginManifest {
//             override val id = "lyrics"
//             override val version = "1.0.0"
//         }
//         override val manifest = Manifest
//
//         override fun use() {
//             on(CoreEvents.Time) { paint(it.time) }
//         }
//     }
//
// Everything below is scoped to this plugin and cleaned up when it goes away:
// listeners unsubscribe, timers cancel, sockets close, storage keys and log
// lines carry the plugin's id. That is the point of the base class — an author
// who uses only these cannot leak, and cannot collide with another plugin.
//
// Reaching around it to a raw event bus, timer or HTTP client works and is not
// blocked, because guidance is not a wall. It also gives up every guarantee
// above; the detekt-player pack points that out at the call site.
//
// The class is wide because the surface is: this is the plugin API, and a
// narrower base would just mean authors importing what it left out.
@Suppress("TooManyFunctions")
public abstract class Plugin<O : Any> {

    public abstract val manifest: PluginManifest

    // The author's default options. A consumer's options at registration win.
    public open val options: O? get() = null

    public val id: String get() = manifest.id

    private var wiring: Wiring<O>? = null

    private val wired: Wiring<O>
        get() = wiring ?: throw PlayerError(
            code = PluginErrorCodes.STATE_UNINITIALIZED,
            scope = ErrorScope.plugin(id),
            message = "Plugin \"$id\" was used before it was registered. Construct it, then register it.",
        )

    // Prefixed [nmplayer][<id>], so a log line says which plugin was talking.
    protected val logger: Logger get() = wired.logger

    // Keys prefixed nmplayer-<id>-, so two plugins that both store "enabled"
    // do not overwrite each other.
    protected val storage: Storage get() = wired.storage

    protected val resolvedOptions: O? get() = wired.options ?: options

    // Called by the registry after construction and before use(). Not for
    // plugin authors: it is internal, and calling it twice replaces the wiring
    // of a plugin that may already be running.
    internal fun initialize(host: PluginHost, opts: O?, lifecycle: LifecycleRegistry) {
        wiring = Wiring(
            host = host,
            lifecycle = lifecycle,
            logger = host.rootLogger.child(id),
            storage = NamespacedStorage(host.rootStorage, "nmplayer-$id-"),
            options = opts,
        )
    }

    // Wire up here rather than in a constructor: at construction time the
    // plugin has no host yet.
    public open fun use() {}

    // Anything the helpers below cannot clean up on their own. The registry
    // calls this, then disposes the lifecycle.
    public open fun dispose() {}

    // Subscribes and unsubscribes itself when the plugin goes away. The
    // returned handle is for unsubscribing sooner; ignoring it is the normal
    // case and leaks nothing.
    //
    // A bare key listens to a core event; another plugin's key is already
    // namespaced, so listening across plugins is the same call.
    protected fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription {
        val subscription: Subscription = wired.host.on(key, fn)
        wired.lifecycle.addCleanup { subscription.dispose() }
        return subscription
    }

    // Emits under this plugin's namespace. A key that is already namespaced
    // goes out verbatim, so re-emitting your own registry key is not
    // double-prefixed.
    protected fun <T> emit(key: EventKey<T>, data: T) {
        wired.host.emit(EventKey(scopedName(key.name)), data)
    }

    // The same cancellable seam core uses for its own before* events: a
    // listener can reshape the payload, refuse the action, or hold it open.
    protected suspend fun <T> dispatchBefore(
        key: EventKey<BeforeEvent<T>>,
        data: T,
    ): BeforeDispatchResult<T> = wired.host.dispatchBefore(EventKey(scopedName(key.name)), data)

    // Surface a problem without stopping. Use for anything the viewer might
    // want to know about but that did not break playback.
    protected fun report(
        code: String,
        message: String,
        severity: Severity = Severity.WARNING,
        cause: Throwable? = null,
        context: Map<String, Any?> = emptyMap(),
    ) {
        wired.host.report(buildError(code, message, severity, cause, context))
    }

    // Surface a problem and stop. The error is reported through the host before
    // it is thrown, so a host that catches nothing still sees it.
    protected fun throwError(
        code: String,
        message: String,
        severity: Severity = Severity.ERROR,
        cause: Throwable? = null,
        context: Map<String, Any?> = emptyMap(),
    ): Nothing {
        val error: PlayerError = buildError(code, message, severity, cause, context)
        wired.host.report(error)
        throw error
    }

    protected suspend fun fetch(url: String, opts: FetchOptions = FetchOptions()): FetchResponse =
        wired.host.fetch(url, opts)

    // Closes itself when the plugin goes away, unless it is already closing.
    protected fun websocket(url: String, opts: SocketOptions = SocketOptions()): RealtimeChannel {
        val channel: RealtimeChannel = wired.host.websocket(url, opts)
        wired.lifecycle.addCleanup {
            if (channel.readyState != CLOSED && channel.readyState != CLOSING) {
                channel.close()
            }
        }
        return channel
    }

    // Looks up plugin.<id>.<key>, so a plugin ships translations under its own
    // namespace and cannot shadow a core string.
    protected fun t(key: String, vars: Map<String, String> = emptyMap()): String =
        wired.host.t("plugin.$id.$key", vars)

    protected fun timeout(delayMs: Long, fn: () -> Unit): Job = wired.lifecycle.timeout(delayMs, fn)

    protected fun interval(periodMs: Long, fn: () -> Unit): Job = wired.lifecycle.interval(periodMs, fn)

    protected fun frame(fn: (deltaMs: Long) -> Unit): Job = wired.lifecycle.frame(fn)

    protected fun <T> listen(flow: Flow<T>, fn: (T) -> Unit): Job = wired.lifecycle.listen(flow, fn)

    private fun buildError(
        code: String,
        message: String,
        severity: Severity,
        cause: Throwable? = null,
        context: Map<String, Any?> = emptyMap(),
    ): PlayerError = PlayerError(
        code = code,
        scope = ErrorScope.plugin(id),
        severity = severity,
        message = message,
        cause = cause,
        context = context,
    )

    private fun scopedName(name: String): String =
        if (name.startsWith("plugin:")) name else "plugin:$id:$name"

    // One nullable field instead of five lateinits: a plugin used before
    // registration then fails with a message naming the plugin, rather than an
    // UninitializedPropertyAccessException naming a field.
    private class Wiring<O : Any>(
        val host: PluginHost,
        val lifecycle: LifecycleRegistry,
        val logger: Logger,
        val storage: Storage,
        val options: O?,
    )
}

// Every key a plugin writes goes through here, which is what makes two plugins
// storing "enabled" safe.
internal class NamespacedStorage(private val backend: Storage, private val prefix: String) : Storage {
    override fun get(key: String): String? = backend.get(prefix + key)
    override fun set(key: String, value: String) = backend.set(prefix + key, value)
    override fun remove(key: String) = backend.remove(prefix + key)
}
