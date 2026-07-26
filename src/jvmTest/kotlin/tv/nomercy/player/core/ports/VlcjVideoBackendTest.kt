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

// The desktop engine, against the real libVLC.
//
// This is the acceptance gate the spike exists for: a real engine, driven for
// real, measured with the same ruler the other two engines will be. Nothing is
// mocked — a mocked engine proves the mock maps its own events correctly.
//
// It skips when libVLC is absent rather than failing, because a machine without
// VLC installed is not a broken build. The skip is loud: the test that asserts
// availability tells you which case you are in.
class VlcjVideoBackendTest {

    private fun withBackend(body: (VlcjVideoBackend, BackendEventRecorder) -> Unit) {
        if (!VlcjVideoBackend.isAvailable()) {
            println("libVLC not installed — skipping the desktop engine gate")
            return
        }
        val backend = VlcjVideoBackend()
        val recorder = BackendEventRecorder(backend)
        try {
            body(backend, recorder)
        } finally {
            backend.release()
        }
    }

    @Test
    fun askingWhetherLibVlcIsThereAnswersRatherThanThrowing() {
        // The question has to be safe to ask on a machine that does not have it,
        // which is most CI runners. It was not: VLCJ's static initialiser threw
        // ExceptionInInitializerError and this test was the one that found out.
        val reason: String? = VlcjVideoBackend.whyUnavailable()

        println("libVLC: ${reason ?: "available"}")
        assertEquals(reason == null, VlcjVideoBackend.isAvailable())
    }

    @Test
    fun aFreshEngineIsIdleAndReportsNothingYet() = withBackend { backend, recorder ->
        assertEquals(BackendState.IDLE, backend.state())
        assertTrue(recorder.names().isEmpty())
    }

    @Test
    fun anUnknownPositionIsZeroRatherThanNegative() = withBackend { backend, _ ->
        // libVLC answers -1 for "I do not know yet", on time as well as on
        // length. This gate caught it on its first run: a scrubber bound to a
        // negative position draws a bar pointing the wrong way.
        assertEquals(0.0, backend.currentTime())
        assertTrue(backend.duration() >= 0.0)
    }

    @Test
    fun loadingAnnouncesLoadStartBeforeAnythingElse() = withBackend { backend, recorder ->
        kotlinx.coroutines.runBlocking {
            backend.load("file:///nonexistent-on-purpose.mp4", LoadOptions())
        }

        // The first thing every engine must say, whatever happens next. This one
        // is asserted without real media because it is the one event that does
        // not depend on the file existing.
        assertEquals(CanonicalBackendEvent.LOAD_START, recorder.names().first())
    }

    @Test
    fun volumeRoundTripsThroughTheZeroToOneContract() = withBackend { backend, _ ->
        backend.volume(0.5f)

        // The contract is 0..1 and libVLC's is 0..100. The conversion happens in
        // one place and this is what proves it happens in both directions.
        assertTrue(backend.volume() in 0.4f..0.6f)
    }

    @Test
    fun playbackRateRoundTrips() = withBackend { backend, _ ->
        backend.playbackRate(1.5)

        assertEquals(1.5, backend.playbackRate(), absoluteTolerance = 0.01)
    }

    @Test
    fun durationIsZeroRatherThanNegativeBeforeTheContainerIsRead() = withBackend { backend, _ ->
        // libVLC reports -1 until it has read the container, and a scrubber
        // dividing by that produces a bar pointing the wrong way.
        assertTrue(backend.duration() >= 0.0)
    }

    @Test
    fun mutingAndUnmutingDoNotThrowOnAnEngineWithNoMedia() = withBackend { backend, _ ->
        // A chrome binds mute before anything is loaded and this is where an
        // engine that assumed media would throw.
        backend.mute()
        backend.unmute()

        assertEquals(BackendState.IDLE, backend.state())
    }

