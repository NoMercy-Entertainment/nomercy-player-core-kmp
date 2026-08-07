// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider

/**
 * Two libmpv handles holding gains at once, and the fade between them.
 *
 * The same two claims [VlcjCrossfadeTest] makes of libVLC, against the engine
 * that is replacing it — because "the audio backend is written" and "the audio
 * backend crossfades" are different statements, and removing libVLC on the
 * first one would delete the only proven fade on this desktop.
 *
 * A generated clip rather than a fixture: what is under test is two engines
 * holding independent gains, and a network stream puts a download between the
 * assertion and the thing asserted.
 */
class MpvCrossfadeTest {

    private fun media(name: String): File {
        val file: File = File.createTempFile(name, ".y4m")
        file.writeBytes(rawClip())
        return file
    }

    @Test
    fun bothEnginesCanHoldAGainAtOnce() {
        val reason: String? = MpvVideoEngineProvider.whyUnavailable()
        if (reason != null) return println("SKIPPED: libmpv unavailable — $reason")

        val backend = MpvAudioBackend()
        val first: File = media("mpv-fade-a")
        val second: File = media("mpv-fade-b")

        try {
            runBlocking {
                backend.load(first.absolutePath, LoadOptions())
                backend.play()
                backend.loadSecondary(second.absolutePath)
                backend.primeSecondary()
            }
            backend.secondaryGain(HALF)

            assertTrue(
                backend.secondaryGain() in HALF - TOLERANCE..HALF + TOLERANCE,
                "the second engine would not take a gain: ${backend.secondaryGain()}",
            )
            // And the playing one did not follow it down. This is the
            // difference from libVLC, whose volume is not strictly per player:
            // there, silencing the standby dragged the playing engine with it
            // and a fade read from the wrong engine faded IN to silence.
            assertTrue(
                backend.volume() > HALF + TOLERANCE,
                "silencing the standby moved the playing engine: ${backend.volume()}",
            )
        } finally {
            backend.release()
            first.delete()
            second.delete()
        }
    }

    @Test
    fun theFadeLeavesTheIncomingEngineAnswering() {
        val reason: String? = MpvVideoEngineProvider.whyUnavailable()
        if (reason != null) return println("SKIPPED: libmpv unavailable — $reason")

        val backend = MpvAudioBackend()
        val first: File = media("mpv-fade-a")
        val second: File = media("mpv-fade-b")

        try {
            val playing: Float = runBlocking {
                backend.load(first.absolutePath, LoadOptions())
                backend.play()
                // Read with one engine live, because that is the level the fade
                // has to arrive at. A hardcoded 1.0 would read the machine's
                // saved volume as the contract.
                val level: Float = backend.volume()
                backend.loadSecondary(second.absolutePath)
                backend.primeSecondary()
                backend.crossfade(FADE_MS, CrossfadeCurve.EQUAL_POWER)
                level
            }

            assertTrue(
                backend.volume() in playing - TOLERANCE..playing + TOLERANCE,
                "the track that faded in did not reach $playing: ${backend.volume()}",
            )
            assertEquals(0f, backend.secondaryGain(), "the retired engine was left audible")
        } finally {
            backend.release()
            first.delete()
            second.delete()
        }
    }

    // A tiny raw clip, so the engines have something real to open without a
    // network or a licensed file.
    private fun rawClip(): ByteArray {
        val size = 16
        val header: String = "YUV4MPEG2 W$size H$size F10:1 Ip A1:1 C420jpeg" + LINE_END
        val frameMarker: String = "FRAME" + LINE_END
        val luma = ByteArray(size * size) { index -> if (index % 2 == 0) LIGHT else DARK }
        val chroma = ByteArray(size / 2 * (size / 2)) { NEUTRAL }
        val frame: ByteArray = frameMarker.toByteArray() + luma + chroma + chroma
        return header.toByteArray() + (1..FRAME_COUNT).fold(ByteArray(0)) { whole, _ -> whole + frame }
    }

    private companion object {
        const val HALF = 0.5f
        const val TOLERANCE = 0.05f
        const val FADE_MS = 400L
        const val FRAME_COUNT = 200
        const val LINE_END = "\n"
        const val LIGHT: Byte = 235.toByte()
        const val DARK: Byte = 16
        const val NEUTRAL: Byte = 128.toByte()
    }
}
