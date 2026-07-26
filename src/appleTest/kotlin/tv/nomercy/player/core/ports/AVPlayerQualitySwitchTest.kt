// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.currentItem
import platform.AVFoundation.preferredPeakBitRate
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runUntilDate
import platform.Foundation.writeToFile

// Capping adaptation on Apple, against real AVFoundation.
//
// The plan asked for the ExoPlayer shape — enumerate the rungs, pin the
// highest, assert it comes back. That cannot be written here, and the reason is
// a finding rather than an obstacle: AVFoundation does not publish an HLS
// variant list. It gives the rendition playing and a ceiling to cap adaptation
// with, so qualityLevels() is one rung by design, and the planned
// assumeTrue(size >= 2) would have skipped on every machine forever while
// looking like a gate.
//
// The media is local, and that is the second correction. The first version of
// this file streamed Apple's public sample ladder and reported four green
// tests that had measured nothing: the simulator rejects that CDN's
// certificate with NSURLErrorServerCertificateUntrusted, so every run took the
// skip path and passed. The host curls the same URL to a clean Apple/DigiCert
// chain, so it is the simulator's trust store rather than the network — but a
// gate that depends on the public internet reports the network either way.
//
// What is left needing a real ladder is one observation: that adaptation
// climbs and settles under the cap over several rungs. That is recorded as
// device QA rather than pretended at here.
@OptIn(ExperimentalForeignApi::class)
class AVPlayerQualitySwitchTest {

    private fun settle(seconds: Double) {
        NSRunLoop.mainRunLoop().runUntilDate(NSDate().dateByAddingTimeInterval(seconds))
    }

    private fun withPlayingClip(body: (AVPlayerVideoBackend) -> Unit) {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
        AVAudioSession.sharedInstance().setActive(true, null)

        val path = "${NSTemporaryDirectory()}nomercy-ladder-gate.mp4"
        val data: NSData = checkNotNull(NSData.create(base64EncodedString = DUAL_AUDIO_CLIP_BASE64, options = 0u)) {
            "the shared clip is not valid base64"
        }
        assertTrue(data.writeToFile(path, true), "could not write the gate media to $path")

        // fileURLWithPath, not "file://" + path. A hand-built file URL is how a
        // path with anything needing escaping becomes a URL that parses and
        // then resolves to nothing, which reads as an engine reporting nothing.
        val url: String = NSURL.fileURLWithPath(path).absoluteString
            ?: error("could not build a file URL for $path")

        val backend = AVPlayerVideoBackend()
        try {
            kotlinx.coroutines.runBlocking { backend.load(url, LoadOptions()) }
            kotlinx.coroutines.runBlocking { backend.play() }
            settle(WARMUP_SECONDS)

            // A hard failure, not a skip. The previous version of this gate
            // returned quietly when the engine had no rendition, and four tests
            // reported green while never running a line of their bodies. If the
            // media cannot be played there is nothing to measure, and that has
            // to look different from a measurement that succeeded.
            assertTrue(
                backend.quality() != null,
                "the engine reported no rendition after ${WARMUP_SECONDS}s: " +
                    "item error is ${backend.avPlayer.currentItem?.error?.localizedDescription}",
            )
            body(backend)
        } finally {
            backend.release()
        }
    }

    @Test
    fun aPlayingClipReportsTheRenditionItIsOn() = withPlayingClip { backend ->
        val level: QualityLevel = checkNotNull(backend.quality())

        // Height comes from presentationSize, which stays zero until real
        // frames have been decoded — so a reading here is evidence the clip
        // genuinely played rather than that an object was constructed.
        assertEquals(CLIP_HEIGHT, level.height, "the engine reports a rendition the clip does not have")
        assertEquals(level, backend.qualityLevels().singleOrNull())
    }

    @Test
    fun theCeilingIsWhatTheEngineWasActuallyTold() = withPlayingClip { backend ->
        // Read back off AVPlayerItem rather than off the backend, because the
        // question is whether the number crossed into the framework. A backend
        // that stored it and answered from its own field would satisfy a
        // behavioural assertion while capping nothing.
        backend.quality(QualityLevel(height = CLIP_HEIGHT, bitrate = CAPPED_BITRATE, codec = "avc1"))

        assertEquals(
            CAPPED_BITRATE.toDouble(),
            backend.avPlayer.currentItem?.preferredPeakBitRate,
            "AVPlayerItem never received the ceiling",
        )
    }

    @Test
    fun automaticLiftsTheCeilingRatherThanPinningZeroBitrate() = withPlayingClip { backend ->
        // Zero is AVFoundation's "no limit", not "no bitrate". A backend that
        // read null as a cap of zero would stall the stream completely, which
        // looks like a network fault rather than a settings bug.
        backend.quality(QualityLevel(height = CLIP_HEIGHT, bitrate = CAPPED_BITRATE, codec = "avc1"))
        settle(SETTLE_SECONDS)
        assertEquals(CAPPED_BITRATE.toDouble(), backend.avPlayer.currentItem?.preferredPeakBitRate)

        backend.quality(null)

        assertEquals(0.0, backend.avPlayer.currentItem?.preferredPeakBitRate, "the ceiling was never lifted")
    }

    @Test
    fun aCeilingSurvivesBeingSetTwiceWithDifferentNumbers() {
        // One write could be a constructor default. Two, with different values,
        // cannot — and a setter that only ever applied the first would leave a
        // viewer's second choice silently ignored.
        withPlayingClip { backend ->
            backend.quality(QualityLevel(height = CLIP_HEIGHT, bitrate = CAPPED_BITRATE, codec = "avc1"))
            settle(SETTLE_SECONDS)
            backend.quality(QualityLevel(height = CLIP_HEIGHT, bitrate = RAISED_BITRATE, codec = "avc1"))

            assertEquals(RAISED_BITRATE.toDouble(), backend.avPlayer.currentItem?.preferredPeakBitRate)
        }
    }
}

private const val WARMUP_SECONDS = 3.0
private const val SETTLE_SECONDS = 0.3
private const val CLIP_HEIGHT = 180
private const val CAPPED_BITRATE = 300_000
private const val RAISED_BITRATE = 900_000
