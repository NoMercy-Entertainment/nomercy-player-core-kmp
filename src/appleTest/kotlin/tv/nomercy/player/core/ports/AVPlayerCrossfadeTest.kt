// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runUntilDate
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 44_100
private const val SECONDS = 3
private const val FADE_MS = 400L
private const val SETTLE_SECONDS = 0.5
private const val FULL = 1.0f
private const val HALF = 0.5f

// A crossfade between two real AVPlayers on a simulator.
//
// The fader's own tests prove the curve against recording sinks. What they
// cannot show is that two AVPlayers can hold gains at the same time, that the
// swap leaves the right engine answering, or that a listener registered before
// the transition still hears after it — and on this platform the answer depends
// on an audio session the app owns, which is precisely why it is worth asking.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AVPlayerCrossfadeTest {

    // What an app does before it plays anything, and what the library
    // deliberately does not: the session is a global the app owns, and a library
    // that claimed it would fight whatever else is playing.
    private fun activateAudioSession() {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
        AVAudioSession.sharedInstance().setActive(true, null)
    }

    private fun media(name: String): String {
        val path = "${NSTemporaryDirectory()}$name.wav"
        check(writeSilentWav(path, SECONDS)) { "could not write the gate media to $path" }
        return NSURL.fileURLWithPath(path).absoluteString ?: error("could not build a file URL")
    }

    @Test
    fun bothPlayersCanHoldAGainAtOnce() {
        activateAudioSession()
        val backend = AVPlayerAudioBackend()

        try {
            runBlocking {
                backend.load(media("fade-a"), LoadOptions())
                settle()
                backend.play()
                backend.loadSecondary(media("fade-b"))
                settle()
                backend.primeSecondary()
            }
            backend.secondaryGain(HALF)

            assertEquals(HALF, backend.secondaryGain(), "the second player would not take a gain")
        } finally {
            backend.release()
        }
    }

    @Test
    fun theFadeLeavesTheIncomingEngineAnswering() {
        activateAudioSession()
        val backend = AVPlayerAudioBackend()

        try {
            runBlocking {
                backend.load(media("fade-a"), LoadOptions())
                settle()
                backend.play()
                backend.loadSecondary(media("fade-b"))
                settle()
                backend.primeSecondary()
                backend.crossfade(FADE_MS, CrossfadeCurve.EQUAL_POWER)
            }

            assertEquals(FULL, backend.volume(), "the track that faded in is not at full volume")
            assertEquals(0f, backend.secondaryGain(), "the retired engine was left audible")
        } finally {
            backend.release()
        }
    }

    @Test
    fun aListenerRegisteredBeforeTheFadeStillHearsAfterIt() {
        activateAudioSession()
        val backend = AVPlayerAudioBackend()
        var heard = false

        try {
            runBlocking {
                backend.load(media("fade-a"), LoadOptions())
                settle()
                backend.on(BackendEvents.PAUSE) { heard = true }
                backend.play()
                backend.loadSecondary(media("fade-b"))
                settle()
                backend.primeSecondary()
                backend.crossfade(FADE_MS, CrossfadeCurve.EQUAL_POWER)
            }
            backend.pause()
            settle()

            assertTrue(heard, "the listener stopped hearing the backend after the engines swapped")
        } finally {
            backend.release()
        }
    }

    // Turns the main run loop rather than blocking it. AVFoundation delivers
    // status changes on the main queue, so a test that blocks the main thread
    // gets an item that never becomes ready.
    private fun settle(seconds: Double = SETTLE_SECONDS) {
        NSRunLoop.mainRunLoop().runUntilDate(NSDate().dateByAddingTimeInterval(seconds))
    }

    // Forty-four bytes of header and the rest zeroes. Real media for the
    // decoder, and nothing to download.
    private fun writeSilentWav(path: String, seconds: Int): Boolean {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val dataSize = byteRate * seconds
        val bytes = ArrayList<Byte>(44 + dataSize)

        fun ascii(text: String) = text.forEach { bytes.add(it.code.toByte()) }
        fun int32(value: Int) = repeat(4) { bytes.add(((value shr (it * 8)) and 0xFF).toByte()) }
        fun int16(value: Int) = repeat(2) { bytes.add(((value shr (it * 8)) and 0xFF).toByte()) }

        ascii("RIFF"); int32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); int32(16); int16(1); int16(channels)
        int32(SAMPLE_RATE); int32(byteRate); int16(channels * bitsPerSample / 8); int16(bitsPerSample)
        ascii("data"); int32(dataSize)
        repeat(dataSize) { bytes.add(0) }

        val array: ByteArray = bytes.toByteArray()
        return array.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = array.size.toULong())
                .writeToFile(path, atomically = true)
        }
    }
}
