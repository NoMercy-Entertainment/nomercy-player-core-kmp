// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.SubtitlesPayload
import tv.nomercy.player.core.ports.AudioTrack
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.QualityLevel
import tv.nomercy.player.core.ports.SubtitleTrack
import tv.nomercy.player.core.ports.VideoBackend
import tv.nomercy.player.testing.FakeMediaBackend
import kotlin.test.Test
import kotlin.test.assertEquals

private val LATE_TRACKS = listOf(SubtitleTrack(id = "sub-1", language = "nl", label = "Nederlands"))

private class LateTracksBackend : FakeMediaBackend(), VideoBackend {
    var tracks: List<SubtitleTrack> = emptyList()

    fun learnTracks(learned: List<SubtitleTrack>) {
        tracks = learned
        fire(CanonicalBackendEvent.SUBTITLE_TRACKS)
    }

    override fun audioTracks(): List<AudioTrack> = emptyList()
    override fun audioTrack(): AudioTrack? = null
    override fun audioTrack(track: AudioTrack) = Unit
    override fun qualityLevels(): List<QualityLevel> = emptyList()
    override fun quality(): QualityLevel? = null
    override fun quality(level: QualityLevel?) = Unit
    override fun subtitleTracks(): List<SubtitleTrack> = tracks
    override fun subtitleTrack(): SubtitleTrack? = null
    override fun subtitleTrack(track: SubtitleTrack?) = Unit
}

class LateSubtitleTracksTest {

    @Test
    fun aTrackListLearnedAfterLoadReachesTheSubtitlesEvent() = runTest {
        val backend = LateTracksBackend()
        val player = ComposedPlayer(backend = backend, video = backend)
        val seen = mutableListOf<SubtitlesPayload>()
        player.on(CoreEvents.Subtitles) { seen += it }

        backend.learnTracks(LATE_TRACKS)

        assertEquals(listOf(SubtitlesPayload(LATE_TRACKS)), seen)
    }
}
