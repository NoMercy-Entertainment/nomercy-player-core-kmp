// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.math.abs

// Schedules [task] after [delayMs] and returns the call that cancels it. A seam
// so a test drives time by hand, and the bridge hands it a main-looper Handler.
internal fun interface UpdateScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

// Tells Media3 its state changed, at most once per window.
//
// Android sheds a package's notification updates above about five a second, and
// a shed update can be the one carrying the buttons or the artwork. The first
// request after a quiet spell goes through at once; the rest of a burst
// collapses into one publish when the window closes. The state is pulled from
// the bridge's fields at publish time, so the last change wins.
internal class UpdateCoalescer(
    private val windowMs: Long,
    private val scheduler: UpdateScheduler,
    private val publish: () -> Unit,
) {
    private var windowOpen = false
    private var held = false
    private var cancelWindow: (() -> Unit)? = null

    fun request() {
        if (windowMs <= 0L) {
            publish()
            return
        }
        if (windowOpen) {
            held = true
            return
        }
        publish()
        openWindow()
    }

    fun cancel() {
        cancelWindow?.invoke()
        cancelWindow = null
        windowOpen = false
        held = false
    }

    private fun openWindow() {
        windowOpen = true
        cancelWindow = scheduler.schedule(windowMs) {
            windowOpen = false
            cancelWindow = null
            if (held) {
                held = false
                publish()
                openWindow()
            }
        }
    }
}

// Whether a playback report changes what Media3 would draw.
//
// Media3 extrapolates a playing position from the last one it was given, so a
// report that lands where the extrapolation already is carries nothing new. A
// seek, a pause or a resume moves it off that line.
internal class PlaybackProgress(private val nowMs: () -> Long) {
    private var playing = false
    private var positionMs = 0L
    private var shownAtMs = 0L

    fun report(playing: Boolean, positionMs: Long): Boolean {
        val now = nowMs()
        val expected = if (this.playing) this.positionMs + (now - shownAtMs) else this.positionMs
        if (playing == this.playing && abs(positionMs - expected) <= DRIFT_MS) return false
        this.playing = playing
        this.positionMs = positionMs
        shownAtMs = now
        return true
    }

    // Where playback is now, for a state built from a skipped report.
    fun currentPositionMs(): Long = if (playing) positionMs + (nowMs() - shownAtMs) else positionMs

    fun reset() {
        playing = false
        positionMs = 0L
        shownAtMs = 0L
    }

    private companion object {
        // Below a second a seek is lost in the drift; a skip is ten or more.
        const val DRIFT_MS = 1_000L
    }
}
