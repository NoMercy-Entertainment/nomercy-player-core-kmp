// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.plugin.ChromeContribution
import tv.nomercy.player.core.plugin.ChromeSlot
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend

private class NotePlugin(id: String = "note") : Plugin<Unit>() {
    private val contribution = object : ChromeContribution {
        override val slot: ChromeSlot = ChromeSlot.Overlay
    }

    override val manifest: PluginManifest = object : PluginManifest {
        override val id: String = id
        override val version: String = "1.0.0"
        override val contributions: List<ChromeContribution> = listOf(contribution)
    }

    var plays: Int = 0
    var used: Boolean = false

    override fun use() {
        used = true
        on(CoreEvents.Play) { plays += 1 }
    }
}

class ComposedPlayerTest {

    private fun player(): ComposedPlayer = ComposedPlayer(FakeMediaBackend())

    private val ComposedPlayer.engine: FakeMediaBackend get() = context.fakeBackend()

    @Test
    fun theWholeFlowRunsThroughOneComposedSurface() = runTest {
        val subject = player()

        subject.setup()
        subject.ready().await()
        assertEquals(PlayerPhase.READY, subject.phase())

        subject.queue(items("a", "b", "c"))
        subject.playItem("a")
        assertEquals("a", subject.item()?.id)
        assertEquals(1, subject.engine.playCount)

        subject.next()
        assertEquals("b", subject.item()?.id)
        assertEquals("https://example.test/b", subject.engine.loadedUrls.last())

        subject.pause()
        assertEquals(PlayState.PAUSED, subject.state().playState)

        subject.dispose()
        assertEquals(PlayerPhase.DISPOSED, subject.phase())
    }

    @Test
    fun theFlowIsTheOneReactiveSourceForEverySurface() = runTest {
        val subject = player()
        subject.setup()
        subject.ready().await()
        subject.queue(items("a"))

        subject.play()

        assertEquals(PlayState.PLAYING, subject.stateFlow.value.playState)
        assertEquals(100, subject.stateFlow.value.volume)
        assertEquals("a", subject.stateFlow.value.item?.id)
    }

    @Test
    fun aChangeMadeThroughAnyControllerShowsUpInTheOneSnapshot() = runTest {
        val subject = player()
        subject.setup()
        subject.ready().await()
        subject.queue(items("a", "b"))

        subject.volume(30)
        subject.playbackRate(1.5)
        subject.play()
        subject.next()

        val snapshot = subject.state()
        assertEquals(30, snapshot.volume)
        assertEquals(1.5, snapshot.playbackRate)
        assertEquals("b", snapshot.item?.id)
        assertEquals(2, snapshot.queueLength)
        assertEquals(snapshot, subject.stateFlow.value)
    }

    @Test
    fun setupAppliesTheConfiguredVolumeToTheWholePlayer() = runTest {
        val subject = player()

        subject.setup(PlayerConfig(defaultVolume = 25))

        assertEquals(25, subject.volume())
        assertEquals(25, subject.stateFlow.value.volume)
    }

    @Test
    fun aPluginRegistersAgainstTheSameBusTheControllersUse() = runTest {
        val subject = player()
        val plugin = NotePlugin()
        subject.setup()
        subject.ready().await()
        subject.queue(items("a"))

        assertSame(subject, subject.addPlugin(plugin))
        assertTrue(plugin.used)

        subject.play()

        // The plugin heard a transport event emitted by a controller: one bus,
        // not a plugin bus bolted alongside.
        assertEquals(1, plugin.plays)
        assertEquals(1, subject.pluginList().size)
    }

    @Test
    fun aPluginsChromeContributionIsVisibleToASurface() = runTest {
        val subject = player()
        subject.setup()

        subject.addPlugin(NotePlugin())

        val overlay = subject.contributions(ChromeSlot.Overlay)
        assertEquals(listOf("note"), overlay.map { it.pluginId })
        assertTrue(subject.contributions(ChromeSlot.TopBar).isEmpty())
    }

    @Test
    fun removingAPluginStopsItHearingTheBus() = runTest {
        val subject = player()
        val plugin = NotePlugin("gone")
        subject.setup()
        subject.ready().await()
        subject.queue(items("a"))
        subject.addPlugin(plugin)

        subject.play()
        subject.removePluginById("gone")
        subject.play()

        assertEquals(1, plugin.plays)
        assertTrue(subject.pluginList().isEmpty())
    }

    @Test
    fun disposingThePlayerDisposesItsPlugins() = runTest {
        val subject = player()
        val plugin = NotePlugin()
        subject.setup()
        subject.ready().await()
        subject.queue(items("a"))
        subject.addPlugin(plugin)

        subject.dispose()
        subject.context.emit(CoreEvents.Play, tv.nomercy.player.core.events.PlaySource())

        assertEquals(0, plugin.plays)
    }

    @Test
    fun aPlayerBuiltWithoutATransportSaysSoRatherThanPretending() = runTest {
        val subject = player()

        val failure = assertFailsWith<PlayerError> {
            subject.fetch("https://example.test", tv.nomercy.player.core.ports.FetchOptions())
        }

        // A stub returning an empty 200 is indistinguishable from a server that
        // answered, which is worse than not being there.
        assertEquals("core:not-implemented/fetch", failure.code)
    }

    @Test
    fun anUntranslatedKeyComesBackAsTheKey() {
        val subject = player()

        assertEquals("plugin.note.play", subject.t("plugin.note.play", emptyMap()))
    }
}
