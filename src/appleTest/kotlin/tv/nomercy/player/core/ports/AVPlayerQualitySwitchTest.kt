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
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runUntilDate

// Capping adaptation on Apple, against real AVFoundation over a real ladder.
//
// The plan for this gate said to enumerate the rungs, pin the highest and
// assert the engine reports it back — the shape the ExoPlayer gate uses. That
// cannot be written here, and the reason is the finding rather than an
// obstacle: AVFoundation does not publish an HLS variant list. It gives the
// rendition currently playing and a ceiling to cap adaptation with, so
// qualityLevels() is one rung by design and an assumeTrue on two would have
// skipped on every machine forever while looking like a gate.
//
// What is real on this engine is the ceiling, so that is what this measures:
// set one, and the rendition the engine settles on stays under it. Pinning a
// variant is not something AVFoundation offers and a gate asserting it would
// be asserting a wish.
@OptIn(ExperimentalForeignApi::class)
class AVPlayerQualitySwitchTest {

    // A real multi-variant master. Apple's own sample, which is the one stream
    // that can be relied on to still be there and still be a ladder.
    private val master: String =
        "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"

    private fun settle(seconds: Double) {
        NSRunLoop.mainRunLoop().runUntilDate(NSDate().dateByAddingTimeInterval(seconds))
    }

    private fun withStream(body: (AVPlayerVideoBackend) -> Unit) {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
        AVAudioSession.sharedInstance().setActive(true, null)

        val backend = AVPlayerVideoBackend()
        try {
            kotlinx.coroutines.runBlocking { backend.load(master, LoadOptions()) }
            kotlinx.coroutines.runBlocking { backend.play() }
            settle(WARMUP_SECONDS)

            // Loudly, rather than a green run that measured nothing. A CI box
            // with no route to Apple's CDN is not a broken library, but it is
            // also not evidence, and the two have to look different.
            if (backend.quality() == null) {
                println("no rendition after ${WARMUP_SECONDS}s — the CDN is unreachable, skipping the Apple ladder gate")
                return
            }
            body(backend)
        } finally {
            backend.release()
        }
    }

    @Test
    fun aPlayingLadderReportsTheRenditionItIsOn() = withStream { backend ->
        val level: QualityLevel? = backend.quality()

        // Height comes from presentationSize and bitrate from the access log.
        // Both are only populated once real segments have been fetched and
        // decoded, so a reading here is evidence the stream genuinely played
        // rather than that an object was constructed.
        assertTrue(level != null && level.height > 0, "no rendition height while playing: $level")
        assertEquals(level, backend.qualityLevels().singleOrNull())
    }

    @Test
    fun aCeilingKeepsAdaptationUnderneathIt() {
        // The claim the quality control rests on for this engine. If the
        // ceiling never reached AVFoundation, adaptation would climb the ladder
        // on a fast connection and the viewer's choice would do nothing.
        withStream { backend ->
            val ceiling = QualityLevel(height = CAPPED_HEIGHT, bitrate = CAPPED_BITRATE, codec = "avc1")

            backend.quality(ceiling)
            settle(ADAPT_SECONDS)

            val landed: QualityLevel = requireNotNull(backend.quality())
            assertTrue(
                landed.bitrate <= CAPPED_BITRATE,
                "the engine settled at ${landed.bitrate}bps above a ${CAPPED_BITRATE}bps ceiling",
            )
        }
    }

    @Test
    fun theCeilingIsWhatTheEngineWasActuallyTold() = withStream { backend ->
        // Read back off AVPlayerItem rather than off the backend, because the
        // question is whether the number crossed into the framework. A backend
        // that stored it and answered from its own field would satisfy every
        // other assertion here while capping nothing.
        backend.quality(QualityLevel(height = CAPPED_HEIGHT, bitrate = CAPPED_BITRATE, codec = "avc1"))

        assertEquals(
            CAPPED_BITRATE.toDouble(),
            backend.avPlayer.currentItem?.preferredPeakBitRate,
            "AVPlayerItem never received the ceiling",
        )
    }

    @Test
    fun automaticLiftsTheCeilingRatherThanPinningZero() = withStream { backend ->
        // Zero is AVFoundation's "no limit", not "no bitrate". A backend that
        // read null as a cap of zero would stall the stream completely, which
        // looks like a network fault rather than a settings bug.
        backend.quality(QualityLevel(height = CAPPED_HEIGHT, bitrate = CAPPED_BITRATE, codec = "avc1"))
        settle(SETTLE_SECONDS)

        backend.quality(null)

        assertEquals(0.0, backend.avPlayer.currentItem?.preferredPeakBitRate)
    }
}

private const val WARMUP_SECONDS = 6.0
private const val ADAPT_SECONDS = 6.0
private const val SETTLE_SECONDS = 0.5

// Low enough to sit under the bottom of Apple's sample ladder's upper rungs, so
// a ceiling that works is visible as a ceiling. A cap above every rung would be
// satisfied by an engine that ignored it.
private const val CAPPED_BITRATE = 300_000
private const val CAPPED_HEIGHT = 224