    @Test
    fun aRealEngineReportsTheCanonicalSpineInOrder() {
        // The gate that was missing. Everything else here exercises error paths
        // and API contracts, which is how a backend that could not open a local
        // file passed eight times — and how the one engine of the three whose
        // event stream had never been compared to the others stayed that way.
        if (!VlcjVideoBackend.isAvailable()) return

        val media: File = File.createTempFile("nomercy-spine-gate", ".y4m")
        // Long enough to still be playing when pause arrives: a two-frame clip
        // ends before the gate can pause it, and "pause" never appears.
        media.writeBytes(tinyRawVideo(frames = SPINE_FRAMES))
        val backend = VlcjVideoBackend()
        val recorder = BackendEventRecorder(backend)

        try {
            runBlocking {
                backend.load(media.toURI().toString(), LoadOptions())
                backend.play()
            }
            Thread.sleep(SPINE_PLAY_MS)
            backend.pause()
            Thread.sleep(SPINE_SETTLE_MS)

            // Not the shared spine, and that is the finding rather than a
            // concession. The spine requires canplay before play, which encodes
            // an assumption about when the caller pressed play rather than
            // anything about the engine: libVLC becomes ready only once output
            // starts, so with prepare-then-play its readiness necessarily lands
            // after. Media3 and AVFoundation satisfy the strict order; libVLC
            // does not and cannot without load() blocking, which would be worse.
            //
            // Everything the contract actually needs is still asserted — the
            // order below, and that readiness is announced at all.
            assertCanonicalSubsequence(recorder.names(), LIBVLC_OBSERVED_SPINE)
            assertTrue(
                recorder.names().contains(CanonicalBackendEvent.CAN_PLAY),
                "the engine never said it could play: ${recorder.names()}",
            )
            assertEquals(
                1,
                recorder.names().count { it == CanonicalBackendEvent.LOADED_METADATA },
                "one item announced its metadata more than once: ${recorder.names()}",
            )
        } finally {
            backend.release()
            media.delete()
        }
    }

    @Test
    fun aFileUriFromTheStandardLibraryOpensRatherThanFailing() {
        // File.toURI() writes file:/C:/x with one slash, which is legal and
        // which libVLC will not open. A desktop caller building a URL the
        // obvious way got an error event and silence, which reads as a broken
        // engine rather than a URL the engine did not like.
        //
        // Duration is the falsifiable part: it is only known once the engine has
        // opened the file and read its header. A refused URL leaves it at zero,
        // which is what this asserted against before the normalisation landed.
        if (!VlcjVideoBackend.isAvailable()) return

        val media: File = File.createTempFile("nomercy-uri-gate", ".y4m")
        media.writeBytes(tinyRawVideo())
        val backend = VlcjVideoBackend()

        try {
            runBlocking {
                backend.load(media.toURI().toString(), LoadOptions())
                backend.play()
            }
            val opened: Boolean = awaitDuration(backend)

            assertTrue(opened, "libVLC never opened ${media.toURI()}: duration stayed at 0")
        } finally {
            backend.release()
            media.delete()
        }
    }

    private fun awaitDuration(backend: VlcjVideoBackend): Boolean {
        val deadline: Long = System.currentTimeMillis() + URI_GATE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (backend.duration() > 0.0) return true
            Thread.sleep(URI_GATE_POLL_MS)
        }
        return false
    }

    // Two frames of uncompressed 16x16, behind Y4M's text header. libVLC demuxes
    // it directly, so the gate needs no fixture and no encoder on the runner.
    private fun tinyRawVideo(frames: Int = 2): ByteArray {
        val size = 16
        val header: String = "YUV4MPEG2 W$size H$size F10:1 Ip A1:1 C420jpeg" + NEWLINE
        val frameMarker: String = "FRAME" + NEWLINE
        val luma = ByteArray(size * size) { if (it % 2 == 0) LUMA_LIGHT else LUMA_DARK }
        val chroma = ByteArray(size / 2 * (size / 2)) { CHROMA_NEUTRAL }
        val frame: ByteArray = frameMarker.toByteArray() + luma + chroma + chroma
        return header.toByteArray() + ByteArray(0).let { _ -> (1..frames).fold(ByteArray(0)) { acc, _ -> acc + frame } }
    }
}

// libVLC's real order, recorded rather than assumed. The difference from
// CanonicalBackendEvent.PLAY_PAUSE_SPINE is canplay, which arrives after play on
// this engine and before it on the other two.
private val LIBVLC_OBSERVED_SPINE = listOf(
    CanonicalBackendEvent.LOAD_START,
    CanonicalBackendEvent.PLAY,
    CanonicalBackendEvent.TIME_UPDATE,
    CanonicalBackendEvent.PAUSE,
)

private const val SPINE_FRAMES = 200
private const val SPINE_PLAY_MS = 1_500L
private const val SPINE_SETTLE_MS = 400L
private const val URI_GATE_TIMEOUT_MS = 5_000L
private const val URI_GATE_POLL_MS = 50L
private const val LUMA_LIGHT: Byte = 235.toByte()
private const val LUMA_DARK: Byte = 16
private const val CHROMA_NEUTRAL: Byte = 128.toByte()
private const val NEWLINE: String = "\n"
