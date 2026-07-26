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

private const val MEDIA_FILE = "ladder.mp4"
private const val TRACKS_TIMEOUT_S = 20L
private const val TALL_RUNG = 360
private const val SHORT_RUNG = 180

// Choosing a rung by descriptor, against a real engine on real hardware.
//
// The unit tests prove the matcher picks the right index and the mapper reads
// the right fields. What neither can show is that the index means what Media3
// thinks it means — the engine's own numbering is the thing this library refuses
// to pass around, and the only way to know the translation is right is to hand a
// real engine a descriptor and read back what it selected.
//
// The media has two video tracks rather than an adaptive manifest, because a
// manifest needs a server and a device test that reached for one would fail on
// the network instead of on the code. Media3 reports them as separate rungs and
// an override picks between them, which is the same path.
class ExoQualitySwitchTest {

    private fun backendWithTracks(): Pair<ExoPlayerVideoBackend, File> {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val media: File = writeLadderClip(File(context.cacheDir, MEDIA_FILE))
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
    fun bothRungsAreReportedAsDescriptors() {
        val (backend, media) = backendWithTracks()
        try {
            val heights: List<Int> = backend.qualityLevels().map { it.height }.sortedDescending()

            assertEquals(listOf(TALL_RUNG, SHORT_RUNG), heights, "the engine did not report both rungs")
        } finally {
            backend.release()
            media.delete()
        }
    }

    @Test
    fun aDescriptorSelectsTheRungItNames() {
        // The claim the whole descriptor rule rests on. If the translation from
        // descriptor to engine index is off by one, this selects the other rung
        // and every quality menu in the library is wrong in the same way.
        val (backend, media) = backendWithTracks()
        try {
            val wanted: QualityLevel = assertNotNull(
                backend.qualityLevels().firstOrNull { it.height == SHORT_RUNG },
                "no ${SHORT_RUNG}p rung to select",
            )

            backend.quality(wanted)
            Thread.sleep(SETTLE_MS)

            assertEquals(SHORT_RUNG, backend.quality()?.height, "the engine selected a rung nobody asked for")
        } finally {
            backend.release()
            media.delete()
        }
    }

    @Test
    fun switchingBackSelectsTheOtherRung() {
        // One selection could be the engine's own default. Two, in opposite
        // directions, cannot be.
        val (backend, media) = backendWithTracks()
        try {
            val short: QualityLevel = assertNotNull(backend.qualityLevels().firstOrNull { it.height == SHORT_RUNG })
            val tall: QualityLevel = assertNotNull(backend.qualityLevels().firstOrNull { it.height == TALL_RUNG })

            backend.quality(short)
            Thread.sleep(SETTLE_MS)
            backend.quality(tall)
            Thread.sleep(SETTLE_MS)

            assertEquals(TALL_RUNG, backend.quality()?.height)
        } finally {
            backend.release()
            media.delete()
        }
    }

    @Test
    fun automaticLetsTheEngineChooseAgain() {
        // Null is a selection rather than a missing one: it hands the rung back
        // to Media3's adaptation, which with two tracks means it stops being
        // pinned to either.
        val (backend, media) = backendWithTracks()
        try {
            val short: QualityLevel = assertNotNull(backend.qualityLevels().firstOrNull { it.height == SHORT_RUNG })
            backend.quality(short)
            Thread.sleep(SETTLE_MS)

            backend.quality(null)
            Thread.sleep(SETTLE_MS)

            assertTrue(
                backend.quality()?.height != SHORT_RUNG,
                "the engine stayed pinned to the rung automatic was supposed to release",
            )
        } finally {
            backend.release()
            media.delete()
        }
    }
}

private const val SETTLE_MS = 1_500L
