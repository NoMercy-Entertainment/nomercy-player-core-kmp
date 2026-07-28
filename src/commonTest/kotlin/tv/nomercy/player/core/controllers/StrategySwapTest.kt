// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.ports.CrossfadeTransitionStrategy
import tv.nomercy.player.core.ports.DefaultPreloadStrategy
import tv.nomercy.player.core.ports.GaplessTransitionStrategy
import tv.nomercy.player.core.ports.PreloadAsset
import tv.nomercy.player.core.ports.PreloadContext
import tv.nomercy.player.core.ports.PreloadStrategy
import tv.nomercy.player.core.ports.TransitionBackend
import tv.nomercy.player.core.ports.TransitionContext
import tv.nomercy.player.core.ports.TransitionStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend

private class RecordingPreload : PreloadStrategy {
    var cancelled: Int = 0
        private set

    override fun shouldPreload(context: PreloadContext): Boolean = false
    override fun assetsToPreload(item: PlaylistItem): List<PreloadAsset> = emptyList()
    override fun cancel() {
        cancelled += 1
    }
}

private class RecordingTransition : TransitionStrategy {
    val cancelReasons: MutableList<String> = mutableListOf()

    override fun shouldTransition(context: PreloadContext): Boolean = false
    override fun tick(context: TransitionContext, backend: TransitionBackend?): Unit = Unit
    override fun start(outgoing: PlaylistItem, incoming: PlaylistItem, backend: TransitionBackend?): Unit = Unit
    override fun complete(from: PlaylistItem, to: PlaylistItem): Unit = Unit
    override fun cancel(reason: String) {
        cancelReasons += reason
    }
}

// Swapping how the player fetches ahead and how it moves between items.
class StrategySwapTest {

    private fun player(): ComposedPlayer = ComposedPlayer(backend = FakeMediaBackend())

    @Test
    fun corePreloadsOnALeadAndCutsAtTheEnd() {
        // The two defaults, and the reason the video default is a cut: two
        // video streams overlapping is a dissolve nobody asked for.
        val player: ComposedPlayer = player()

        assertTrue(player.preloadStrategy() is DefaultPreloadStrategy)
        assertTrue(player.transitionStrategy() is GaplessTransitionStrategy)
    }

    @Test
    fun aSwappedPreloadStrategyIsTheOneThatGetsAsked() {
        val player: ComposedPlayer = player()
        val replacement = RecordingPreload()

        player.setPreloadStrategy(replacement)

        assertEquals(replacement, player.preloadStrategy())
    }

    @Test
    fun theOutgoingPreloadStrategyIsCancelled() {
        // It may have a fetch in flight against an item this player is about to
        // stop caring about.
        val player: ComposedPlayer = player()
        val outgoing = RecordingPreload()
        player.setPreloadStrategy(outgoing)

        player.setPreloadStrategy(RecordingPreload())

        assertEquals(1, outgoing.cancelled)
    }

    @Test
    fun swappingMidTransitionCancelsTheOneUnderWay() {
        // Leaving it running would strand the outgoing item at whatever gain the
        // old strategy last set — audible as a track that never comes back up.
        val player: ComposedPlayer = player()
        val outgoing = RecordingTransition()
        player.setTransitionStrategy(outgoing)

        player.setTransitionStrategy(CrossfadeTransitionStrategy())

        assertEquals(listOf("strategy-replaced"), outgoing.cancelReasons)
    }

    @Test
    fun aCancelledTransitionIsAnnouncedSoAChromeStopsDrawingIt() {
        val player: ComposedPlayer = player()
        val reasons: MutableList<String> = mutableListOf()
        player.on(CoreEvents.TransitionCancelled) { reasons += it.reason }

        player.setTransitionStrategy(RecordingTransition())

        assertEquals(listOf("strategy-replaced"), reasons)
    }

    @Test
    fun thePreloadAndTransitionStrategiesAreIndependent() {
        // The reason they are two ports: a consumer wanting a gapless join has
        // no opinion about which assets get warmed, and one prefetching artwork
        // has none about crossfades.
        val player: ComposedPlayer = player()
        val preload = RecordingPreload()

        player.setPreloadStrategy(preload)
        player.setTransitionStrategy(CrossfadeTransitionStrategy())

        assertEquals(preload, player.preloadStrategy(), "swapping the transition replaced the preload")
        assertEquals(0, preload.cancelled, "swapping the transition cancelled the preload")
    }
}
