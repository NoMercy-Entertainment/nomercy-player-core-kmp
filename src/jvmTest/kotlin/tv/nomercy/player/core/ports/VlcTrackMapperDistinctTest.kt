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

// One rendition offered once, however many ways the demuxer can reach it.
//
// The Sintel master playlist declares a single EXT-X-MEDIA:TYPE=AUDIO — one
// English AAC rendition, referenced by all four variants. libVLC hands back TWO
// tracks for it, and the testbed named them:
//
//     engine reports: audio [audio_aac English aac, audio_aac English aac]
//
// Identical in language, label, codec and channel count, differing only in the
// id libVLC assigned. The browser offers no audio button for this film at all,
// because hls.js counts the renditions; the desktop player offered a menu with
// the same row twice.
//
// So identity is the DESCRIPTOR, not the id — the rule this codebase already
// applies everywhere else. Two rows a viewer cannot tell apart are not a choice.
class VlcTrackMapperDistinctTest {

    // The label libVLC hands back for the Sintel manifest's only rendition,
    // named once because the duplicate is the point of three of these tests.
    private val sintelAudio = "audio_aac English aac"

    // The label a real alternative carries. Named for the same reason the one
    // above is: what is being asserted is that two rows reading the SAME word
    // collapse and two reading different words do not.
    private val english = "English"

    /** The only subtitle format in play here, named so the rows read as rows. */
    private val webvtt = "webvtt"

    @Test
    fun oneRenditionReachedTwiceIsOfferedOnce() {
        val tracks: List<AudioTrack> = listOf(
            AudioTrack(id = "1", language = "eng", label = sintelAudio, channels = 2, codec = "aac"),
            AudioTrack(id = "6", language = "eng", label = sintelAudio, channels = 2, codec = "aac"),
        )

        val distinct: List<AudioTrack> = VlcTrackMapper.distinctAudio(tracks)

        assertEquals(1, distinct.size, "the same rendition was offered twice")

        // The FIRST one, because that is the id libVLC reports as selected — a
        // menu that keeps the later duplicate shows nothing as active.
        assertEquals("1", distinct.single().id)
    }

    @Test
    fun twoRealTracksBothSurvive() {
        val tracks: List<AudioTrack> = listOf(
            AudioTrack(id = "1", language = "eng", label = english, channels = 2, codec = "aac"),
            AudioTrack(id = "2", language = "nld", label = "Nederlands", channels = 2, codec = "aac"),
            // Same language, different mix — named apart in the manifest, which
            // is how a viewer tells them apart in the menu.
            AudioTrack(id = "3", language = "eng", label = "English 5.1", channels = 6, codec = "ac3"),
        )

        assertEquals(3, VlcTrackMapper.distinctAudio(tracks).size, "a real choice was collapsed")
    }

    // The run that got through the first version of this.
    //
    // libVLC reports the channel count once it has opened the elementary stream,
    // so the same rendition reads 1ch on one pass and 2ch on the next. Keying on
    // it made the duplicate appear and disappear with timing: the desktop bar
    // drew an audio button in one launch and not the next, off the same film.
    @Test
    fun theSameRenditionCountedBeforeAndAfterItsChannelsAreKnown() {
        val tracks: List<AudioTrack> = listOf(
            AudioTrack(id = "0", language = "eng", label = sintelAudio, channels = 1, codec = "mp4a"),
            AudioTrack(id = "5", language = "eng", label = sintelAudio, channels = 2, codec = "mp4a"),
        )

        assertEquals(1, VlcTrackMapper.distinctAudio(tracks).size, "a channel count made one track look like two")
    }

    @Test
    fun subtitlesFollowTheSameRule() {
        val tracks: List<SubtitleTrack> = listOf(
            SubtitleTrack(id = "4", language = "eng", label = english, format = webvtt),
            SubtitleTrack(id = "9", language = "eng", label = english, format = webvtt),
            SubtitleTrack(id = "5", language = "eng", label = "English SDH", format = webvtt),
        )

        assertEquals(2, VlcTrackMapper.distinctSubtitles(tracks).size)
    }
}
