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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The desktop engine's track surface, against the real libVLC.
//
// VlcTrackMapperTest already checks the mapping functions, but it feeds them
// values a test wrote. That proves the arithmetic and nothing about whether
// libVLC is asked the right questions: a mapper wired to a method that answers
// null on real media passes every one of those tests and returns an empty menu
// in the app.
//
// So this drives a real engine over a real file and reads the menu back. The
// fixture carries two audio tracks in different languages because one track
// cannot tell a working switch from a no-op — with a single track, "select the
// second one" and "do nothing" produce identical readings.
class VlcjTrackSurfaceTest {

    @Test
    fun aRealFilesAudioTracksAreEnumeratedWithTheirLanguages() = withFixture { backend ->
        val tracks: List<AudioTrack> = awaitAudioTracks(backend)

        // Two, because the file has two. An engine that reported one would mean
        // the demuxer stopped at the first audio stream, and the app would show
        // a menu missing every alternate dub.
        assertEquals(2, tracks.size, "libVLC enumerated ${tracks.size} audio tracks, expected 2")

        // The languages are the falsifiable part. ids are positional and would
        // look right even if the mapper read the wrong field; these came from
        // the container's metadata and could only arrive by being read.
        val languages: List<String> = tracks.map { it.language }
        assertTrue("eng" in languages, "no English track in $languages")
        assertTrue("nld" in languages, "no Dutch track in $languages")
    }

    @Test
    fun everyEnumeratedTrackIsDistinctAndLabelled() = withFixture { backend ->
        val tracks: List<AudioTrack> = awaitAudioTracks(backend)

        // A menu with two rows that carry the same id cannot be selected from:
        // whichever the viewer picks, the engine is handed the same number.
        assertEquals(tracks.size, tracks.map { it.id }.toSet().size, "duplicate track ids in $tracks")
        assertTrue(tracks.all { it.label.isNotBlank() }, "a track has no label to draw: $tracks")
        assertTrue(tracks.all { it.channels >= 1 }, "a track claims no channels: $tracks")
    }

    @Test
    fun selectingTheSecondTrackIsHonouredByTheEngine() = withFixture { backend ->
        val tracks: List<AudioTrack> = awaitAudioTracks(backend)
        val wanted: AudioTrack = tracks[1]

        backend.audioTrack(wanted)

        // Read back from the engine rather than from a field the setter wrote.
        // The whole point is whether libVLC took it; a backend that remembered
        // the request and answered from memory would be indistinguishable from
        // a working one until a viewer noticed the audio never changed.
        val settled: Boolean = awaitCondition { backend.audioTrack()?.id == wanted.id }
        val current: AudioTrack? = backend.audioTrack()

        assertNotNull(current, "the engine reports no current audio track after a switch")
        assertTrue(settled, "asked for ${wanted.id}/${wanted.language}, engine stayed on ${current.id}")
        assertEquals(wanted.language, current.language)
    }

    @Test
    fun aFileWithNoSubtitlesReportsAnEmptyMenuRatherThanAPhantom() = withFixture { backend ->
        awaitAudioTracks(backend)

        // The fixture has no text streams. An engine that invented one — libVLC
        // exposes a "Disable" pseudo-track in some builds — would draw a
        // subtitle menu with an entry that selects nothing.
        assertTrue(
            backend.subtitleTracks().isEmpty(),
            "libVLC reported subtitles on a file with none: ${backend.subtitleTracks()}",
        )
        assertEquals(null, backend.subtitleTrack())
    }

    // Loads the fixture and gets the engine as far as playing, which is where
    // libVLC populates the track list: media().info() is null until the demuxer
    // has read the container, so asking any earlier answers nothing regardless
    // of what the file holds.
    private fun withFixture(body: (VlcjVideoBackend) -> Unit) {
        if (!VlcjVideoBackend.isAvailable()) {
            println("libVLC not installed — skipping the desktop track-surface gate")
            return
        }

        val media: File = extractFixture()
        val backend = VlcjVideoBackend()
        try {
            runBlocking {
                backend.load(media.toURI().toString(), LoadOptions())
                backend.play()
            }
            body(backend)
        } finally {
            backend.release()
            media.delete()
        }
    }

    // libVLC reads from a path, so the vendored resource has to land on disk.
    private fun extractFixture(): File {
        val bytes: ByteArray = checkNotNull(javaClass.getResourceAsStream(FIXTURE)) {
            "$FIXTURE is missing from the test resources"
        }.use { it.readBytes() }

        val file: File = File.createTempFile("nomercy-track-surface", ".mkv")
        file.writeBytes(bytes)
        return file
    }

    private fun awaitAudioTracks(backend: VlcjVideoBackend): List<AudioTrack> {
        // Both, because they do not arrive together. libVLC publishes the track
        // list and the container length from different points in its open
        // sequence, and waiting only for the tracks caught the length still at
        // zero often enough to fail one run in four.
        awaitCondition {
            backend.audioTracks().size >= EXPECTED_AUDIO_TRACKS && backend.duration() > 0.0
        }
        val tracks: List<AudioTrack> = backend.audioTracks()

        assertTrue(
            tracks.isNotEmpty(),
            "libVLC never enumerated an audio track — the container was not parsed within ${GATE_TIMEOUT_MS}ms",
        )

        // The fixture has to outlive the longest wait in this file, and the
        // first version did not. At three seconds against an eight second
        // timeout, any wait that genuinely had to wait outlived the media:
        // libVLC tore the item down at the end and every getter went null, so a
        // failing switch reported "no current audio track" instead of "the
        // engine stayed on the one it had". A misleading diagnosis is worse
        // than a red test, because it sends the next reader at the wrong code.
        assertTrue(
            backend.duration() > GATE_TIMEOUT_MS / MS_PER_SECOND,
            "the fixture is ${backend.duration()}s but a gate here waits up to " +
                "${GATE_TIMEOUT_MS / MS_PER_SECOND}s — it would be judging a finished engine",
        )
        return tracks
    }

    private fun awaitCondition(predicate: () -> Boolean): Boolean {
        val deadline: Long = System.currentTimeMillis() + GATE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(GATE_POLL_MS)
        }
        return false
    }
}

private const val FIXTURE = "/fixtures/two-audio.mkv"
private const val EXPECTED_AUDIO_TRACKS = 2
private const val GATE_TIMEOUT_MS = 8_000L
private const val GATE_POLL_MS = 50L
private const val MS_PER_SECOND = 1_000L
