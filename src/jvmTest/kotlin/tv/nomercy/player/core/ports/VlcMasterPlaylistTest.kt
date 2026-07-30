// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.io.File
import tv.nomercy.player.core.media.DynamicRange
import tv.nomercy.player.core.media.QualityDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The desktop reading a ladder it could not see, and handing back a narrowed one.
//
// Before this, qualityLevels() on the desktop reported whichever single variant
// libVLC happened to be decoding, and reported it as SDR — so the HDR decision
// had nothing to decide about and a PQ rendition played on an SDR screen.
class VlcMasterPlaylistTest {

    @Test
    fun theLadderIsFourRungsAndTwoOfThemAreHdr() {
        val playlist: VlcMasterPlaylist? = sintel()

        val ladder: List<QualityDescriptor> = playlist?.ladder.orEmpty()
        assertEquals(4, ladder.size, "the ladder did not come back whole: $ladder")
        assertEquals(
            2,
            ladder.count { it.dynamicRange != DynamicRange.Sdr },
            "VIDEO-RANGE=PQ was not read as HDR: $ladder",
        )
    }

    @Test
    fun narrowingDropsTheHdrAndTheFourKRungs() {
        val playlist: VlcMasterPlaylist = sintel() ?: error(NO_FIXTURE)
        val keep: List<QualityDescriptor> = playlist.ladder.filter {
            it.dynamicRange == DynamicRange.Sdr && it.height == SDR_1080_HEIGHT
        }

        val narrowed: String = File(playlist.narrowedTo(keep) ?: error(NOTHING_WRITTEN)).readText()

        assertTrue(narrowed.contains("video_1920x818_SDR"), "the kept rung is missing: $narrowed")
        assertTrue(!narrowed.contains("video_3840x1635"), "a 4K rung survived: $narrowed")
        assertTrue(
            !narrowed.contains("VIDEO-RANGE=PQ"),
            "an HDR rung survived, which is the whole defect: $narrowed",
        )
    }

    @Test
    fun theAudioGroupSurvivesTheNarrowing() {
        // Audio is a separate rendition group, not one of the variants. A narrowing
        // that dropped it would leave a picture with no sound, which is a worse
        // outcome than the one being fixed.
        val playlist: VlcMasterPlaylist = sintel() ?: error(NO_FIXTURE)
        val keep: List<QualityDescriptor> = playlist.ladder.take(1)

        val narrowed: String = File(playlist.narrowedTo(keep) ?: error(NOTHING_WRITTEN)).readText()

        assertTrue(narrowed.contains("audio_eng_aac"), "the audio group was dropped: $narrowed")
    }

    @Test
    fun everyUriInTheNarrowedPlaylistIsAbsolute() {
        // The narrowed copy is opened from a temp directory, and HLS resolves a
        // relative URI against the playlist's own location. Leave one relative and
        // libVLC looks for the variant next to the copy, finds an empty directory,
        // and fails with no picture and no reason.
        val playlist: VlcMasterPlaylist = sintel() ?: error(NO_FIXTURE)
        val keep: List<QualityDescriptor> = playlist.ladder.take(1)

        val narrowed: String = File(playlist.narrowedTo(keep) ?: error(NOTHING_WRITTEN)).readText()

        val relative: List<String> = narrowed.lines().filter { line: String ->
            line.isNotBlank() && !line.startsWith("#") && !line.startsWith("https://")
        }
        assertEquals(emptyList(), relative, "a relative variant URI survived")
        assertTrue(
            narrowed.contains("URI=\"https://raw.githubusercontent.com/"),
            "the audio group's URI stayed relative: $narrowed",
        )
    }

    @Test
    fun keepingEverythingWritesNothing() {
        // Narrowing to the whole ladder is not narrowing. Writing a file for it
        // would move the playlist off its own server for no gain, and every URI
        // rewrite is a chance to break one.
        val playlist: VlcMasterPlaylist = sintel() ?: error(NO_FIXTURE)

        assertNull(playlist.narrowedTo(playlist.ladder))
    }

    @Test
    fun keepingNothingWritesNothing() {
        val playlist: VlcMasterPlaylist = sintel() ?: error(NO_FIXTURE)

        assertNull(playlist.narrowedTo(emptyList()))
    }

    @Test
    fun aUrlThatIsNotAPlaylistIsNeverFetched() {
        // A desktop client's most common item is a local file. Paying for a network
        // read on every one of them to learn it has no ladder is a cost that shows
        // up as a slow first frame.
        val fetch = FakeHlsMasterFetch(SINTEL_MASTER)

        val playlist: VlcMasterPlaylist? =
            VlcMasterPlaylist.of("file:///C:/films/sintel.mkv", emptyMap(), fetch)

        assertNull(playlist)
        assertEquals(emptyList(), fetch.asked, "a local file was fetched over HTTP")
    }

    @Test
    fun aQueryStringDoesNotHideThePlaylist() {
        // A signed URL carries its token in the query. Matching the extension
        // against the whole string would skip every authenticated stream.
        val fetch = FakeHlsMasterFetch(SINTEL_MASTER)

        val playlist: VlcMasterPlaylist? =
            VlcMasterPlaylist.of("$SINTEL_MASTER_URL?token=abc", emptyMap(), fetch)

        assertEquals(4, playlist?.ladder?.size)
    }

    @Test
    fun theLoadsHeadersReachTheFetch() {
        // The manifest behind a NoMercy server needs the same Authorization the
        // engine will send. Reading it without one answers 401 and the ladder goes
        // back to being invisible.
        val fetch = FakeHlsMasterFetch(SINTEL_MASTER)

        VlcMasterPlaylist.of(SINTEL_MASTER_URL, mapOf("Authorization" to "Bearer x"), fetch)

        assertEquals("Bearer x", fetch.sawHeaders["Authorization"])
    }

    @Test
    fun anUnreadableManifestIsNotAnError() {
        // The item still plays, unnarrowed, which is exactly what happened before
        // any of this existed. A load that failed because a side-channel read failed
        // would be a regression dressed as a fix.
        assertNull(VlcMasterPlaylist.of(SINTEL_MASTER_URL, emptyMap(), FakeHlsMasterFetch(null)))
    }

    @Test
    fun somethingThatIsNotAPlaylistAtAllIsRefused() {
        val notAPlaylist = FakeHlsMasterFetch("<html><body>404</body></html>")

        assertNull(VlcMasterPlaylist.of(SINTEL_MASTER_URL, emptyMap(), notAPlaylist))
    }

    private fun sintel(): VlcMasterPlaylist? =
        VlcMasterPlaylist.of(SINTEL_MASTER_URL, emptyMap(), FakeHlsMasterFetch(SINTEL_MASTER))
}

private const val SDR_1080_HEIGHT = 818
private const val NOTHING_WRITTEN = "nothing was written"
private const val NO_FIXTURE = "the fixture did not parse"
