// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Choosing a dub by descriptor, against a real engine on real hardware.
//
// The quality gate next door proves the descriptor rule for video rungs. Audio
// goes through a different Media3 path — a group selection rather than a track
// override within a group — so passing there says nothing about here, and the
// audio menu is the one a viewer opens most.
//
// The clip is local rather than an adaptive master. A device test that reached
// for a public multi-audio stream would fail on a runner's network as readily
// as on the code, and a gate that goes red for reasons outside the repository
// is a gate people learn to ignore.
class ExoAudioSwitchTest {

    private fun loadedBackend(name: String): Pair<ExoPlayerVideoBackend, File> {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val media: File = writeDualAudioClip(File(context.cacheDir, name))
        val backend = ExoPlayerVideoBackend(context)
        val ready = CountDownLatch(1)

        backend.on(BackendEvents.LOADED_METADATA) { ready.countDown() }
        runBlocking { backend.load(media.absolutePath, LoadOptions()) }
        assertTrue(
            ready.await(TRACKS_TIMEOUT_S, TimeUnit.SECONDS),
            "the engine never reported metadata for a file it was handed",
        )
        return backend to media
    }

    @Test
    fun bothDubsAreReportedWithTheirLanguages() {
        val (backend, media) = loadedBackend("audio-enumerate.mp4")
        try {
            val tracks: List<AudioTrack> = backend.audioTracks()

            assertEquals(
                EXPECTED_TRACKS,
                tracks.size,
                "the engine reported ${tracks.size} audio tracks for a file with $EXPECTED_TRACKS",
            )

            val languages: List<String> = tracks.map { it.language }
            assertTrue(ENGLISH in languages, "no English track in $languages")
            assertTrue(DUTCH in languages, "no Dutch track in $languages")
        } finally {
            backend.release()
            media.delete()
        }
    }

    @Test
    fun aDescriptorSelectsTheDubItNames() {
        // Away from whatever the engine picked, rather than at a named
        // language. Naming one looked clearer and was not falsifiable: this
        // gate first asked for Dutch and passed with the setter stubbed out,
        // because Media3's default selector matches the device locale and the
        // phone it ran on is nl-NL. It was asserting a default and reading it
        // as a switch.
        //
        // Relative to the current track, the assertion holds on any device in
        // any locale, and there is no arrangement of defaults that satisfies it
        // without the selection actually being made.
        val (backend, media) = loadedBackend("audio-select.mp4")
        try {
            val before: AudioTrack = assertNotNull(backend.audioTrack(), "no starting audio track")
            val other: AudioTrack = assertNotNull(
                backend.audioTracks().firstOrNull { it.id != before.id },
                "only one audio track, so nothing to switch to",
            )

            backend.audioTrack(other)
            Thread.sleep(SETTLE_MS)

            assertEquals(
                other.language,
                backend.audioTrack()?.language,
                "asked for ${other.language}, engine stayed on ${before.language}",
            )
        } finally {
            backend.release()
            media.delete()
        }
    }

    @Test
    fun switchingBackSelectsTheOtherDub() {
        // One selection could be the engine's own default. Two, in opposite
        // directions, cannot both be — whichever dub the device's locale
        // prefers, one of these two moves away from it.
        val (backend, media) = loadedBackend("audio-switch-back.mp4")
        try {
            val dutch: AudioTrack = assertNotNull(backend.audioTracks().firstOrNull { it.language == DUTCH })
            val english: AudioTrack = assertNotNull(backend.audioTracks().firstOrNull { it.language == ENGLISH })

            backend.audioTrack(dutch)
            Thread.sleep(SETTLE_MS)
            assertEquals(DUTCH, backend.audioTrack()?.language, "the first switch never landed")

            backend.audioTrack(english)
            Thread.sleep(SETTLE_MS)

            assertEquals(ENGLISH, backend.audioTrack()?.language, "the engine would not switch back")
        } finally {
            backend.release()
            media.delete()
        }
    }

    @Test
    fun theReportedCurrentTrackIsOneOfTheEnumeratedOnes() {
        // A getter that answers from a field the setter wrote would pass the
        // tests above while reporting a track the menu does not contain. The
        // chrome draws a checkmark next to the row whose id matches, so a
        // current track outside the list is a menu with nothing selected.
        val (backend, media) = loadedBackend("audio-consistency.mp4")
        try {
            val tracks: List<AudioTrack> = backend.audioTracks()
            val current: AudioTrack = assertNotNull(
                backend.audioTrack(),
                "the engine reports no current audio track on a file with $EXPECTED_TRACKS",
            )

            assertTrue(
                current.id in tracks.map { it.id },
                "current track ${current.id} is not in the menu ${tracks.map { it.id }}",
            )
        } finally {
            backend.release()
            media.delete()
        }
    }
}

private const val TRACKS_TIMEOUT_S = 20L
private const val SETTLE_MS = 1_500L
private const val EXPECTED_TRACKS = 2

// Two letters, not three. The container carries ISO 639-2 (eng, nld) and Media3
// normalises to ISO 639-1 before it reaches Format.language, so a gate written
// against what ffmpeg was told to write would never match.
private const val ENGLISH = "en"
private const val DUTCH = "nl"
