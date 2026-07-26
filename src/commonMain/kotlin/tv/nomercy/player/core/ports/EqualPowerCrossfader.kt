// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.coroutines.delay

// The one crossfade, driven by every engine that can play two things at once.
//
// Written once on purpose. A fade is a handful of lines and three engines would
// each get them subtly differently — a step count here, a rounding there — and
// the result is a transition that sounds right on a phone and dips on a desktop,
// which nobody can reproduce because nobody plays the same track on both.
//
// The step is a fixed budget rather than a frame callback, which is what makes
// it deterministic: a test drives it under a virtual clock and gets exactly the
// same ramp the device does.
public class EqualPowerCrossfader(private val stepMs: Long = DEFAULT_STEP_MS) {

    // The three things that vary per transition, together. Separately they were
    // five positional arguments at the call site, two of which are numbers —
    // which is how a duration ends up in the volume.
    public data class Fade(
        val startVolume: Float,
        val durationMs: Long,
        val curve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER,
    )

    // Ramps [outgoing] down and [incoming] up over [durationMs], then releases
    // the outgoing handle.
    //
    // Both gains are computed from the same progress rather than one from the
    // other, so the curve's own guarantee — in² + out² = 1 for equal power —
    // holds at every step instead of accumulating rounding across the fade.
    public suspend fun run(outgoing: GainSink, incoming: GainSink, fade: Fade) {
        val startVolume: Float = fade.startVolume
        val durationMs: Long = fade.durationMs
        val curve: CrossfadeCurve = fade.curve

        // Silence first, so the incoming track cannot be heard at full volume
        // for the instant between starting it and the first ramp step.
        incoming.gain(0f)
        incoming.play()

        if (durationMs <= 0) {
            finish(outgoing, incoming, startVolume)
            return
        }

        var elapsed: Long = 0
        while (elapsed < durationMs) {
            val progress: Double = elapsed.toDouble() / durationMs
            incoming.gain(startVolume * curve.gain(progress).toFloat())
            outgoing.gain(startVolume * curve.gain(1.0 - progress).toFloat())

            delay(stepMs)
            elapsed += stepMs
        }

        finish(outgoing, incoming, startVolume)
    }

    // The endpoints are set exactly rather than left wherever the last step
    // landed. A fade whose duration is not a whole number of steps otherwise
    // ends a fraction short, and a track that stays at 0.02 gain is a track a
    // listener can still hear under the next one.
    private fun finish(outgoing: GainSink, incoming: GainSink, startVolume: Float) {
        incoming.gain(startVolume)
        outgoing.gain(0f)
        outgoing.releaseAfterFade()
    }

    private companion object {
        // Sixteen milliseconds is a frame at sixty hertz. Finer is inaudible and
        // costs a wake-up per step; coarser is a staircase you can hear.
        const val DEFAULT_STEP_MS = 16L
    }
}
