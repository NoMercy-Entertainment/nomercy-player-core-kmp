// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val STEP_MS = 16L
private const val FADE_MS = 320L
private const val FULL = 1.0f

// Float rounding on a gain, not a measurement tolerance. The scheduler stores
// what the curve returns; anything larger would let a different curve through.
private const val CURVE_TOLERANCE = 1e-6f

// A handle that records every gain it is given, in order.
//
// The order is the point: a crossfade that ended at the right numbers having
// passed through the wrong ones in between is a fade a listener hears as a dip
// or a jump, and only the sequence shows it.
private class RecordingSink(start: Float) : GainSink {
    val gains: MutableList<Float> = mutableListOf(start)
    var played: Boolean = false
        private set
    var released: Boolean = false
        private set

    private var value: Float = start

    override fun gain(): Float = value

    override fun gain(value: Float) {
        this.value = value
        gains += value
    }

    override suspend fun play() {
        played = true
    }

    override fun releaseAfterFade() {
        released = true
    }
}

// The property this exists for is that the level does not move.
//
// Two linear ramps sum to less power in the middle than either track alone, so
// every transition dips — audibly, on every track, forever. Equal power is the
// cosine pair whose squares sum to one, and asserting that at every step is the
// difference between having the curve and having the right curve.
class EqualPowerCrossfaderTest {

    private fun fader() = EqualPowerCrossfader(stepMs = STEP_MS)

    @Test
    fun theTotalPowerHoldsAcrossTheWholeFade() = runTest {
        val outgoing = RecordingSink(FULL)
        val incoming = RecordingSink(0f)

        fader().run(outgoing, incoming, EqualPowerCrossfader.Fade(FULL, FADE_MS))

        // Paired step by step, because the guarantee is about the two together
        // — and aligned by hand, because the two lists do not start level. Each
        // carries its constructor value, and the incoming one carries the
        // pre-ramp silence as well. Zipping them raw compares gains from
        // different moments, which is how this first read as a fade that moved
        // eight percent when it does not move at all.
        val outSteps: List<Float> = outgoing.gains.drop(1)
        val inSteps: List<Float> = incoming.gains.drop(2)
        val worst: Double = outSteps.zip(inSteps).maxOf { (out, into) ->
            abs(1.0 - (out * out + into * into).toDouble())
        }
        assertTrue(worst < POWER_TOLERANCE, "power moved by $worst across the fade")
    }

    @Test
    fun theScheduledRampIsThisCurveAndNotMerelyAConstantPowerOne() {
        // Constant power is necessary and not sufficient. More than one curve
        // holds in² + out² = 1 — the previous test would pass on any of them,
        // and a scheduler that quietly grew its own would drift from the web's
        // fade while staying green. This pins the shape step by step.
        runTest {
            val outgoing = RecordingSink(FULL)
            val incoming = RecordingSink(0f)

            fader().run(outgoing, incoming, EqualPowerCrossfader.Fade(FULL, FADE_MS))

            // Aligned the same way the power test aligns them: each list carries
            // its constructor value, and the incoming one carries the pre-ramp
            // silence too.
            val inSteps: List<Float> = incoming.gains.drop(2).dropLast(1)
            for ((step, actual) in inSteps.withIndex()) {
                val progress: Double = (step * STEP_MS).toDouble() / FADE_MS
                val expected: Float = CrossfadeCurve.EQUAL_POWER.gain(progress).toFloat()
                assertTrue(
                    abs(actual - expected) < CURVE_TOLERANCE,
                    "step $step scheduled $actual, the curve says $expected",
                )
            }
        }
    }

    @Test
    fun aLinearFadeDipsInTheMiddleAndThatIsWhyItIsNotTheDefault() = runTest {
        // Kept as a test rather than a comment: it is the evidence for the
        // choice, and if someone changes LINEAR's maths this says what breaks.
        val outgoing = RecordingSink(FULL)
        val incoming = RecordingSink(0f)

        fader().run(outgoing, incoming, EqualPowerCrossfader.Fade(FULL, FADE_MS, CrossfadeCurve.LINEAR))

        val middle: Int = outgoing.gains.size / 2
        val out: Float = outgoing.gains[middle]
        val into: Float = incoming.gains[middle]
        assertTrue(
            out * out + into * into < LINEAR_MIDPOINT_POWER,
            "a linear fade held its power, which would mean the curve is not linear",
        )
    }

    @Test
    fun theIncomingTrackStartsSilentAndIsStarted() = runTest {
        // Started before the ramp, at zero. Starting it at full for the instant
        // before the first step is a click on every transition.
        val outgoing = RecordingSink(FULL)
        val incoming = RecordingSink(0f)

        fader().run(outgoing, incoming, EqualPowerCrossfader.Fade(FULL, FADE_MS))

        assertTrue(incoming.played, "the incoming track was never started")
        assertEquals(0f, incoming.gains[1], "the incoming track was audible before the fade began")
    }

    @Test
    fun theFadeEndsExactlyRatherThanWhereverTheLastStepLanded() = runTest {
        // A duration that is not a whole number of steps. Left as-is, the
        // outgoing track ends at a small non-zero gain — quiet, and still
        // audible under the next one.
        val outgoing = RecordingSink(FULL)
        val incoming = RecordingSink(0f)

        fader().run(outgoing, incoming, EqualPowerCrossfader.Fade(FULL, RAGGED_FADE_MS))

        assertEquals(0f, outgoing.gains.last())
        assertEquals(FULL, incoming.gains.last())
    }

    @Test
    fun theOutgoingHandleIsReleased() = runTest {
        // A silent player still holds a decoder, and two of those per transition
        // is how a queue runs a device out of them.
        val outgoing = RecordingSink(FULL)
        val incoming = RecordingSink(0f)

        fader().run(outgoing, incoming, EqualPowerCrossfader.Fade(FULL, FADE_MS))

        assertTrue(outgoing.released, "the faded-out handle was left holding its decoder")
        assertTrue(!incoming.released, "the track that just started was released")
    }

    @Test
    fun aZeroLengthFadeIsAnImmediateSwap() = runTest {
        // Crossfade turned off, or a track shorter than the window. It must
        // still swap cleanly rather than doing nothing.
        val outgoing = RecordingSink(FULL)
        val incoming = RecordingSink(0f)

        fader().run(outgoing, incoming, EqualPowerCrossfader.Fade(FULL, 0L))

        assertEquals(0f, outgoing.gains.last())
        assertEquals(FULL, incoming.gains.last())
        assertTrue(incoming.played && outgoing.released)
    }

    @Test
    fun theFadeRespectsAVolumeThatIsNotFull() = runTest {
        // The listener set the volume to a third. Fading the incoming track up
        // to 1.0 would make every transition jump to full.
        val outgoing = RecordingSink(QUIET)
        val incoming = RecordingSink(0f)

        fader().run(outgoing, incoming, EqualPowerCrossfader.Fade(QUIET, FADE_MS))

        assertEquals(QUIET, incoming.gains.last())
        assertTrue(incoming.gains.none { it > QUIET }, "the fade went louder than the listener asked for")
    }

    private companion object {
        const val POWER_TOLERANCE = 0.02
        const val LINEAR_MIDPOINT_POWER = 0.9f
        const val RAGGED_FADE_MS = 100L
        const val QUIET = 0.33f
    }
}
