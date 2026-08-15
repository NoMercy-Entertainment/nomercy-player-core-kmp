// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.ports.ExoPlayerVideoBackend
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 44_100
private const val SECONDS = 10

private data class Clip(override val id: String, override val url: String, override val title: String? = null) :
    PlaylistItem

// F19, traced for real: TransportController.seek() emits CoreEvents.Seeked
// right after the fire-and-forget currentTime(resolved) call returns — not
// after the engine's real async settle. BackendBridge.announcePlay/
// announcePause react to the ENGINE's own isPlaying callback and tag it
// ActionSource.BACKEND_SETTLE, which arrives off Media3's own event loop,
// asynchronously, an unknown-until-measured interval later. This device
// test records the actual wall-clock order of both on a real engine, real
// device — the thing the finding said needed a trace before trusting
// fc4790e's "once the seek is fully settled" assumption.
class SeekedOrderingTraceTest {

    @Test
    fun tracesWhetherSeekedFiresBeforeOrAfterTheBackendSettleBlip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val media = File(context.cacheDir, "seeked-order.wav").also { it.writeBytes(silentWav(SECONDS)) }

        val events: MutableList<Pair<String, Long>> = CopyOnWriteArrayList()
        var player: ComposedPlayer? = null

        instrumentation.runOnMainSync {
            val backend = ExoPlayerVideoBackend(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val built = ComposedPlayer(backend = backend, video = backend, scope = scope)
            built.on(CoreEvents.Seeked) { events += "Seeked" to System.nanoTime() }
            built.on(CoreEvents.Play) { payload ->
                events += "Play:${(payload as? PlaySource)?.source}" to System.nanoTime()
            }
            built.on(CoreEvents.Pause) { payload ->
                events += "Pause:${(payload as? PlaySource)?.source}" to System.nanoTime()
            }
            player = built
        }
        val p: ComposedPlayer = requireNotNull(player) { "player was not built on the main thread" }

        try {
            runBlocking {
                p.setup(PlayerConfig())
                p.queue(listOf(Clip("a", media.toURI().toString())))
                p.item("a", autoplay = true)
            }
            // Real playback actually starting, not merely requested.
            Thread.sleep(1_000)

            events.clear()
            runBlocking { p.time(4.0) }
            // Generous: the settle blip's own real arrival time is exactly
            // the unknown this test exists to measure.
            Thread.sleep(1_500)

            val seekedAt: Long? = events.firstOrNull { it.first == "Seeked" }?.second
            val settleAt: Long? = events.firstOrNull { it.first.endsWith(":backend-settle") }?.second

            // Not asserted against a specific order: the finding IS which
            // order this actually runs in on real hardware, and forcing a
            // pass either way would hide the answer rather than report it.
            // Printed so this trace's actual result is on record in the
            // instrumentation log for whoever reads this run.
            println(
                "F19 TRACE: Seeked@${seekedAt ?: "never"} settle@${settleAt ?: "never fired within 1500ms"} " +
                    "seekedBeforeSettle=${if (seekedAt != null && settleAt != null) seekedAt < settleAt else "n/a"} " +
                    "fullSequence=$events",
            )

            assertTrue(seekedAt != null, "CoreEvents.Seeked never fired at all — a seek() call is broken, not just its ordering")
        } finally {
            instrumentation.runOnMainSync { runBlocking { p.dispose() } }
        }
    }

    private fun silentWav(seconds: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val dataSize = byteRate * seconds
        val out = java.io.ByteArrayOutputStream()

        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        fun int16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        ascii("RIFF"); int32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); int32(16); int16(1); int16(channels)
        int32(SAMPLE_RATE); int32(byteRate); int16(channels * bitsPerSample / 8); int16(bitsPerSample)
        ascii("data"); int32(dataSize)
        out.write(ByteArray(dataSize))

        return out.toByteArray()
    }
}
