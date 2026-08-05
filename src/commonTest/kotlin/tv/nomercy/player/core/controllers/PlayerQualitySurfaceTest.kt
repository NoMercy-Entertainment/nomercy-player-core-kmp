// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.ports.AudioTrack
import tv.nomercy.player.core.ports.DynamicRange
import tv.nomercy.player.core.ports.QualityLevel
import tv.nomercy.player.core.ports.QualityMode
import tv.nomercy.player.core.ports.SubtitleTrack
import tv.nomercy.player.core.ports.VideoBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.testing.FakeMediaBackend

private val LADDER = listOf(
    QualityLevel(height = 1080, bitrate = 6_000_000, codec = "avc1", dynamicRange = DynamicRange.SDR),
    QualityLevel(height = 720, bitrate = 3_000_000, codec = "avc1", dynamicRange = DynamicRange.SDR),
)

// An engine that reports a ladder and remembers what was pinned.
private class LadderBackend : FakeMediaBackend(), VideoBackend {
    private var pinned: QualityLevel? = null

    override fun qualityLevels(): List<QualityLevel> = LADDER
    override fun quality(): QualityLevel? = pinned
    override fun quality(level: QualityLevel?) { pinned = level }
    override fun audioTracks(): List<AudioTrack> = emptyList()
    override fun audioTrack(): AudioTrack? = null
    override fun audioTrack(track: AudioTrack) = Unit
    override fun subtitleTracks(): List<SubtitleTrack> = emptyList()
    override fun subtitleTrack(): SubtitleTrack? = null
    override fun subtitleTrack(track: SubtitleTrack?) = Unit
}

// The quality surface a chrome binds to.
class PlayerQualitySurfaceTest {

    @Test
    fun anAudioOnlyEngineHasNoLadderRatherThanAnEmptyMenu() = runTest {
        // A player built on a backend that cannot report a ladder still works.
        // Answering with an empty list is the honest shape; throwing would make
        // a music player crash on a menu it never needed.
        val player = ComposedPlayer(backend = FakeMediaBackend())

        assertTrue(player.qualityLevels().isEmpty())
        assertEquals(null, player.quality())
        assertEquals(QualityMode.AUTO, player.qualityMode())
    }

    @Test
    fun theLadderComesBackAsDescriptors() = runTest {
        val backend = LadderBackend()
        val player = ComposedPlayer(backend = backend, video = backend)

        assertEquals(listOf(1080, 720), player.qualityLevels().map { it.height })
    }

    @Test
    fun pinningARungSwitchesTheModeAndLiftingItSwitchesBack() = runTest {
        // A menu showing a committed selection the viewer never made invites
        // them to fix a setting that was never set.
        val backend = LadderBackend()
        val player = ComposedPlayer(backend = backend, video = backend)

        assertEquals(QualityMode.AUTO, player.qualityMode())

        player.quality(LADDER[1])
        assertEquals(QualityMode.MANUAL, player.qualityMode())
        assertEquals(720, player.quality()?.height)

        player.quality(null)
        assertEquals(QualityMode.AUTO, player.qualityMode())
    }

    // The contract spells the writer by rung index, because that is what a menu
    // holds — the list it drew and the row that was touched.
    @Test
    fun aRungIsPinnedByItsPlaceInTheLadder() = runTest {
        val backend = LadderBackend()
        val player = ComposedPlayer(backend = backend, video = backend)

        player.qualityMode(1)

        assertEquals(QualityMode.MANUAL, player.qualityMode())
        assertEquals(720, player.quality()?.height)
    }

    @Test
    fun aNullIndexIsTheContractsAutoAndReleasesThePin() = runTest {
        val backend = LadderBackend()
        val player = ComposedPlayer(backend = backend, video = backend)
        player.qualityMode(0)

        player.qualityMode(null)

        assertEquals(QualityMode.AUTO, player.qualityMode())
        assertEquals(null, player.quality())
    }
}
