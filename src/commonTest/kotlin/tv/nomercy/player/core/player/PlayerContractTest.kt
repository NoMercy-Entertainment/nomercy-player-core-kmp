// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

import kotlinx.coroutines.flow.StateFlow
import tv.nomercy.player.core.events.EventEmitter
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data class FakeTrack(
    override val id: String,
    override val url: String = "https://example.test/$id",
    override val title: String? = null,
) : PlaylistItem

private class CountingPlugin : Plugin<Unit>() {
    companion object Manifest : PluginManifest {
        override val id: String = "counting"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest
}

// A player small enough to read in one sitting, built only from what this plan
// ships: the state holder for state, the P03 emitter for events. It exists to
// prove the interface is implementable and coherent — that the transport
// methods, the queue cursor and the observable state agree with each other.
// The real implementation is delegated controllers over a media backend.
private class FakePlayer : Player<Unit> {
    private val holder = PlayerStateHolder()
    private val bus = EventEmitter<Unit>()
    private val items: List<PlaylistItem> = listOf(FakeTrack("a"), FakeTrack("b"))
    private val plugins = mutableListOf<Plugin<*>>()

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

    override fun play(opts: ActionOptions) =
        holder.update { it.copy(phase = PlayerPhase.PLAYING, playState = PlayState.PLAYING) }

    override fun pause(opts: ActionOptions) =
        holder.update { it.copy(phase = PlayerPhase.PAUSED, playState = PlayState.PAUSED) }

    override fun stop(opts: ActionOptions) =
        holder.update { it.copy(phase = PlayerPhase.STOPPED, playState = PlayState.STOPPED) }

    override fun time(): Double = holder.snapshot().time

    override fun time(seconds: Double) = holder.update { it.copy(time = seconds) }

    override fun next() = moveCursor(1)

    override fun previous() = moveCursor(-1)

    private fun moveCursor(step: Int) {
        holder.update { state ->
            val moved = (state.index + step).coerceIn(0, items.size - 1)
            state.copy(index = moved, item = items[moved])
        }
    }

    override fun item(): PlaylistItem? = holder.snapshot().item

    override fun queue(): List<PlaylistItem> = items

    override fun index(): Int = holder.snapshot().index

    override fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription = bus.on(key, fn)

    override fun <T> once(key: EventKey<T>, fn: (T) -> Unit): Subscription = bus.once(key, fn)

    override fun <T> off(key: EventKey<T>, fn: (T) -> Unit) = bus.off(key, fn)

    override fun on(name: String, fn: (Any?) -> Unit): Subscription = bus.on(name, fn)

    override fun <P : Plugin<*>> addPlugin(plugin: P, opts: Any?): Player<Unit> {
        plugins.add(plugin)
        return this
    }

    @Suppress("UNCHECKED_CAST")
    override fun <P : Plugin<*>> getPlugin(type: KClass<P>): P? =
        plugins.firstOrNull { type.isInstance(it) } as? P

    override fun state(): PlayerState = holder.snapshot()

    override val stateFlow: StateFlow<PlayerState> get() = holder.stateFlow

    override fun dispose() =
        holder.update { it.copy(phase = PlayerPhase.DISPOSED, setupState = SetupState.DISPOSED) }

    // Not on the Player surface: emitting is the implementation's job, and a
    // consumer holding a Player must not be able to fake its events. The test
    // needs a way to make one happen.
    fun <T> emitForTest(key: EventKey<T>, data: T) = bus.emit(key, data)
}

class PlayerContractTest {

    @Test
    fun setupReturnsTheSameInstanceSoCallsChain() {
        val player = FakePlayer()

        assertSame(player, player.setup(PlayerConfig()))
        assertEquals(SetupState.READY, player.state().setupState)
    }

    @Test
    fun setupAppliesTheConfigItWasGiven() {
        val player = FakePlayer().setup(PlayerConfig(defaultVolume = 40))

        assertEquals(40, player.state().volume)
    }

    @Test
    fun transportDrivesTheObservableStateAndBothViewsAgree() {
        val player = FakePlayer()
        assertEquals(PlayerPhase.IDLE, player.state().phase)

        player.play()

        assertEquals(PlayerPhase.PLAYING, player.state().phase)
        assertEquals(player.state(), player.stateFlow.value)

        player.pause()
        assertEquals(PlayState.PAUSED, player.stateFlow.value.playState)
    }

    @Test
    fun theQueueCursorReadsBackThroughItemAndIndex() {
        val player = FakePlayer()
        assertEquals(-1, player.index())
        assertNull(player.item())

        player.next()

        assertEquals(0, player.index())
        assertEquals("a", player.item()?.id)
        assertTrue(player.queue().size == 2)
    }

    @Test
    fun theCursorStopsAtBothEndsRatherThanRunningOff() {
        val player = FakePlayer()

        player.previous()
        assertEquals(0, player.index())

        repeat(5) { player.next() }
        assertEquals(1, player.index())
        assertEquals("b", player.item()?.id)
    }

    @Test
    fun aSeekReadsBackThroughTheBareNounGetter() {
        val player = FakePlayer()

        player.time(42.0)

        assertEquals(42.0, player.time())
    }

    @Test
    fun theEventMethodsAreRealRegistrationsNotStubs() {
        val player = FakePlayer()
        val ping = EventKey<Int>("ping")
        var received: Int? = null

        val subscription = player.on(ping) { received = it }
        player.emitForTest(ping, 7)
        assertEquals(7, received)

        subscription.dispose()
        player.emitForTest(ping, 9)
        assertEquals(7, received)
    }

    @Test
    fun onceAndOffOnThePlayerSurfaceBehaveLikeTheEmitters() {
        val player = FakePlayer()
        val ping = EventKey<Int>("ping")
        var onceCalls = 0
        var offCalls = 0

        player.once(ping) { onceCalls++ }
        val handler: (Int) -> Unit = { offCalls++ }
        player.on(ping, handler)

        player.emitForTest(ping, 1)
        player.off(ping, handler)
        player.emitForTest(ping, 2)

        assertEquals(1, onceCalls)
        assertEquals(1, offCalls)
    }

    @Test
    fun theRawNameHatchIsReachableFromThePlayerToo() {
        val player = FakePlayer()
        var received: Any? = null

        player.on("plugin:lyrics:line") { received = it }
        player.emitForTest(EventKey<String>("plugin:lyrics:line"), "hello")

        assertEquals("hello", received)
    }

    @Test
    fun addPluginChainsAndGetPluginFindsItByType() {
        val player = FakePlayer()
        val plugin = CountingPlugin()

        assertSame(player, player.addPlugin(plugin))

        assertSame(plugin, player.getPlugin(CountingPlugin::class))
    }

    @Test
    fun disposeIsVisibleInBothTheSnapshotAndTheFlow() {
        val player = FakePlayer()

        player.dispose()

        assertEquals(PlayerPhase.DISPOSED, player.state().phase)
        assertEquals(SetupState.DISPOSED, player.stateFlow.value.setupState)
    }
}
