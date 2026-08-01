// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.errors.stateError
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PreventedAction
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.plugin.PluginRegistry

// Birth, readiness and death.
//
// setup() and dispose() are the two calls a host makes, and both are guarded:
// setting up twice and using a disposed player are different bugs and get
// different codes, because "player is broken" is not a diagnosis.
//
// Stream and auth wiring are named seams for later plans rather than stubs
// here. A stub that does nothing looks exactly like a feature that is broken.
public class LifecycleController(
    private val ctx: PlayerContext,
    private val plugins: PluginRegistry? = null,
    private val report: (PlayerError) -> Unit = {},
) {
    private val readySignal: CompletableDeferred<Unit> = CompletableDeferred()

    private var configured: PlayerConfig? = null

    // What setup wired and dispose has to unwire, in registration order.
    //
    // The reference's `_policyCleanup`. Everything a setup step subscribed to,
    // timed or acquired goes on here named, so a teardown that throws says which
    // step it was rather than reporting that dispose failed.
    private val policyCleanup: MutableList<Pair<String, () -> Unit>> = mutableListOf()

    public fun config(): PlayerConfig? = configured

    // Register a teardown for something setup wired.
    //
    // Called by the composition rather than by a host: the pieces that wire at
    // setup — policies, metrics sampling, preload orchestration — live on the
    // player, and their unwiring has to happen inside the one dispose the host
    // calls rather than beside it.
    public fun onCleanup(step: String, teardown: () -> Unit) {
        policyCleanup += step to teardown
    }

    // Awaited by anything that must not run before the player exists. Memoized,
    // so twenty callers await one signal rather than racing twenty.
    public fun ready(): Deferred<Unit> = readySignal

    public suspend fun setup(config: PlayerConfig = PlayerConfig()) {
        if (ctx.phase == PlayerPhase.DISPOSING || ctx.phase == PlayerPhase.DISPOSED) {
            throw stateError(CoreErrorCodes.DISPOSED, "The player has been disposed.")
        }
        if (configured != null) {
            throw alreadySetUp()
        }

        ctx.dispatchBefore(CoreEvents.BeforeSetup, Unit)
        configured = config
        ctx.transitionPhase(PlayerPhase.SETUP)
        ctx.internalVolume = config.defaultVolume
        ctx.volumeBeforeMute = config.defaultVolume

        ctx.transitionPhase(PlayerPhase.READY)
        ctx.emit(CoreEvents.Ready, Unit)
        readySignal.complete(Unit)
    }

    // Refusable, because a plugin holding unsaved state has a legitimate reason
    // to ask for a moment. A refused dispose leaves the player exactly as it
    // was — no half-torn-down state.
    public suspend fun dispose(opts: ActionOptions = ActionOptions()) {
        if (ctx.phase == PlayerPhase.DISPOSING || ctx.phase == PlayerPhase.DISPOSED) return

        val outcome = ctx.dispatchBefore(CoreEvents.BeforeDispose, Unit)
        if (outcome.prevented) {
            ctx.emit(CoreEvents.DisposePrevented, PreventedAction(outcome.reason, opts.source))
            return
        }

        ctx.transitionPhase(PlayerPhase.DISPOSING)
        // Plugins first, while the player is otherwise intact, then everything
        // setup wired — the reference's order, and it matters: a plugin reading
        // the player as it cleans up must not find the policies already gone.
        cleanUp("plugins") { plugins?.dispose() }
        for ((step, teardown) in policyCleanup) cleanUp(step, teardown)
        policyCleanup.clear()
        cleanUp("backend") { ctx.backend?.stop() }
        ctx.transitionPhase(PlayerPhase.DISPOSED)
        ctx.emit(CoreEvents.Dispose, Unit)

        // A caller still awaiting ready() would otherwise wait forever on a
        // player that is never going to be ready.
        if (!readySignal.isCompleted) {
            readySignal.completeExceptionally(stateError(CoreErrorCodes.DISPOSED, "Disposed before setup finished."))
        }
    }

    // A teardown step that throws must not strand the player half-disposed.
    // Left unguarded, the phase stays DISPOSING, the dispose event never fires,
    // and anything awaiting ready() waits forever on a player that is already
    // gone — a leak whose cause is a plugin the host may not have written.
    // Every step runs, every failure is reported, and the player always arrives
    // at DISPOSED.
    @Suppress("TooGenericExceptionCaught")
    private inline fun cleanUp(step: String, body: () -> Unit) {
        try {
            body()
        } catch (cause: Throwable) {
            report(
                PlayerError(
                    code = CoreErrorCodes.CLEANUP_FAILED,
                    scope = ErrorScope.core(),
                    severity = Severity.WARNING,
                    message = "${CoreErrorCodes.CLEANUP_FAILED}: tearing down $step threw; dispose continued.",
                    cause = cause,
                    context = mapOf("step" to step),
                ),
            )
        }
    }

    private fun alreadySetUp(): PlayerError = stateError(
        CoreErrorCodes.ALREADY_SETUP,
        "The player is already set up. Dispose it before setting it up again.",
    )
}
