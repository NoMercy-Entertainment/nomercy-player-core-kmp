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
// Stream, auth, metrics and preload wiring are named seams for later plans
// rather than stubs here. A stub that does nothing looks exactly like a feature
// that is broken.
public class LifecycleController(
    private val ctx: PlayerContext,
    private val plugins: PluginRegistry? = null,
    private val report: (PlayerError) -> Unit = {},
) {
    private val readySignal: CompletableDeferred<Unit> = CompletableDeferred()

    private var configured: PlayerConfig? = null

    public fun config(): PlayerConfig? = configured

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
        cleanUp("plugins") { plugins?.dispose() }
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
