// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import tv.nomercy.player.core.KIT_VERSION
import tv.nomercy.player.core.errors.NotImplementedError
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.BeforeDispatchResult
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.player.PlayerState
import tv.nomercy.player.core.player.RepeatState
import tv.nomercy.player.core.player.ShuffleState
import tv.nomercy.player.core.plugin.ChromeContribution
import tv.nomercy.player.core.plugin.ChromeSlot
import tv.nomercy.player.core.plugin.ContributionBinding
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginHost
import tv.nomercy.player.core.plugin.PluginRegistry
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.Logger
import tv.nomercy.player.core.ports.MediaBackend
import tv.nomercy.player.core.ports.RealtimeChannel
import tv.nomercy.player.core.ports.RealtimeFactoryOptions
import tv.nomercy.player.core.ports.Storage
import tv.nomercy.player.core.ports.Translator

// The controllers wired together over one context, and the proof that they are
// a player rather than eight pieces that happen to compile.
//
// Composition, not inheritance: this owns its controllers and forwards. A
// per-library player does the same over the same context rather than
// subclassing a base and hoping the overrides line up.
//
// Open, and every member with it. A consumer replacing one behaviour overrides
// one method; a consumer replacing the composition builds their own from the
// same controllers.
@Suppress("TooManyFunctions")
public open class ComposedPlayer(
    backend: MediaBackend? = null,
    private val logger: Logger = SilentLogger,
    private val storage: Storage = InMemoryStorage(),
    private val translator: Translator? = null,
    // Nullable rather than defaulted, so a caller that cannot see Kotlin default
    // arguments can still leave it out. From Swift the default is invisible and
    // the type was non-optional, which meant building a player required
    // constructing a Kotlin CoroutineScope first — a front door no iOS engineer
    // should have to find.
    scope: CoroutineScope? = null,
) : PluginHost {

    public val context: PlayerContext = PlayerContext(backend = backend)

    public val queue: QueueController = QueueController(context)
    public val transport: TransportController = TransportController(context, queue)
    public val volume: VolumeController = VolumeController(context)
    public val time: TimeController = TimeController(context, queue, transport)
    public val state: StateController = StateController(context, queue, time)
    public val plugins: PluginRegistry = PluginRegistry(this, KIT_VERSION, scope ?: CoroutineScope(SupervisorJob()))
    public val lifecycle: LifecycleController = LifecycleController(context, plugins)

    // What the engine reports, turned into what the player says. Without it
    // the controllers drive the engine and nothing listens to it come back.
    public val bridge: BackendBridge = BackendBridge(context)

    init {
        queue.transport = transport
        queue.wireQueue()
        backend?.let { bridge.attach(it) }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public open suspend fun setup(config: PlayerConfig = PlayerConfig()) {
        lifecycle.setup(config)
    }

    public open fun ready(): Deferred<Unit> = lifecycle.ready()

    public open suspend fun dispose(opts: ActionOptions = ActionOptions()) {
        lifecycle.dispose(opts)
    }

    public open fun phase(): PlayerPhase = context.phase

    // ── Transport ────────────────────────────────────────────────────────────

    public open suspend fun play(opts: ActionOptions = ActionOptions()): Unit = transport.play(opts)

    public open suspend fun pause(opts: ActionOptions = ActionOptions()): Unit = transport.pause(opts)

    public open suspend fun stop(opts: ActionOptions = ActionOptions()): Unit = transport.stop(opts)

    public open suspend fun togglePlayback(opts: ActionOptions = ActionOptions()): Unit =
        transport.togglePlayback(opts)

    public open suspend fun next(opts: ActionOptions = ActionOptions()): Unit = transport.next(opts)

    public open suspend fun previous(opts: ActionOptions = ActionOptions()): Unit = transport.previous(opts)

    public open suspend fun restart(opts: ActionOptions = ActionOptions()): Unit = transport.restart(opts)

    // ── Time ─────────────────────────────────────────────────────────────────

    public open fun time(): Double = time.time()

    public open suspend fun time(seconds: Double, opts: ActionOptions = ActionOptions()): Unit =
        time.time(seconds, opts)

    public open fun duration(): Double = time.duration()

    public open fun buffered(): Double = time.buffered()

    public open fun playbackRate(): Double = time.playbackRate()

    public open suspend fun playbackRate(rate: Double, opts: ActionOptions = ActionOptions()): Unit =
        time.playbackRate(rate, opts)

    // ── Volume ───────────────────────────────────────────────────────────────

    public open fun volume(): Int = volume.volume()

    public open suspend fun volume(level: Int, opts: ActionOptions = ActionOptions()): Unit =
        volume.volume(level, opts)

    public open suspend fun mute(opts: ActionOptions = ActionOptions()): Unit = volume.mute(opts)

    public open suspend fun unmute(opts: ActionOptions = ActionOptions()): Unit = volume.unmute(opts)

    public open suspend fun toggleMute(opts: ActionOptions = ActionOptions()): Unit = volume.toggleMute(opts)

    // ── Queue ────────────────────────────────────────────────────────────────

    public open fun queue(): List<PlaylistItem> = queue.queue()

    public open fun queue(items: List<PlaylistItem>): Unit = queue.queue(items)

    public open fun item(): PlaylistItem? = queue.item()

    public open fun index(): Int = queue.index()

    public open suspend fun item(id: String, autoplay: Boolean = false): Unit = queue.item(id, autoplay)

    public open suspend fun playItem(id: String): Unit = queue.playItem(id)

    // ── Modes and state ──────────────────────────────────────────────────────

    public open fun state(): PlayerState = state.state()

    public open val stateFlow: StateFlow<PlayerState> get() = state.stateFlow

    public open fun repeatState(): RepeatState = state.repeatState()

    public open suspend fun repeatState(value: RepeatState, opts: ActionOptions = ActionOptions()): Unit =
        state.repeatState(value, opts)

    public open fun shuffleState(): ShuffleState = state.shuffleState()

    public open suspend fun shuffleState(value: ShuffleState, opts: ActionOptions = ActionOptions()): Unit =
        state.shuffleState(value, opts)

    // ── Plugins ──────────────────────────────────────────────────────────────

    // Returns the player so registrations chain, which is how a host reads:
    // build it, add what it needs, set it up.
    public open suspend fun <O : Any> addPlugin(plugin: Plugin<O>, opts: O? = null): ComposedPlayer {
        plugins.register(plugin, opts)
        return this
    }

    public open fun removePluginById(id: String): Unit = plugins.remove(id)

    public open fun pluginList(): List<Plugin<*>> = plugins.plugins()

    public open fun contributions(slot: ChromeSlot): List<ContributionBinding> = plugins.contributions(slot)

    // ── PluginHost ───────────────────────────────────────────────────────────

    override val rootLogger: Logger get() = logger

    override val rootStorage: Storage get() = storage

    override fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription = context.on(key, fn)

    override fun <T> emit(key: EventKey<T>, data: T): Unit = context.emit(key, data)

    override suspend fun <T> dispatchBefore(
        key: EventKey<BeforeEvent<T>>,
        data: T,
    ): BeforeDispatchResult<T> = context.dispatchBefore(key, data)

    // No transport at this layer, and no honest default for one. A NotImplemented
    // that names the feature is better than a stub returning an empty 200, which
    // a plugin cannot tell from a server that answered.
    override suspend fun fetch(url: String, opts: FetchOptions): FetchResponse =
        throw NotImplementedError("This player was built without an HTTP transport.", "fetch")

    override fun websocket(url: String, opts: RealtimeFactoryOptions): RealtimeChannel =
        throw NotImplementedError("This player was built without a realtime transport.", "websocket")

    // Falls back to the key so an untranslated string is visibly a key rather
    // than an empty label.
    override fun t(namespacedKey: String, vars: Map<String, String>): String =
        translator?.t(namespacedKey, vars) ?: namespacedKey

    override fun report(error: PlayerError) {
        logger.error("${error.code}: ${error.message}")
    }
}

// Says nothing. The default because a library that prints to a host's console
// uninvited is a library people wrap to shut it up.
public object SilentLogger : Logger {
    override fun error(message: String, vararg args: Any?): Unit = Unit
    override fun warn(message: String, vararg args: Any?): Unit = Unit
    override fun info(message: String, vararg args: Any?): Unit = Unit
    override fun debug(message: String, vararg args: Any?): Unit = Unit
    override fun child(scope: String): Logger = this
}

// Enough for a player with no persistence wired: plugins that store preferences
// keep them for the session and lose them on restart, which is a real behaviour
// a host can choose rather than a crash.
public class InMemoryStorage : Storage {
    private val entries: MutableMap<String, String> = mutableMapOf()

    override suspend fun get(key: String): String? = entries[key]

    override suspend fun set(key: String, value: String) {
        entries[key] = value
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }
}
