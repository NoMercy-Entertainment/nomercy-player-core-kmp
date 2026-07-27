// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Test
import tv.nomercy.player.core.dsp.Fft
import tv.nomercy.player.core.dsp.HannWindow
import tv.nomercy.player.core.plugins.audio.VisualizationFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The equaliser measured through the same path the audio takes.
//
// This is why the processor exists instead of android.media.audiofx.Equalizer:
// the platform one needs a live audio session, a playing stream and a system
// service, and its Visualizer counterpart has no Robolectric shadow at all.
// A ByteBuffer transform can be handed a synthetic sine on a workstation and
// the boost measured by FFT — an actual decibel figure rather than a call that
// returned without throwing.
class BiquadEqAudioProcessorTest {

    private fun configured(): BiquadEqAudioProcessor {
        val processor = BiquadEqAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))
        processor.flush()
        return processor
    }

    // Interleaved stereo PCM16, little endian — what Media3 hands a processor.
    private fun sine(hz: Double, frames: Int = FFT_SIZE): ByteBuffer {
        val buffer: ByteBuffer = ByteBuffer
            .allocateDirect(frames * CHANNELS * BYTES_PER_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (frame in 0 until frames) {
            val value: Double = sin(2.0 * PI * hz * frame / SAMPLE_RATE) * AMPLITUDE
            val sample: Short = (value * FULL_SCALE).toInt().toShort()
            repeat(CHANNELS) { buffer.putShort(sample) }
        }
        return buffer.also { it.flip() }
    }

    // The level at one frequency, in decibels, from the left channel.
    private fun levelDb(pcm: ByteBuffer, hz: Double): Double {
        val samples = DoubleArray(FFT_SIZE)
        val reading: ByteBuffer = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until FFT_SIZE) {
            samples[frame] = reading.short.toDouble() / FULL_SCALE
            repeat(CHANNELS - 1) { reading.short }
        }

        val magnitudes: DoubleArray = Fft.magnitudes(HannWindow.apply(samples))
        val bin: Int = (hz * FFT_SIZE / SAMPLE_RATE).toInt()
        val peak: Double = (bin - 1..bin + 1).maxOf { magnitudes[it] }
        return 20.0 * log10(peak)
    }

    @Test
    fun boostingABandRaisesThatBandByTheNumberItWasGiven() {
        // The claim the whole equaliser rests on. A chain that applied the
        // wrong gain would still sound like an equaliser — it would just never
        // match what the slider says, and nobody could tell by listening.
        val processor: BiquadEqAudioProcessor = configured()
        processor.graph().bandGain(TONE.toInt(), BOOST_DB)

        val input: ByteBuffer = sine(TONE)
        processor.queueInput(input)
        val measured: Double = levelDb(processor.output, TONE) - levelDb(sine(TONE), TONE)

        assertTrue(abs(measured - BOOST_DB) < TOLERANCE_DB, "asked for ${BOOST_DB}dB, measured ${measured}dB")
    }

    @Test
    fun cuttingABandLowersItByTheNumberItWasGiven() {
        // The other direction, because a sign error in the cookbook's A term
        // gives a filter that boosts on both.
        val processor: BiquadEqAudioProcessor = configured()
        processor.graph().bandGain(TONE.toInt(), -BOOST_DB)

        processor.queueInput(sine(TONE))
        val measured: Double = levelDb(processor.output, TONE) - levelDb(sine(TONE), TONE)

        assertTrue(abs(measured + BOOST_DB) < TOLERANCE_DB, "asked for -${BOOST_DB}dB, measured ${measured}dB")
    }

    @Test
    fun aBandLeavesTheRestOfTheSpectrumAlone() {
        // A boost at 1kHz that also lifted 10kHz would be a filter that is not
        // where it claims to be, and every preset would be wrong in a way that
        // reads as "the equaliser sounds odd" rather than as a bug.
        val processor: BiquadEqAudioProcessor = configured()
        processor.graph().bandGain(TONE.toInt(), BOOST_DB)

        processor.queueInput(sine(FAR_TONE))
        val measured: Double = levelDb(processor.output, FAR_TONE) - levelDb(sine(FAR_TONE), FAR_TONE)

        assertTrue(abs(measured) < LEAK_TOLERANCE_DB, "a 1kHz boost moved 10kHz by ${measured}dB")
    }

    @Test
    fun aFlatChainReturnsTheSignalItWasGiven() {
        // The default has to be transparent. A chain that coloured the sound at
        // rest would mean every listener hears the equaliser whether or not
        // they ever opened it.
        val processor: BiquadEqAudioProcessor = configured()

        processor.queueInput(sine(TONE))
        val measured: Double = levelDb(processor.output, TONE) - levelDb(sine(TONE), TONE)

        assertTrue(abs(measured) < LEAK_TOLERANCE_DB, "a flat chain changed the level by ${measured}dB")
    }

    @Test
    fun bypassingIsTransparentEvenWithACurveInPlace() {
        val processor: BiquadEqAudioProcessor = configured()
        processor.graph().bandGain(TONE.toInt(), BOOST_DB)
        processor.graph().eqEnabled(false)

        processor.queueInput(sine(TONE))
        val measured: Double = levelDb(processor.output, TONE) - levelDb(sine(TONE), TONE)

        assertTrue(abs(measured) < LEAK_TOLERANCE_DB, "a bypassed chain still applied ${measured}dB")
    }

    @Test
    fun theSpectrumTapSeesTheToneThatWasPlayed() {
        val processor: BiquadEqAudioProcessor = configured()
        var frame: VisualizationFrame? = null
        processor.graph().installFrameTap { frame = it }

        processor.queueInput(sine(BASS_TONE))
        processor.output

        val seen: VisualizationFrame = assertNotNull(frame, "no frame reached the tap")
        assertTrue(
            seen.bandEnergies.bass > seen.bandEnergies.treble,
            "an 80Hz tone did not light the bass band: ${seen.bandEnergies}",
        )
    }

    @Test
    fun theOutputIsTheSameLengthAsTheInput() {
        // A processor that returned fewer frames than it was given makes the
        // stream drift out of sync with the video a little on every buffer.
        val processor: BiquadEqAudioProcessor = configured()
        val input: ByteBuffer = sine(TONE)
        val inputBytes: Int = input.remaining()

        processor.queueInput(input)

        assertEquals(inputBytes, processor.output.remaining())
    }

    @Test
    fun aFormatThatIsNotSixteenBitIsRefusedRatherThanPassedThrough() {
        // Silently letting float PCM past would be an equaliser that stops
        // working on certain files with no indication why.
        val processor = BiquadEqAudioProcessor()

        try {
            processor.configure(AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_FLOAT))
            throw AssertionError("float PCM was accepted")
        } catch (expected: AudioProcessor.UnhandledAudioFormatException) {
            assertTrue(expected.message?.isNotEmpty() == true)
        }
    }
}

private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 2
private const val BYTES_PER_SAMPLE = 2
private const val FFT_SIZE = 4_096
private const val FULL_SCALE = 32_768.0

// Well short of full scale, so a boost has somewhere to go before it clips and
// the measurement reads the filter rather than the limiter.
//
// 0.25 was the first choice and it was wrong by exactly the margin that hides
// the defect this gate exists for: a +12dB boost multiplies by four, which puts
// 0.25 precisely at full scale. With the gain divisor deliberately halved the
// cut test reported a clean doubling and the boost test reported 14dB instead
// of 24 — the extra had been clipped away. A gate that measures the limiter
// cannot measure the filter.
private const val AMPLITUDE = 0.1

private const val TONE = 1_000.0
private const val FAR_TONE = 10_000.0
private const val BASS_TONE = 80.0
private const val BOOST_DB = 12.0

// Wide enough for the window's own spread, narrow enough that a wrong gain
// divisor — the defect that halves or doubles every band — cannot hide in it.
private const val TOLERANCE_DB = 1.5
private const val LEAK_TOLERANCE_DB = 1.0
