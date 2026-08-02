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

// The two defects, through the real engine.
//
// Both were invisible from inside libVLC. Its adaptive demuxer publishes the ONE
// variant it is decoding as a video track and the track struct carries no transfer
// function, so a four-rung stream read as a single SDR rung: the dynamic-range
// decision had nothing to decide about, and nothing knew there was a 4K rung to
// keep out of a small pane. Measured on the desktop before the fix: 3840x1666 at
// 6.5 delivered frames a second in a pane 800 by 372.
//
// The narrowed playlist is the artifact, so that is what this reads. Asserting the
// decision alone would pass on a decision nothing acted on, which is how the
// dynamic-range policy came to be written, tested three ways, and never called.
class VlcjLadderCapTest {

    @Test
    fun theLadderIsReadFromTheManifestRatherThanFromLibVlc() = withBackend { backend ->
        runBlocking { backend.load(SINTEL_MASTER_URL, LoadOptions()) }

        val levels: List<QualityLevel> = backend.qualityLevels()
        assertEquals(4, levels.size, "the desktop still sees one rung: $levels")
        assertEquals(
            2,
            levels.count { it.dynamicRange != DynamicRange.SDR },
            "no rung was identified as HDR, so nothing can be decided: $levels",
        )
    }

    @Test
    fun anSdrDesktopIsNeverHandedAnHdrRendition() = withBackend { backend ->
        val narrowed: String = playlistWrittenBy {
            runBlocking { backend.load(SINTEL_MASTER_URL, LoadOptions()) }
        }

        assertTrue(
            !narrowed.contains("VIDEO-RANGE=PQ"),
            "an HDR rendition reached an SDR desktop: $narrowed",
        )
    }

    @Test
    fun aSmallPaneIsNeverHandedAFourKRendition() = withBackend { backend ->
        backend.surfaceSize(PANE_WIDTH, PANE_HEIGHT)

        val narrowed: String = playlistWrittenBy {
            runBlocking { backend.load(SINTEL_MASTER_URL, LoadOptions()) }
        }

        assertTrue(!narrowed.contains(FOUR_K_RUNG), "a 4K rendition reached a 372px pane: $narrowed")
        assertTrue(narrowed.contains(FULL_HD_RUNG), NOTHING_SURVIVED)
    }

    @Test
    fun anUnmeasuredPaneStillGetsTheDynamicRangeCap() = withBackend { backend ->
        // The size arrives on the first layout pass, which can be after the first
        // load. The dynamic-range cap does not depend on it, and a cold start must
        // not be the one case that shows a washed-out picture.
        val narrowed: String = playlistWrittenBy {
            runBlocking { backend.load(SINTEL_MASTER_URL, LoadOptions()) }
        }

        assertTrue(!narrowed.contains("VIDEO-RANGE=PQ"), "no cap without a measured pane: $narrowed")
        assertTrue(narrowed.contains(FOUR_K_RUNG), "the size cap ran on a pane of zero: $narrowed")
    }

    @Test
    fun aPaneMeasuredAfterTheLoadStillCapsTheLadder() = withBackend { backend ->
        // The order every Compose desktop run actually takes. The surface reports
        // its size once it has been laid out, which is after the item is open, so
        // a ceiling computed only at load is computed from a pane of zero — and
        // the 4K rung stayed in the ladder for the whole playback.
        runBlocking { backend.load(SINTEL_MASTER_URL, LoadOptions()) }

        val narrowed: String = playlistWrittenBy { backend.surfaceSize(PANE_WIDTH, PANE_HEIGHT) }

        assertTrue(!narrowed.contains(FOUR_K_RUNG), "the measured pane did not re-narrow: $narrowed")
        assertTrue(narrowed.contains(FULL_HD_RUNG), NOTHING_SURVIVED)
    }

    @Test
    fun aResizeInsideTheSameRungReopensNothing() = withBackend { backend ->
        backend.surfaceSize(PANE_WIDTH, PANE_HEIGHT)
        runBlocking { backend.load(SINTEL_MASTER_URL, LoadOptions()) }

        val before: Set<String> = spilledPlaylists()
        backend.surfaceSize(PANE_WIDTH + 1, PANE_HEIGHT + 1)

        assertEquals(before, spilledPlaylists(), "a resize that moved no ceiling reopened the item")
    }

    @Test
    fun aLocalFileIsPlayedFromItsOwnPath() = withBackend { backend ->
        // Nothing to narrow and nothing to fetch. The whole path has to be
        // invisible to the desktop's most common item.
        val media: File = File.createTempFile("nomercy-ladder-gate", ".y4m")
        try {
            val before: Set<String> = spilledPlaylists()
            runBlocking { backend.load(media.toURI().toString(), LoadOptions()) }

            assertEquals(emptyList(), backend.qualityLevels(), "a local file reported a manifest ladder")
            assertEquals(before, spilledPlaylists(), "a local file spilled a playlist")
        } finally {
            media.delete()
        }
    }

    // The narrowed playlist [action] wrote.
    //
    // Found by difference against the temp directory rather than by asking the
    // backend, so the assertion is about what libVLC was actually handed and not
    // about a value the backend chose to report.
    private fun playlistWrittenBy(action: () -> Unit): String {
        val before: Set<String> = spilledPlaylists()
        action()
        val written: Set<String> = spilledPlaylists() - before

        assertEquals(1, written.size, "expected exactly one narrowed playlist, got $written")
        return File(written.single()).readText()
    }

    private fun spilledPlaylists(): Set<String> =
        File(System.getProperty("java.io.tmpdir"))
            .listFiles { file: File -> file.name.startsWith("nomercy-ladder-") && file.name.endsWith(".m3u8") }
            .orEmpty()
            .map { it.absolutePath }
            .toSet()

    private fun withBackend(body: (VlcjVideoBackend) -> Unit) {
        if (!VlcjVideoBackend.isAvailable()) {
            println("libVLC not installed — skipping the desktop ladder gate")
            return
        }
        val backend = VlcjVideoBackend()
        backend.masterFetch = FakeHlsMasterFetch(SINTEL_MASTER)
        try {
            body(backend)
        } finally {
            backend.release()
        }
    }
}

// The two rungs the assertions turn on, as the manifest spells them.
private const val FOUR_K_RUNG = "3840x1635"
private const val FULL_HD_RUNG = "1920x818"

private const val NOTHING_SURVIVED = "nothing playable survived"

// The testbed's own pane at the default window size, which is where this was
// measured.
private const val PANE_WIDTH = 800
private const val PANE_HEIGHT = 372
