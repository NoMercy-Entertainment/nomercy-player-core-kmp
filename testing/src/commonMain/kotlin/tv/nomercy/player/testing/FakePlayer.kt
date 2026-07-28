// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import tv.nomercy.player.core.KIT_VERSION
import tv.nomercy.player.core.devtools.EventFirehose
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.BeforeDispatchResult
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.events.EventEmitter
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.Player
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.player.PlayerState
import tv.nomercy.player.core.player.PlayerStateHolder
import tv.nomercy.player.core.player.SetupState
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginHost
import tv.nomercy.player.core.plugin.PluginRegistry
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.Logger
import tv.nomercy.player.core.ports.RealtimeChannel
import tv.nomercy.player.core.ports.RealtimeFactoryOptions
import tv.nomercy.player.core.ports.Storage
import kotlin.reflect.KClass

// A player with no engine under it, and a real everything else.
//
// The two halves it implements are the two a consumer's own code talks to:
// [Player] is what a chrome or a view model holds, [PluginHost] is what the
// plugin runtime needs. Both are satisfied by the same object here for the
// same reason the real player satisfies them — a plugin registered on a player
// listens to that player, and splitting them would let a test wire a plugin to
// a bus nothing else is emitting on.
//
// What is real: the event emitter, the plugin registry, the state holder, the
// queue cursor. Registering a plugin runs its `use()`, its listeners land on
// this emitter, and [dispose] tears both down. That is what makes a plugin test
// against this worth running.
//
// What is not: nothing decodes, nothing is fetched, no time passes on its own.
// Transport methods move the state and announce it; they do not wait for an
// engine to confirm, because there is no engine. Pair it with
// [FakeMediaBackend] when the thing under test cares what an engine said.
//
// The ports default to the fakes next to it rather than to throwing, except
// [fetch] and [websocket], which are absent until a test supplies them. An
// empty 200 is indistinguishable from a server that answered, so inventing one
// would turn a missing arrangement into a passing test.
@Suppress("TooManyFunctions")
public class FakePlayer(
    private val items: List<PlaylistItem> = emptyList(),
    // Pass the test scope. A plugin that calls this.launch runs on whatever
    // scope the registry was given, and the default here is a real dispatcher
    // the test scheduler does not drive — so runCurrent() advances nothing, the
    // work races the assertion, and the failure reads as a plugin that did not
    // do anything. `FakePlayer(scope = this)` inside runTest is the fix.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    override val rootStorage: Storage = FakeStorage(),
    override val rootLogger: Logger = FakeLogger(),
    private val fetcher: FakeFetcher? = null,
    private val realtime: ((String, RealtimeFactoryOptions) -> RealtimeChannel)? = null,
) : Player<Unit>, PluginHost, EventFirehose {

    private val bus: EventEmitter<Unit> = EventEmitter()
    private val holder: PlayerStateHolder = PlayerStateHolder()

    // The real registry, not a list. Validation, ordering, `use()`, teardown —
    // a plugin tested against this one is tested against the code that will
    // load it in production.
    public val plugins: PluginRegistry = PluginRegistry(this, KIT_VERSION, scope)

    // Every error the host was handed, in order. A plugin that reports a
    // problem instead of throwing is otherwise invisible to a test.
    public val reportedErrors: MutableList<PlayerError> = mutableListOf()

    // Translations a test seeded, by already-namespaced key.
    public val translations: MutableMap<String, String> = mutableMapOf()

    override fun setup(config: PlayerConfig): Player<Unit> {
        holder.update {
            it.copy(
                setupState = SetupState.READY,
                phase = PlayerPhase.READY,
                volume = config.defaultVolume,
                queueLength = items.size,
            )
        }
        return this
    }

    override fun play(opts: ActionOptions) {
        holder.update { it.copy(phase = PlayerPhase.PLAYING, playState = PlayState.PLAYING) }
    }

    override fun pause(opts: ActionOptions) {
        holder.update { it.copy(phase = PlayerPhase.PAUSED, playState = PlayState.PAUSED) }
    }

    override fun stop(opts: ActionOptions) {
        holder.update { it.copy(phase = PlayerPhase.STOPPED, playState = PlayState.STOPPED) }
    }

    override fun time(): Double = holder.snapshot().time

    override fun time(seconds: Double) {
        holder.update { it.copy(time = seconds) }
    }

    override fun next(): Unit = moveCursor(1)

    override fun previous(): Unit = moveCursor(-1)

    private fun moveCursor(step: Int) {
        if (items.isEmpty()) return
        holder.update { state ->
            val moved: Int = (state.index + step).coerceIn(0, items.size - 1)
            state.copy(index = moved, item = items[moved])
        }
    }

    override fun item(): PlaylistItem? = holder.snapshot().item

    override fun queue(): List<PlaylistItem> = items

    override fun index(): Int = holder.snapshot().index

    override fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription = bus.on(key, fn)

    override fun <T> once(key: EventKey<T>, fn: (T) -> Unit): Subscription = bus.once(key, fn)

    override fun <T> off(key: EventKey<T>, fn: (T) -> Unit): Unit = bus.off(key, fn)

    override fun on(name: String, fn: (Any?) -> Unit): Subscription = bus.on(name, fn)

    // The firehose, so an inspector attached to this sees what one attached to
    // the real player sees.
    override fun onAll(fn: (name: String, data: Any?) -> Unit): Subscription = bus.onAll(fn)

    // The leak baseline. The same diagnostic the real emitter exposes, read off
    // the same emitter the plugins subscribed to.
    public fun listenerCount(): Int = bus.listenerCount()

    // Registration is the real thing: validate, wire, run `use()`.
    //
    // The cast is the interface's, not this fake's. Player.addPlugin takes
    // `Plugin<*>` and an `Any?`, and the registry takes a `Plugin<O>` and an
    // `O?`; nothing on either side can prove the two agree, so the star has to
    // be reopened somewhere. Doing it here is the same trade the real player
    // makes, and a plugin handed options of the wrong shape still fails at
    // registration rather than at first use.
    @Suppress("UNCHECKED_CAST", "NoUncheckedCast")
    override fun <P : Plugin<*>> addPlugin(plugin: P, opts: Any?): Player<Unit> {
        plugins.register(plugin as Plugin<Any>, opts)
        return this
    }

    // KClass.isInstance has already checked it; Kotlin cannot see that the
    // check and the cast are about the same type.
    @Suppress("UNCHECKED_CAST", "NoUncheckedCast")
    override fun <P : Plugin<*>> getPlugin(type: KClass<P>): P? =
        plugins.plugins().firstOrNull { type.isInstance(it) } as? P

    public fun getPluginById(id: String): Plugin<*>? = plugins.getById(id)

    public fun removePluginById(id: String): Unit = plugins.remove(id)

    override fun state(): PlayerState = holder.snapshot()

    override val stateFlow: StateFlow<PlayerState> get() = holder.stateFlow

    // Plugins first, then the state. A plugin's dispose() may still want to
    // read the player, and a disposed-looking player is a different thing to
    // tear down against.
    override fun dispose() {
        plugins.dispose()
        holder.update { it.copy(phase = PlayerPhase.DISPOSED, setupState = SetupState.DISPOSED) }
    }

    // ── PluginHost ───────────────────────────────────────────────────────────

    override fun <T> emit(key: EventKey<T>, data: T): Unit = bus.emit(key, data)

    override suspend fun <T> dispatchBefore(
        key: EventKey<BeforeEvent<T>>,
        data: T,
    ): BeforeDispatchResult<T> = bus.dispatchBefore(key, data)

    override suspend fun fetch(url: String, opts: FetchOptions): FetchResponse =
        fetcher?.fetch(url, opts) ?: noTransport("fetch")

    override fun websocket(url: String, opts: RealtimeFactoryOptions): RealtimeChannel =
        realtime?.invoke(url, opts) ?: noTransport("websocket")

    // Falls back to the key, the way the real player does, so an untranslated
    // string reads as a key rather than as an empty label.
    override fun t(namespacedKey: String, vars: Map<String, String>): String =
        translations[namespacedKey] ?: namespacedKey

    override fun report(error: PlayerError) {
        reportedErrors += error
    }
}
