// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FADE_MS = 400L
private const val HALF = 0.5f

// A crossfade between two real libVLC players.
//
// The fader's own tests prove the curve against recording sinks. What they
// cannot show is that a second libVLC player can hold audio beside the first,
// that the swap leaves the right engine answering, or that a listener
// registered before the transition still hears after it — which on this engine
// is a different question from Android, because the handles are native and the
// events come off VLC's own thread.
//
// Skips where libVLC is absent, loudly, for the same reason every other desktop
// gate does: a machine without VLC is not a broken build.
class VlcjCrossfadeTest {

    private fun media(name: String): File {
        val file: File = File.createTempFile(name, ".y4m")
        file.writeBytes(rawClip())
        return file
    }

    @Test
    fun bothPlayersCanHoldAGainAtOnce() {
        val reason: String? = VlcjVideoBackend.whyUnavailable()
        if (reason != null) {
            println("skipped: $reason")
            return
        }

        val backend = VlcjAudioBackend()
        val first: File = media("fade-a")
        val second: File = media("fade-b")

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
        } finally {
            // Everything, not just the standby. libVLC's audio state is shared
            // enough that a player left alive decides the next test's answer —
            // which is how this first showed up: a volume round-trip that
            // passed alone and failed after this ran.
            backend.release()
            first.delete()
            second.delete()
        }
    }

    @Test
    fun theFadeLeavesTheIncomingEngineAnswering() {
        val reason: String? = VlcjVideoBackend.whyUnavailable()
        if (reason != null) {
            println("skipped: $reason")
            return
        }

        val backend = VlcjAudioBackend()
        val first: File = media("fade-a")
        val second: File = media("fade-b")

        try {
            val playing: Float = runBlocking {
                backend.load(first.absolutePath, LoadOptions())
                backend.play()
                // Read here, with one engine live, because that is the level the
                // fade has to arrive at. Asserting a hardcoded 1.0 instead reads
                // the machine's saved VLC volume as if it were the contract: on
                // a desktop whose vlcrc says 50%, a perfect fade lands at 0.5
                // and a test expecting full scale calls it broken.
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
            // Everything, not just the standby. libVLC's audio state is shared
            // enough that a player left alive decides the next test's answer —
            // which is how this first showed up: a volume round-trip that
            // passed alone and failed after this ran.
            backend.release()
            first.delete()
            second.delete()
        }
    }

    // Uncompressed frames behind Y4M's text header, so the gate needs no
    // fixture and no encoder. libVLC demuxes it directly.
    private fun rawClip(): ByteArray {
        val size = 16
        val header: String = "YUV4MPEG2 W$size H$size F10:1 Ip A1:1 C420jpeg" + LINE_END
        val frameMarker: String = "FRAME" + LINE_END
        val luma = ByteArray(size * size) { if (it % 2 == 0) LIGHT else DARK }
        val chroma = ByteArray(size / 2 * (size / 2)) { NEUTRAL }
        val frame: ByteArray = frameMarker.toByteArray() + luma + chroma + chroma
        return header.toByteArray() + (1..FRAME_COUNT).fold(ByteArray(0)) { acc, _ -> acc + frame }
    }

    private companion object {
        const val TOLERANCE = 0.05f
        const val FRAME_COUNT = 200
        const val LINE_END = "\n"
        const val LIGHT: Byte = 235.toByte()
        const val DARK: Byte = 16
        const val NEUTRAL: Byte = 128.toByte()
    }
}
