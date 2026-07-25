// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PreventedAction
import tv.nomercy.player.core.events.RepeatChange
import tv.nomercy.player.core.events.ShuffleChange
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.PlayerState
import tv.nomercy.player.core.player.RepeatState
import tv.nomercy.player.core.player.ShuffleState
import tv.nomercy.player.core.player.VolumeState

private const val MUTED_LEVEL = 0

// Repeat and shuffle, and the one place the whole player's state is readable at
// once.
//
// The snapshot is derived, never stored. The context is the single source of
// truth and this recomputes from it whenever anything moves, so a snapshot
// cannot disagree with the player it came from — the failure you get from
// keeping a parallel copy and updating it in nineteen places.
@Suppress("TooManyFunctions")
public class StateController(
    private val ctx: PlayerContext,
    private val queue: QueueController,
    private val time: TimeController,
) {
    private val mutable: MutableStateFlow<PlayerState> = MutableStateFlow(PlayerState())

    // Collected by a chrome. StateFlow conflates equal values, so a recompute
    // that changes nothing costs no recomposition.
    public val stateFlow: StateFlow<PlayerState> = mutable.asStateFlow()

    init {
        // Every event that can move the snapshot, subscribed once. Adding a
        // state field without adding its event here is the way this goes wrong;
        // the flow simply stops updating for that field.
        ctx.on(CoreEvents.Play) { recompute() }
        ctx.on(CoreEvents.Pause) { recompute() }
        ctx.on(CoreEvents.Stop) { recompute() }
        ctx.on(CoreEvents.Phase) { recompute() }
        ctx.on(CoreEvents.Volume) { recompute() }
        ctx.on(CoreEvents.Mute) { recompute() }
        ctx.on(CoreEvents.Seek) { recompute() }
        ctx.on(CoreEvents.Seeked) { recompute() }
        ctx.on(CoreEvents.Time) { recompute() }
        ctx.on(CoreEvents.Item) { recompute() }
        ctx.on(CoreEvents.Queue) { recompute() }
        ctx.on(CoreEvents.Repeat) { recompute() }
        ctx.on(CoreEvents.Shuffle) { recompute() }
        ctx.on(CoreEvents.PlaybackRate) { recompute() }
        recompute()
    }

    public fun state(): PlayerState = snapshot()

    public fun playState(): PlayState = ctx.playState

    public fun volumeState(): VolumeState = ctx.volumeState

    public fun repeatState(): RepeatState = ctx.repeatState

    public suspend fun repeatState(state: RepeatState, opts: ActionOptions = ActionOptions()) {
        if (ctx.repeatState == state) return

        val outcome = ctx.dispatchBefore(CoreEvents.BeforeRepeat, RepeatChange(state))
        if (outcome.prevented) {
            ctx.emit(CoreEvents.RepeatPrevented, PreventedAction(outcome.reason, opts.source))
            return
        }

        ctx.repeatState = outcome.data.state
        ctx.emit(CoreEvents.Repeat, RepeatChange(ctx.repeatState))
    }

    public fun shuffleState(): ShuffleState = ctx.shuffleState

    // The boolean form exists because a toggle switch has a boolean, and making
    // every caller map true to an enum constant is friction with no payoff.
    public suspend fun shuffleState(enabled: Boolean, opts: ActionOptions = ActionOptions()) {
        shuffleState(if (enabled) ShuffleState.ON else ShuffleState.OFF, opts)
    }

    public suspend fun shuffleState(state: ShuffleState, opts: ActionOptions = ActionOptions()) {
        if (ctx.shuffleState == state) return

        val outcome = ctx.dispatchBefore(CoreEvents.BeforeShuffle, ShuffleChange(state))
        if (outcome.prevented) {
            ctx.emit(CoreEvents.ShufflePrevented, PreventedAction(outcome.reason, opts.source))
            return
        }

        ctx.shuffleState = outcome.data.state
        // Turning it on reorders what is left; turning it off leaves the order
        // alone, because unshuffling into the original order would move the
        // item the viewer is on.
        if (ctx.shuffleState == ShuffleState.ON) queue.queueShuffle()
        ctx.emit(CoreEvents.Shuffle, ShuffleChange(ctx.shuffleState))
    }

    private fun recompute() {
        mutable.value = snapshot()
    }

    private fun snapshot(): PlayerState {
        val muted: Boolean = ctx.volumeState == VolumeState.MUTED
        return PlayerState(
            phase = ctx.phase,
            playState = ctx.playState,
            time = ctx.internalCurrentTime,
            duration = ctx.internalDuration,
            buffered = time.buffered(),
            volume = if (muted) MUTED_LEVEL else ctx.internalVolume,
            muted = muted,
            volumeState = ctx.volumeState,
            playbackRate = ctx.playbackRate,
            item = queue.item(),
            index = queue.index(),
            queueLength = queue.queueLength(),
            repeatState = ctx.repeatState,
            shuffleState = ctx.shuffleState,
        )
    }
}
