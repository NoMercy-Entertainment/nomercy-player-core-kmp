// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.BetaInteropApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
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
private const val SETTLE_SECONDS = 0.5
private const val PLAY_SECONDS = 1.5

// The Apple engine gate, against real AVFoundation.
//
// Nothing is stubbed. AVFoundation's behaviour around indefinite CMTime, its
// status properties and its periodic observer are exactly the things a stub
// would get wrong in the same way the code does, which is why the gate drives
// the real one.
//
// The media is a silent WAV written to the temp directory: forty-four bytes of
// header and the rest zeroes. AVFoundation plays WAV, so the gate needs no
// network and no bundled asset.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AVPlayerVideoBackendTest {

    // What an app does before it plays anything, and what the library
    // deliberately does not: the audio session is a global the app owns, and a
    // library that claimed it would fight whatever else the app is playing.
    // Without one, AVPlayer on iOS reports ready and then never advances.
    private fun activateAudioSession() {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
        AVAudioSession.sharedInstance().setActive(true, null)
    }

    private fun withBackend(body: (AVPlayerVideoBackend, BackendEventRecorder, String) -> Unit) {
        activateAudioSession()
        val path = "${NSTemporaryDirectory()}nomercy-gate.wav"
        assertTrue(writeSilentWav(path, SECONDS), "could not write the gate media to $path")

        // fileURLWithPath, not "file://" + path. Hand-built file URLs are how a
        // path with anything needing escaping becomes a URL that parses and then
        // resolves to nothing, which reads as an engine that reported nothing.
        val url: String = NSURL.fileURLWithPath(path).absoluteString
            ?: error("could not build a file URL for $path")

        val backend = AVPlayerVideoBackend()
        try {
            body(backend, BackendEventRecorder(backend), url)
        } finally {
            backend.release()
        }
    }

    @Test
    fun aFreshEngineReportsZeroRatherThanNaN() = withBackend { backend, _, _ ->
        // CMTime is indefinite before an asset is read and CMTimeGetSeconds
        // gives NaN for it. NaN reaching a scrubber draws nothing and explains
        // nothing.
        assertEquals(0.0, backend.currentTime())
        assertEquals(0.0, backend.duration())
        assertEquals(BackendState.IDLE, backend.state())
    }

    @Test
    fun loadingAnnouncesLoadStartFirst() = withBackend { backend, recorder, url ->
        kotlinx.coroutines.runBlocking { backend.load(url, LoadOptions()) }

        assertEquals(CanonicalBackendEvent.LOAD_START, recorder.names().first())
    }

    @Test
    fun diagnoseWhatTheEngineActuallyDoes() = withBackend { backend, recorder, url ->
        println("DIAG url=$url")
        val diagPath = url.removePrefix("file://")
        println("DIAG file exists=" + NSFileManager.defaultManager().fileExistsAtPath(diagPath))
        println("DIAG file bytes=" + (NSFileManager.defaultManager().attributesOfItemAtPath(diagPath, null)?.get("NSFileSize")))
        kotlinx.coroutines.runBlocking { backend.load(url, LoadOptions()) }
        settle(SETTLE_SECONDS)
        println("DIAG after load: state=" + backend.state() + " duration=" + backend.duration() + " seen=" + recorder.names())
        kotlinx.coroutines.runBlocking { backend.play() }
        settle(PLAY_SECONDS)
        println("DIAG after play: state=" + backend.state() + " time=" + backend.currentTime() + " seen=" + recorder.names())
    }

    @Test
    fun aRealEngineReportsTheCanonicalSpineInOrder() = withBackend { backend, recorder, url ->
        kotlinx.coroutines.runBlocking { backend.load(url, LoadOptions()) }
        settle(SETTLE_SECONDS)

        kotlinx.coroutines.runBlocking { backend.play() }
        settle(PLAY_SECONDS)
        val whilePlaying: Double = backend.currentTime()

        backend.pause()
        settle(SETTLE_SECONDS)

        // The same assertion the desktop and Android gates make, against the
        // same ruler. That is the whole point of the spike: one vocabulary,
        // three engines that agree on it.
        assertCanonicalSubsequence(recorder.names(), CanonicalBackendEvent.PLAY_PAUSE_SPINE)
        assertTrue(whilePlaying > 0.0, "the playhead did not move: $whilePlaying")
    }

    // Turns the main run loop for a while rather than blocking it.
    //
    // AVFoundation delivers an item's status changes on the main queue, so a
    // test that blocks the main thread — with sleep, or with runBlocking and a
    // delay — gets an item that never becomes ready and an engine that appears
    // to report nothing. That is exactly how this gate first read: loadstart,
    // play, pause, and silence in between.
    private fun settle(seconds: Double) {
        NSRunLoop.mainRunLoop().runUntilDate(NSDate().dateByAddingTimeInterval(seconds))
    }

    @Test
    fun volumeRoundTripsThroughTheZeroToOneContract() = withBackend { backend, _, _ ->
        backend.volume(0.5f)

        assertTrue(backend.volume() in 0.4f..0.6f)
    }

    @Test
    fun playbackRateRoundTrips() = withBackend { backend, _, _ ->
        backend.playbackRate(1.5)

        assertEquals(1.5, backend.playbackRate(), absoluteTolerance = 0.01)
    }

    @Test
    fun mutingDoesNotThrowBeforeThereIsAnything() = withBackend { backend, _, _ ->
        backend.mute()
        backend.unmute()

        assertEquals(BackendState.IDLE, backend.state())
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
