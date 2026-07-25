// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.events.BeforeDispatchResult
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.events.PreventedAction
import tv.nomercy.player.core.events.SeekPosition
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.PlayerPhase
import tv.nomercy.player.core.player.RepeatState

// Play, pause, stop, and moving between items.
//
// Every action follows the same five steps: refuse if the player is not in a
// state where it could mean anything, ask the before-event whether it may
// happen, stop and say why if not, change the state, then tell the engine.
// Doing it in that order is what makes a plugin's veto actually prevent the
// action rather than undo it afterwards.
@Suppress("TooManyFunctions")
public class TransportController(
    private val ctx: PlayerContext,
    private val queue: QueueController,
) {
    public suspend fun play(opts: ActionOptions = ActionOptions()) {
        ctx.assertReady()
        if (!allowed(CoreEvents.BeforePlay, opts, CoreEvents.PlayPrevented)) return

        ctx.playState = PlayState.PLAYING
        ctx.transitionPhase(PlayerPhase.STARTING)
        ctx.emit(CoreEvents.Play, PlaySource(opts.source))
        ctx.backend?.play()
    }

    public suspend fun pause(opts: ActionOptions = ActionOptions()) {
        ctx.assertReady()
        if (!allowed(CoreEvents.BeforePause, opts, CoreEvents.PausePrevented)) return

        ctx.playState = PlayState.PAUSED
        ctx.transitionPhase(PlayerPhase.PAUSED)
        ctx.emit(CoreEvents.Pause, PlaySource(opts.source))
        ctx.backend?.pause()
    }

    public suspend fun stop(opts: ActionOptions = ActionOptions()) {
        ctx.assertReady()
        if (!allowed(CoreEvents.BeforeStop, opts, CoreEvents.StopPrevented)) return

        ctx.playState = PlayState.STOPPED
        ctx.transitionPhase(PlayerPhase.STOPPED)
        ctx.emit(CoreEvents.Stop, PlaySource(opts.source))
        ctx.backend?.stop()
    }

    public suspend fun togglePlayback(opts: ActionOptions = ActionOptions()) {
        if (ctx.playState == PlayState.PLAYING) pause(opts) else play(opts)
    }

    // Where the repeat modes live. ONE reloads the same item without moving the
    // cursor; ALL wraps to the top; OFF says the queue is done rather than
    // silently doing nothing, so a chrome can show it.
    public suspend fun next(opts: ActionOptions = ActionOptions()) {
        ctx.assertReady()
        if (!allowed(CoreEvents.BeforeNext, opts, CoreEvents.NextPrevented)) return

        ctx.emit(CoreEvents.Next, PlaySource(opts.source))
        when {
            ctx.repeatState == RepeatState.ONE -> ctx.queue.current()?.let { loadAndPlay(it, opts) }
            ctx.queue.peekNext() != null -> advanceTo(ctx.queue.currentIndex() + 1, opts)
            ctx.repeatState == RepeatState.ALL -> advanceTo(0, opts)
            else -> ctx.emit(CoreEvents.QueueExhausted, Unit)
        }
    }

    // No exhausted event at the start of the queue: reaching the beginning is
    // not the same event as running out, and a chrome that showed "queue
    // finished" for pressing back on track one would be wrong.
    public suspend fun previous(opts: ActionOptions = ActionOptions()) {
        ctx.assertReady()
        if (!allowed(CoreEvents.BeforePrevious, opts, CoreEvents.PreviousPrevented)) return

        ctx.emit(CoreEvents.Previous, PlaySource(opts.source))
        if (ctx.queue.peekPrevious() != null) advanceTo(ctx.queue.currentIndex() - 1, opts)
    }

    public suspend fun rewind(seconds: Double, opts: ActionOptions = ActionOptions()) {
        seek((ctx.internalCurrentTime - seconds).coerceAtLeast(0.0), opts)
    }

    public suspend fun forward(seconds: Double, opts: ActionOptions = ActionOptions()) {
        seek(ctx.internalCurrentTime + seconds, opts)
    }

    // Back to the start and playing again. A refused seek stops the whole
    // thing: restarting without the seek would just be play(), which is not
    // what the caller asked for.
    public suspend fun restart(opts: ActionOptions = ActionOptions()) {
        if (seek(0.0, opts)) play(opts)
    }

    public suspend fun seek(seconds: Double, opts: ActionOptions = ActionOptions()): Boolean {
        ctx.assertReady()
        val target = SeekPosition(seconds, opts.source)
        val outcome: BeforeDispatchResult<SeekPosition> = ctx.dispatchBefore(CoreEvents.BeforeSeek, target)
        if (outcome.prevented) {
            ctx.emit(CoreEvents.SeekPrevented, PreventedAction(outcome.reason, opts.source))
            return false
        }

        val resolved: Double = outcome.data.time
        seekingTransition {
            ctx.internalCurrentTime = resolved
            ctx.emit(CoreEvents.Seek, SeekPosition(resolved, opts.source))
            ctx.backend?.currentTime(resolved)
        }
        ctx.emit(CoreEvents.Seeked, SeekPosition(resolved, opts.source))
        return true
    }

    private suspend fun advanceTo(index: Int, opts: ActionOptions) {
        // The cursor moves before the media does, so the item event a chrome
        // renders arrives before the buffering it is about to show.
        ctx.queue.setCurrent(index)
        ctx.queue.current()?.let { loadAndPlay(it, opts) }
    }

    private suspend fun loadAndPlay(item: PlaylistItem, opts: ActionOptions) {
        ctx.load(item)
        play(opts)
    }

    // Only a player that was actually going anywhere passes through SEEKING.
    // Seeking a stopped player is a position change, not a state change, and
    // announcing one would make a chrome flash a spinner over a still frame.
    private inline fun seekingTransition(doSeek: () -> Unit) {
        val before: PlayerPhase = ctx.phase
        val announce: Boolean = before == PlayerPhase.PLAYING ||
            before == PlayerPhase.PAUSED ||
            before == PlayerPhase.STARTING

        if (announce) ctx.transitionPhase(PlayerPhase.SEEKING)
        doSeek()
        if (announce) ctx.transitionPhase(before)
    }

    // The before/prevented pair, written once. Returning false means the
    // caller stops; the reason has already been announced.
    private suspend fun allowed(
        before: EventKey<BeforeEvent<ActionOptions>>,
        opts: ActionOptions,
        prevented: EventKey<PreventedAction>,
    ): Boolean {
        val outcome: BeforeDispatchResult<ActionOptions> = ctx.dispatchBefore(before, opts)
        if (!outcome.prevented) return true
        ctx.emit(prevented, PreventedAction(outcome.reason, opts.source))
        return false
    }
}
