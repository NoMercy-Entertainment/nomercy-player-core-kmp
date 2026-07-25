// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.MuteChange
import tv.nomercy.player.core.events.PreventedAction
import tv.nomercy.player.core.events.VolumeChange
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.VolumeState

private const val MIN_VOLUME = 0
private const val MAX_VOLUME = 100
private const val DEFAULT_STEP = 5

// Volume and mute.
//
// The public scale is 0..100 and the engine's is 0..1; the division happens
// here, once, so no engine has to know about both. Whether that maps to a
// perceptual curve is the engine's business — the number here is the one the
// viewer set.
public class VolumeController(private val ctx: PlayerContext) {

    // Zero while muted, whatever level is stored. A slider bound to this shows
    // what is being heard, and unmuting puts the stored level back.
    public fun volume(): Int = if (ctx.volumeState == VolumeState.MUTED) MIN_VOLUME else ctx.internalVolume

    public suspend fun volume(level: Int, opts: ActionOptions = ActionOptions()) {
        val target: Int = level.coerceIn(MIN_VOLUME, MAX_VOLUME)
        val outcome = ctx.dispatchBefore(CoreEvents.BeforeVolume, VolumeChange(target))
        if (outcome.prevented) {
            ctx.emit(CoreEvents.VolumePrevented, PreventedAction(outcome.reason, opts.source))
            return
        }

        // Dragging the slider up while muted means unmute: nobody raises the
        // volume expecting to keep hearing nothing.
        if (ctx.volumeState == VolumeState.MUTED && outcome.data.level > MIN_VOLUME) {
            ctx.volumeState = VolumeState.UNMUTED
            ctx.backend?.unmute()
            ctx.emit(CoreEvents.Mute, MuteChange(muted = false))
        }

        applyVolume(outcome.data.level)
    }

    public suspend fun mute(opts: ActionOptions = ActionOptions()): Unit = setMuted(true, opts)

    public suspend fun unmute(opts: ActionOptions = ActionOptions()): Unit = setMuted(false, opts)

    public suspend fun toggleMute(opts: ActionOptions = ActionOptions()) {
        setMuted(ctx.volumeState != VolumeState.MUTED, opts)
    }

    public suspend fun volumeUp(step: Int = DEFAULT_STEP, opts: ActionOptions = ActionOptions()) {
        volume(ctx.internalVolume + step, opts)
    }

    public suspend fun volumeDown(step: Int = DEFAULT_STEP, opts: ActionOptions = ActionOptions()) {
        volume(ctx.internalVolume - step, opts)
    }

    private fun applyVolume(level: Int) {
        ctx.internalVolume = level
        ctx.volumeBeforeMute = level
        ctx.emit(CoreEvents.Volume, VolumeChange(level))
        ctx.backend?.volume(level.toFloat() / MAX_VOLUME)
    }

    // Muting something already muted is not an event. A chrome that redraws on
    // mute would otherwise flicker every time a keyboard shortcut repeated.
    private suspend fun setMuted(muted: Boolean, opts: ActionOptions) {
        val alreadyMuted: Boolean = ctx.volumeState == VolumeState.MUTED
        if (alreadyMuted == muted) return

        val outcome = ctx.dispatchBefore(CoreEvents.BeforeMute, MuteChange(muted))
        if (outcome.prevented) {
            ctx.emit(CoreEvents.MutePrevented, PreventedAction(outcome.reason, opts.source))
            return
        }

        if (muted) {
            ctx.volumeBeforeMute = ctx.internalVolume
            ctx.volumeState = VolumeState.MUTED
            ctx.backend?.mute()
        } else {
            ctx.volumeState = VolumeState.UNMUTED
            ctx.internalVolume = ctx.volumeBeforeMute
            ctx.backend?.unmute()
            ctx.backend?.volume(ctx.internalVolume.toFloat() / MAX_VOLUME)
        }
        ctx.emit(CoreEvents.Mute, MuteChange(muted))
    }
}
