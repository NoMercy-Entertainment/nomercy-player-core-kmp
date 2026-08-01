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

    // Strict equality, like the reference's own lock-in, so a reorder or a
    // dropped stage fails rather than degrading quietly.
    //
    // This test used to assert listOf("beforeSetup", "ready") and pass, which is
    // how six declared stages stayed unemitted through the whole port: it was
    // written from the same partial reading of the reference as the code it
    // grades, so both agreed and neither was right.
    @Test
    fun setupWalksToReadyAndAnnouncesEveryStageInOrder() = runTest {
        val (ctx, lifecycle) = rig()
        val seen = mutableListOf<String>()
        ctx.on(CoreEvents.BeforeSetup) { seen += "beforeSetup" }
        ctx.on(CoreEvents.SetupStart) { seen += "setupStart" }
        ctx.on(CoreEvents.ConfigResolved) { seen += "configResolved" }
        ctx.on(CoreEvents.PluginsRegistering) { seen += "pluginsRegistering" }
        ctx.on(CoreEvents.PluginsRegistered) { seen += "pluginsRegistered" }
        ctx.on(CoreEvents.StreamsReady) { seen += "streamsReady" }
        ctx.on(CoreEvents.AuthReady) { seen += "authReady" }
        ctx.on(CoreEvents.Ready) { seen += "ready" }

        lifecycle.setup()
        lifecycle.ready().await()

        assertEquals(PlayerPhase.READY, ctx.phase)
        assertEquals(
            listOf(
                "beforeSetup",
                "setupStart",
                "configResolved",
                "pluginsRegistering",
                "pluginsRegistered",
                "streamsReady",
                "authReady",
                "ready",
            ),
            seen,
        )
    }

    // The stage a consumer reads its own configuration back from. Announcing it
    // empty would be the stage firing and telling nobody anything.
    @Test
    fun configResolvedCarriesTheConfigSetupWasGiven() = runTest {
        val (ctx, lifecycle) = rig()
        var announced: PlayerConfig? = null
        ctx.on(CoreEvents.ConfigResolved) { announced = it.config as? PlayerConfig }

        lifecycle.setup(PlayerConfig(defaultVolume = 42))

        assertEquals(42, announced?.defaultVolume)
    }

    // Every stage lands while the player is still setting up. A stage that
    // arrived after READY would be describing a player that had finished, and a
    // consumer holding work back until its stage would run it too late.
    @Test
    fun everyStageArrivesBeforeThePlayerIsReady() = runTest {
        val (ctx, lifecycle) = rig()
        val phases = mutableListOf<PlayerPhase>()
        for (stage in listOf(CoreEvents.PluginsRegistering, CoreEvents.StreamsReady, CoreEvents.AuthReady)) {
            ctx.on(stage) { phases += ctx.phase }
        }

        lifecycle.setup()

        assertEquals(listOf(PlayerPhase.SETUP, PlayerPhase.SETUP, PlayerPhase.SETUP), phases)
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

    @Test
    fun aBackendThatThrowsFromStopStillLeavesThePlayerDisposed() = runTest {
        // Left unguarded this stranded the player in DISPOSING: no dispose
        // event, and anything awaiting ready() waiting forever on a player that
        // is already gone. The host cannot fix that — the throwing code is the
        // engine's.
        val ctx = PlayerContext(backend = ThrowsOnStopBackend())
        val reported = mutableListOf<PlayerError>()
        val lifecycle = LifecycleController(ctx, null) { reported += it }
        val seen = mutableListOf<String>()
        ctx.on(CoreEvents.Dispose) { seen += "dispose" }
        lifecycle.setup()

        lifecycle.dispose()

        assertEquals(PlayerPhase.DISPOSED, ctx.phase)
        assertEquals(listOf("dispose"), seen)
        assertEquals(listOf("core:lifecycle/cleanup-failed"), reported.map { it.code })
        assertEquals("backend", reported.single().context["step"])
    }

    @Test
    fun aFailedTeardownStepDoesNotSkipTheStepsBehindIt() = runTest {
        // Plugins are torn down before the backend. A throwing registry that
        // took the backend down with it would leave the engine holding the
        // media file after the player said it was disposed.
        val ctx = PlayerContext(backend = ThrowsOnStopBackend())
        val reported = mutableListOf<PlayerError>()
        val lifecycle = LifecycleController(ctx, null) { reported += it }
        lifecycle.setup()

        lifecycle.dispose()

        assertEquals(PlayerPhase.DISPOSED, ctx.phase)
        assertTrue((ctx.backend as ThrowsOnStopBackend).stopWasAttempted)
    }

    @Test
    fun aDisposeThatThrowsNothingReportsNothing() = runTest {
        val (_, lifecycle) = rig()
        val reported = mutableListOf<PlayerError>()
        val quiet = LifecycleController(newContext(), null) { reported += it }
        lifecycle.setup()
        quiet.setup()

        quiet.dispose()

        assertEquals(emptyList(), reported)
    }
}
