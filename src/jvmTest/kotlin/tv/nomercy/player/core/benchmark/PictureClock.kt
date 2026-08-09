// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.benchmark

import java.nio.ByteBuffer
import tv.nomercy.player.core.ports.VideoFrameSink

/**
 * When a picture first arrived, and how many have arrived since.
 *
 * Lit rather than merely delivered. An engine that has opened a stream and not
 * yet decoded it still pushes frames — black ones — and a first-frame stopwatch
 * that stopped on the first delivery would report an instant open for a film
 * that shows the viewer nothing for six seconds. That was the reading that made
 * the desktop engine look fast while it felt slow.
 *
 * Sampled rather than scanned, because this runs on the decoder's own callback
 * and a full pass over a 1080p frame there would be measuring the measurement.
 */
internal class PictureClock : VideoFrameSink {

    @Volatile private var markedAt: Long = System.nanoTime()
    @Volatile private var litAt: Long? = null

    @Volatile var frames: Int = 0
        private set

    @Volatile var width: Int = 0
        private set

    @Volatile var height: Int = 0
        private set

    override fun format(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    override fun display(picture: ByteBuffer) {
        frames += 1
        if (litAt == null && isLit(picture)) litAt = System.nanoTime()
    }

    /** Starts both stopwatches again, which is what a seek needs. */
    fun mark() {
        markedAt = System.nanoTime()
        litAt = null
        frames = 0
    }

    /** Milliseconds from the last mark to the first frame carrying a picture. */
    fun firstPictureMs(): Long? = litAt?.let { at -> (at - markedAt) / NANOS_PER_MS }

    fun framesPerSecond(): Double {
        val elapsed: Double = (System.nanoTime() - markedAt).toDouble() / NANOS_PER_SECOND
        return if (elapsed <= 0.0) 0.0 else frames / elapsed
    }

    private fun isLit(picture: ByteBuffer): Boolean {
        var index = 0
        while (index < picture.limit()) {
            if (picture.get(index) != 0.toByte()) return true
            index += SAMPLE_STEP
        }
        return false
    }

    private companion object {
        // Prime, so the stride never lands on the same channel of every pixel
        // and calls a green frame black.
        const val SAMPLE_STEP: Int = 997
        const val NANOS_PER_MS: Long = 1_000_000
        const val NANOS_PER_SECOND: Double = 1_000_000_000.0
    }
}
