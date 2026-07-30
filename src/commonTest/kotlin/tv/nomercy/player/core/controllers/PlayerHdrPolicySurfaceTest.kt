// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.ports.AudioTrack
import tv.nomercy.player.core.ports.HdrOnSdrFallback
import tv.nomercy.player.core.ports.QualityLevel
import tv.nomercy.player.core.ports.SubtitleTrack
import tv.nomercy.player.core.ports.VideoBackend
import tv.nomercy.player.testing.FakeMediaBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

// An engine that remembers which fallback it was handed, and nothing else.
private class FallbackRecordingBackend : FakeMediaBackend(), VideoBackend {
    var received: HdrOnSdrFallback? = null

    override fun hdrOnSdrFallback(fallback: HdrOnSdrFallback) {
        received = fallback
    }

    override fun qualityLevels(): List<QualityLevel> = emptyList()
    override fun quality(): QualityLevel? = null
    override fun quality(level: QualityLevel?) = Unit
    override fun audioTracks(): List<AudioTrack> = emptyList()
    override fun audioTrack(): AudioTrack? = null
    override fun audioTrack(track: AudioTrack) = Unit
    override fun subtitleTracks(): List<SubtitleTrack> = emptyList()
    override fun subtitleTrack(): SubtitleTrack? = null
    override fun subtitleTrack(track: SubtitleTrack?) = Unit
}

// An engine that overrides none of the dynamic-range contract, which is what a
// consumer's own backend looks like the day this ships.
private class SilentBackend : FakeMediaBackend(), VideoBackend {
    override fun qualityLevels(): List<QualityLevel> = emptyList()
    override fun quality(): QualityLevel? = null
    override fun quality(level: QualityLevel?) = Unit
    override fun audioTracks(): List<AudioTrack> = emptyList()
    override fun audioTrack(): AudioTrack? = null
    override fun audioTrack(track: AudioTrack) = Unit
    override fun subtitleTracks(): List<SubtitleTrack> = emptyList()
    override fun subtitleTrack(): SubtitleTrack? = null
    override fun subtitleTrack(track: SubtitleTrack?) = Unit
}

// Whether the consumer's dynamic-range choice reaches the engine that acts on it.
//
// The decision itself is proved by HdrPolicyTest. What this proves is the wiring,
// which is the half that was missing: hdrDecision was written, tested three ways
// and called from no playback path, so HDR played on an SDR screen with nothing
// consulted and nothing converted.
class PlayerHdrPolicySurfaceTest {

    @Test
    fun theConfiguredFallbackReachesTheEngine() = runTest {
        val backend = FallbackRecordingBackend()
        val player = ComposedPlayer(backend = backend, video = backend)

        player.setup(PlayerConfig(hdrOnSdr = HdrOnSdrFallback.Refuse))

        assertEquals(HdrOnSdrFallback.Refuse, backend.received)
    }

    @Test
    fun theDefaultIsToPlayRatherThanToRefuse() = runTest {
        // A library that refused by default would turn a colour problem into a
        // black screen for every consumer who never thought about dynamic range.
        val backend = FallbackRecordingBackend()
        val player = ComposedPlayer(backend = backend, video = backend)

        player.setup(PlayerConfig())

        assertEquals(HdrOnSdrFallback.Play, backend.received)
    }

    @Test
    fun anEngineThatWasNeverAskedIsNotSetUpAsAbleToConvert() = runTest {
        // The default has to be false. An engine that inherited "yes" would have
        // hdrDecision answer ToneMap and then convert nothing, which is the one
        // outcome worse than refusing: a promise with no picture behind it.
        val backend = SilentBackend()

        assertFalse(backend.canToneMapHdrToSdr)
    }

    @Test
    fun anAudioOnlyPlayerIsNotHandedADynamicRangeDecision() = runTest {
        // Music players are built on this same config. Pushing the fallback at a
        // player with no video engine must not throw on the way past.
        val player = ComposedPlayer(backend = FakeMediaBackend())

        player.setup(PlayerConfig(hdrOnSdr = HdrOnSdrFallback.Refuse))

        assertNull(player.quality())
    }
}
