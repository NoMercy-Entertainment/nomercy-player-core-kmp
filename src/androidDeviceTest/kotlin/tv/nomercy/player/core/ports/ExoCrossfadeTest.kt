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
import kotlin.test.assertTrue

private const val FIRST_FILE = "fade-a.mp4"
private const val SECOND_FILE = "fade-b.mp4"
private const val READY_TIMEOUT_S = 20L
private const val FADE_MS = 400L
private const val FULL = 1.0f

// A crossfade between two real engines on real hardware.
//
// The fader's own tests prove the curve; they run against recording sinks and
// cannot show that two ExoPlayers can be audible at once, that the swap leaves
// the right one playing, or that a listener registered before the transition is
// still hearing events after it. Those are the things a device answers.
class ExoCrossfadeTest {

    private fun media(name: String): File {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        return writeLadderClip(File(context.cacheDir, name))
    }

    private fun backend(): ExoPlayerAudioBackend =
        ExoPlayerAudioBackend(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun awaitReady(backend: MediaBackend, media: File) {
        val ready = CountDownLatch(1)
        backend.on(BackendEvents.LOADED_METADATA) { ready.countDown() }
        runBlocking { backend.load(media.absolutePath, LoadOptions()) }
        assertTrue(ready.await(READY_TIMEOUT_S, TimeUnit.SECONDS), "the engine never reported metadata")
    }

    @Test
    fun bothEnginesCanBeAudibleAtOnce() {
        // The claim the whole design rests on. If a second ExoPlayer cannot hold
        // a codec beside the first, there is no crossfade to write.
        val backend = backend()
        val first = media(FIRST_FILE)
        val second = media(SECOND_FILE)

        try {
            awaitReady(backend, first)
            runBlocking {
                backend.play()
                backend.loadSecondary(second.absolutePath)
                backend.primeSecondary()
            }
            backend.secondaryGain(HALF)

            assertEquals(HALF, backend.secondaryGain(), "the second engine would not take a gain")
        } finally {
            backend.disposeSecondary()
            backend.stop()
            first.delete()
            second.delete()
        }
    }

    @Test
    fun theFadeLeavesTheIncomingTrackPlayingAtFullVolume() {
        val backend = backend()
        val first = media(FIRST_FILE)
        val second = media(SECOND_FILE)

        try {
            awaitReady(backend, first)
            runBlocking {
                backend.play()
                backend.loadSecondary(second.absolutePath)
                backend.primeSecondary()
                backend.crossfade(FADE_MS, CrossfadeCurve.EQUAL_POWER)
            }

            // After the swap the backend answers from the engine that faded in.
            assertEquals(FULL, backend.volume(), "the track that faded in is not at full volume")
            assertEquals(0f, backend.secondaryGain(), "the retired engine was left audible")
        } finally {
            backend.disposeSecondary()
            backend.stop()
            first.delete()
            second.delete()
        }
    }

    @Test
    fun aListenerRegisteredBeforeTheFadeStillHearsAfterIt() {
        // The swap moves which engine answers. A chrome that subscribed once at
        // startup would otherwise go silent at the first transition, which looks
        // like a player that died mid-queue.
        val backend = backend()
        val first = media(FIRST_FILE)
        val second = media(SECOND_FILE)
        val heardAfterSwap = CountDownLatch(1)

        try {
            awaitReady(backend, first)
            backend.on(BackendEvents.PAUSE) { heardAfterSwap.countDown() }

            runBlocking {
                backend.play()
                backend.loadSecondary(second.absolutePath)
                backend.primeSecondary()
                backend.crossfade(FADE_MS, CrossfadeCurve.EQUAL_POWER)
            }
            backend.pause()

            assertTrue(
                heardAfterSwap.await(READY_TIMEOUT_S, TimeUnit.SECONDS),
                "the listener stopped hearing the backend after the engines swapped",
            )
        } finally {
            backend.disposeSecondary()
            backend.stop()
            first.delete()
            second.delete()
        }
    }
}

private const val HALF = 0.5f
