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
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.player.PlayerPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LifecycleControllerTest {

    private fun rig(): Pair<PlayerContext, LifecycleController> {
        val ctx = newContext()
        return ctx to LifecycleController(ctx)
    }

    @Test
    fun setupWalksToReadyAndAnnouncesItInOrder() = runTest {
        val (ctx, lifecycle) = rig()
        val seen = mutableListOf<String>()
        ctx.on(CoreEvents.BeforeSetup) { seen += "beforeSetup" }
        ctx.on(CoreEvents.Ready) { seen += "ready" }

        lifecycle.setup()
        lifecycle.ready().await()

        assertEquals(PlayerPhase.READY, ctx.phase)
        assertEquals(listOf("beforeSetup", "ready"), seen)
    }

    @Test
    fun setupAppliesTheConfigItWasGiven() = runTest {
        val (ctx, lifecycle) = rig()

        lifecycle.setup(PlayerConfig(defaultVolume = 35))

        assertEquals(35, ctx.internalVolume)
        assertEquals(35, lifecycle.config()?.defaultVolume)
    }

    @Test
    fun settingUpTwiceIsItsOwnDiagnosis() = runTest {
        val (_, lifecycle) = rig()
        lifecycle.setup()

        val failure = assertFailsWith<PlayerError> { lifecycle.setup() }

        assertEquals("core:lifecycle/already-setup", failure.code)
    }

    @Test
    fun settingUpAfterDisposeIsADifferentDiagnosis() = runTest {
        val (_, lifecycle) = rig()
        lifecycle.setup()
        lifecycle.dispose()

        val failure = assertFailsWith<PlayerError> { lifecycle.setup() }

        // Not "already set up": using a disposed player is a different bug in
        // the calling code and deserves a different code.
        assertEquals("core:player/disposed", failure.code)
    }

    @Test
    fun awaitingReadyResolvesForEveryCallerFromOneSignal() = runTest {
        val (_, lifecycle) = rig()

        lifecycle.setup()

        lifecycle.ready().await()
        lifecycle.ready().await()
        assertTrue(lifecycle.ready().isCompleted)
    }

    @Test
    fun disposeReachesTheDisposedPhaseAndStopsTheEngine() = runTest {
        val (ctx, lifecycle) = rig()
        lifecycle.setup()
        lifecycle.ready().await()
        var disposed = 0
        ctx.on(CoreEvents.Dispose) { disposed += 1 }

        lifecycle.dispose()

        assertEquals(1, disposed)
        assertEquals(PlayerPhase.DISPOSED, ctx.phase)
        assertEquals(1, ctx.fakeBackend().stopCount)
    }

    @Test
    fun aRefusedDisposeLeavesThePlayerExactlyAsItWas() = runTest {
        val (ctx, lifecycle) = rig()
        lifecycle.setup()
        lifecycle.ready().await()
        ctx.on(CoreEvents.BeforeDispose) { it.preventDefault() }
        var prevented = 0
        ctx.on(CoreEvents.DisposePrevented) { prevented += 1 }

        lifecycle.dispose()

        assertEquals(1, prevented)
        assertEquals(PlayerPhase.READY, ctx.phase)
        // No half-torn-down state: the engine was never touched.
        assertEquals(0, ctx.fakeBackend().stopCount)
    }

    @Test
    fun disposeIsIdempotent() = runTest {
        val (ctx, lifecycle) = rig()
        lifecycle.setup()
        lifecycle.ready().await()
        lifecycle.dispose()
        var disposed = 0
        ctx.on(CoreEvents.Dispose) { disposed += 1 }

        lifecycle.dispose()

        assertEquals(0, disposed)
        assertEquals(1, ctx.fakeBackend().stopCount)
    }

    @Test
    fun disposingBeforeSetupFinishesFailsTheWaitersRatherThanHangingThem() = runTest {
        val (_, lifecycle) = rig()

        lifecycle.dispose()

        val failure = assertFailsWith<PlayerError> { lifecycle.ready().await() }
        assertEquals("core:player/disposed", failure.code)
    }
}
