// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.canvas

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.testing.FakePlayer
import tv.nomercy.player.testing.testPlugin
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasPluginTest {

    // Ten ticks of 8 ms against a 60 fps cap is 80 ms of wall clock, which is
    // four capped frames plus the first. A cap that anchored to the tick that
    // crossed it would accept three and drift slower the longer it ran.
    @Test
    fun theFpsCapKeepsTheAverageRateRatherThanDrifting() = runTest {
        val plugin = CanvasPlugin<StringBuilder>()
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.mount(width = 640.0, height = 360.0)
            repeat(TICKS) { plugin.tick(TICK_MS) }

            assertEquals(5L, plugin.ticks.value)
        }
    }

    @Test
    fun renderersRunInTheOrderTheyWereAdded() = runTest {
        val plugin = CanvasPlugin<StringBuilder>()
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.addRenderer { surface, _, _ -> surface.append("first ") }
            plugin.addRenderer { surface, _, _ -> surface.append("second") }

            val surface = StringBuilder()
            plugin.draw(surface)

            assertEquals("first second", surface.toString())
        }
    }

    // One shared surface hosts several independent renderers. A consumer's
    // broken visualiser must not take the library's own down with it, and a
    // renderer that throws every frame would otherwise fill the log forever.
    @Test
    fun aRendererThatThrowsIsReportedOnceAndDropped() = runTest {
        val plugin = CanvasPlugin<StringBuilder>()
        testPlugin(plugin, FakePlayer(scope = this)) { player, _ ->

            plugin.addRenderer { _, _, _ -> error("this renderer is broken") }
            plugin.addRenderer { surface, _, _ -> surface.append("survivor") }

            val first = StringBuilder()
            plugin.draw(first)
            val second = StringBuilder()
            plugin.draw(second)

            assertEquals("survivor", first.toString())
            assertEquals("survivor", second.toString())
            assertEquals(1, player.reportedErrors.count { it.code == CANVAS_RENDERER_FAILED })
        }
    }

    // A layout pass that reports the size it already had is not a resize, and a
    // consumer rebuilding its buffers on the event would rebuild them on every
    // frame of an animation that never changed size.
    @Test
    fun reportingTheSameSizeAgainAnnouncesNothing() = runTest {
        val plugin = CanvasPlugin<StringBuilder>()
        testPlugin(plugin, FakePlayer(scope = this)) { player, _ ->

            var resizes = 0
            val watching: Subscription = player.on(CanvasEvents.ResizedOnPlayer) { resizes += 1 }

            plugin.mount(width = 640.0, height = 360.0)
            plugin.size(width = 640.0, height = 360.0)
            plugin.size(width = 800.0, height = 450.0)
            watching.dispose()

            assertEquals(1, resizes)
            assertEquals(CanvasSize(800.0, 450.0), plugin.bounds.value)
        }
    }

    // A surface that goes away and comes back is a new run of the loop, so the
    // first frame after it must be accepted rather than measured against a gap
    // that spans the time there was no surface at all — which is one frozen
    // frame on every rotation, for as long as the view was gone.
    @Test
    fun aRemountedSurfaceGetsItsFirstFrameStraightAway() = runTest {
        val plugin = CanvasPlugin<StringBuilder>()
        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->

            plugin.mount(width = 640.0, height = 360.0)
            plugin.tick(TICK_MS)
            plugin.tick(TICK_MS)
            assertEquals(1L, plugin.ticks.value, "the second tick is inside the cap and is rejected")

            plugin.unmount()
            plugin.mount(width = 640.0, height = 360.0)
            plugin.tick(TICK_MS)

            assertEquals(2L, plugin.ticks.value)
            assertEquals(CanvasSize(640.0, 360.0), plugin.bounds.value)
        }
    }
}

private const val TICKS = 10

private const val TICK_MS = 8L
