// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.CrossfadeTransitionStrategy
import tv.nomercy.player.core.ports.PreloadAsset
import tv.nomercy.player.core.ports.PreloadContext
import tv.nomercy.player.core.ports.PreloadStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem

// A strategy that names something, so the asset path is exercised rather than
// the empty-list shortcut every shipped strategy takes.
private class NamingPreloadStrategy(private val leadSeconds: Double = 10.0) : PreloadStrategy {
    var cancellations: Int = 0
        private set

    override fun shouldPreload(context: PreloadContext): Boolean {
        if (context.nextItem == null || context.duration <= 0.0) return false
        return context.currentTime >= context.duration - leadSeconds
    }

    override fun assetsToPreload(item: PlaylistItem): List<PreloadAsset> =
        listOf(PreloadAsset(url = item.url, category = "media"))

    override fun cancel() {
        cancellations += 1
    }
}

// shouldPreload and shouldTransition had zero callers. Both strategies were
// constructed, configured from PlayerConfig, exposed by getters — and never
// asked, so every track and episode change was a hard cut into a cold load.
class PreloadOrchestrationTest {

    private fun FakeMediaBackend.tick(position: Double, total: Double = 100.0) {
        currentTimeValue = position
        durationValue = total
        fire(CanonicalBackendEvent.TIME_UPDATE)
    }

    private fun TestScope.rig(config: PlayerConfig = PlayerConfig()): Pair<ComposedPlayer, FakeMediaBackend> {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend, scope = backgroundScope)
        player.queue(listOf(TestItem("a"), TestItem("b")))
        backgroundScope.launch { player.setup(config) }
        runCurrent()
        return player to backend
    }

    @Test
    fun theNextItemIsAskedForBeforeTheCurrentOneEnds() = runTest {
        val (player, backend) = rig()
        val started: MutableList<String> = mutableListOf()
        player.on(CoreEvents.PreloadStart) { started += it.item.id }

        backend.tick(position = 95.0)
        runCurrent()

        assertEquals(listOf("b"), started)
    }

    @Test
    fun nothingIsAskedForWhileTheItemIsStillPlayingThroughTheMiddle() = runTest {
        val (player, backend) = rig()
        var starts = 0
        player.on(CoreEvents.PreloadStart) { starts += 1 }

        backend.tick(position = 40.0)
        runCurrent()

        assertEquals(0, starts)
    }

    @Test
    fun oncePerItemRatherThanOncePerTick() = runTest {
        // A preloader that started again on every tick would fetch the next
        // item forty times.
        val (player, backend) = rig()
        var starts = 0
        player.on(CoreEvents.PreloadStart) { starts += 1 }

        repeat(10) { step -> backend.tick(position = 95.0 + step * 0.1) }
        runCurrent()

        assertEquals(1, starts)
    }

    @Test
    fun aStrategyThatNamesNothingStillCompletes() = runTest {
        // Core's own DefaultPreloadStrategy names no assets, because what is
        // worth prefetching differs between a manifest and an album cover. A
        // consumer waiting on preloadComplete should not have to special-case it.
        val (player, backend) = rig()
        val completed: MutableList<String> = mutableListOf()
        player.on(CoreEvents.PreloadComplete) { completed += it.item.id }

        backend.tick(position = 95.0)
        runCurrent()

        assertEquals(listOf("b"), completed)
    }

    @Test
    fun everyNamedAssetIsReportedAsItLands() = runTest {
        val (player, backend) = rig()
        player.setPreloadStrategy(NamingPreloadStrategy())
        val progress: MutableList<Double> = mutableListOf()
        player.on(CoreEvents.PreloadProgress) { progress += it.loaded }

        backend.tick(position = 95.0)
        runCurrent()

        assertEquals(listOf(1.0), progress)
    }

    @Test
    fun movingTheCursorCancelsWhatTheOldItemHadInFlight() = runTest {
        val (player, backend) = rig()
        val strategy = NamingPreloadStrategy()
        player.setPreloadStrategy(strategy)

        backend.tick(position = 95.0)
        runCurrent()
        player.next()
        runCurrent()

        assertTrue(strategy.cancellations > 0, "a fetch that lands after a skip describes yesterday's item")
    }

    @Test
    fun theCycleArmsAgainForTheNextItem() = runTest {
        val (player, backend) = rig()
        val started: MutableList<String> = mutableListOf()
        player.on(CoreEvents.PreloadStart) { started += it.item.id }
        player.queue(listOf(TestItem("a"), TestItem("b"), TestItem("c")))

        backend.tick(position = 95.0)
        runCurrent()
        player.next()
        runCurrent()
        backend.tick(position = 95.0)
        runCurrent()

        assertEquals(listOf("b", "c"), started)
    }

    @Test
    fun crossfadeIsOffUntilTheHostAsksForIt() = runTest {
        val (player, backend) = rig(PlayerConfig(crossfadeEnabled = false))
        player.setTransitionStrategy(CrossfadeTransitionStrategy())
        var starts = 0
        player.on(CoreEvents.TransitionStart) { starts += 1 }

        backend.tick(position = 98.0)
        runCurrent()

        assertEquals(0, starts, "two video streams overlapping is a dissolve nobody asked for")
    }

    @Test
    fun theTransitionRunsWhenTheStrategySaysItIsTime() = runTest {
        val (player, backend) = rig(PlayerConfig(crossfadeEnabled = true))
        player.setTransitionStrategy(CrossfadeTransitionStrategy())
        val started: MutableList<Pair<String, String>> = mutableListOf()
        player.on(CoreEvents.TransitionStart) { started += it.outgoing.id to it.incoming.id }

        backend.tick(position = 98.0)
        runCurrent()

        assertEquals(listOf("a" to "b"), started)
    }

    @Test
    fun theFadeRidesToTheEndAndSaysSo() = runTest {
        val (player, backend) = rig(PlayerConfig(crossfadeEnabled = true))
        player.setTransitionStrategy(CrossfadeTransitionStrategy())
        val fractions: MutableList<Double> = mutableListOf()
        var completed = 0
        player.on(CoreEvents.TransitionProgress) { fractions += it.fraction }
        player.on(CoreEvents.TransitionComplete) { completed += 1 }

        // The window is crossfadeLead + crossfadeTail wide and starts at
        // duration - lead, so it closes at duration + tail — the reference's
        // arithmetic, where the tail is covered by the engine running past the
        // reported duration rather than by the next item's clock.
        backend.tick(position = 98.0)
        runCurrent()
        backend.tick(position = 103.0)
        advanceTimeBy(100)
        runCurrent()

        assertTrue(fractions.size > 1, "a crossfade nobody can watch is a hard cut with extra steps")
        assertEquals(1, completed)
        assertEquals(1.0, fractions.last())
    }

    @Test
    fun disposingStopsTheOrchestrationEntirely() = runTest {
        val (player, backend) = rig()
        var starts = 0
        player.on(CoreEvents.PreloadStart) { starts += 1 }

        backgroundScope.launch { player.dispose() }
        runCurrent()
        backend.tick(position = 95.0)
        runCurrent()

        assertEquals(0, starts)
    }
}
