// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

public const val BYTES_PER_MB: Int = 1024 * 1024

// The phone default, before any device heuristic has run.
private const val DEFAULT_TARGET_MB = 180

// How much of the stream to hold, and how much to hold before starting.
//
// The defaults are a phone's. A television gets different ones, and the
// difference is not tuning: it is a device that stops responding under a budget
// a phone handles comfortably.
public data class BufferConfig(
    val minBufferMs: Int = 20_000,
    // About a minute and a half, which is what carries playback through a server
    // restart instead of dropping the viewer onto an offline screen.
    val maxBufferMs: Int = 90_000,
    val bufferForPlaybackMs: Int = 5_000,
    // Longer than the initial wait on purpose: after a stall the server is often
    // coming back, and a short wait gives up on it just before it does.
    val bufferForPlaybackAfterRebufferMs: Int = 12_000,
    val backBufferMs: Int = 20_000,
    val retainBackBufferFromKeyframe: Boolean = true,
    val targetBufferBytes: Int = DEFAULT_TARGET_MB * BYTES_PER_MB,
    val isTvDevice: Boolean = false,
)

// What a device can afford to buffer.
//
// Split from the Android lookup so the arithmetic can be tested against a memory
// figure rather than against a device nobody has to hand. That matters here
// because these numbers were not chosen, they were learned: the television
// ceiling is a bedroom TV that stopped responding with the kernel thrashing at
// 45% under a 500MB budget, and the fix was giving televisions a tighter ceiling
// than phones even though neither is flagged low-RAM.
public object BufferBudget {

    public fun forMemory(
        availableMb: Int,
        isTv: Boolean = false,
        isLowRam: Boolean = false,
    ): BufferConfig {
        val budgetMb: Int = when {
            // Below a gigabyte the subtitle layer peaks near 45MB and the codec
            // buffers want their own. Sixty-four is what is left.
            isLowRam -> (availableMb * LOW_RAM_SHARE).toInt().coerceIn(LOW_RAM_FLOOR_MB, LOW_RAM_CEILING_MB)
            isTv -> (availableMb * TV_SHARE).toInt().coerceIn(BUDGET_FLOOR_MB, TV_CEILING_MB)
            else -> (availableMb * PHONE_SHARE).toInt().coerceIn(BUDGET_FLOOR_MB, PHONE_CEILING_MB)
        }

        // The time windows assume a worst case around 30Mbps and scale with what
        // the device can hold: a capable machine keeps more of the stream and
        // rides out a longer outage.
        val scale: Double = (budgetMb / SCALE_PIVOT).coerceIn(MIN_SCALE, MAX_SCALE)

        return BufferConfig(
            minBufferMs = (MIN_BUFFER_MS * scale).toInt().coerceIn(MIN_BUFFER_FLOOR_MS, MIN_BUFFER_CEILING_MS),
            maxBufferMs = (MAX_BUFFER_MS * scale).toInt().coerceIn(MAX_BUFFER_FLOOR_MS, MAX_BUFFER_CEILING_MS),
            // A television starts faster at the cost of initial depth. Adaptive
            // bitrate makes the first seconds the cheapest ones to be short on,
            // and a TV that takes five seconds to show a picture feels broken in
            // a way a phone does not.
            bufferForPlaybackMs = if (isTv) {
                (TV_START_MS * scale).toInt().coerceIn(TV_START_FLOOR_MS, TV_START_CEILING_MS)
            } else {
                (PHONE_START_MS * scale).toInt().coerceIn(PHONE_START_FLOOR_MS, PHONE_START_CEILING_MS)
            },
            bufferForPlaybackAfterRebufferMs = if (isTv) {
                (TV_REBUFFER_MS * scale).toInt().coerceIn(TV_REBUFFER_FLOOR_MS, TV_REBUFFER_CEILING_MS)
            } else {
                (PHONE_REBUFFER_MS * scale).toInt().coerceIn(PHONE_REBUFFER_FLOOR_MS, PHONE_REBUFFER_CEILING_MS)
            },
            backBufferMs = (BACK_BUFFER_MS * scale).toInt().coerceIn(BACK_BUFFER_FLOOR_MS, BACK_BUFFER_CEILING_MS),
            targetBufferBytes = budgetMb * BYTES_PER_MB,
            isTvDevice = isTv,
        )
    }

    private const val LOW_RAM_SHARE = 0.35
    private const val TV_SHARE = 0.30
    private const val PHONE_SHARE = 0.35

    // A hundred megabytes is the budget the unscaled windows below were tuned at.
    private const val SCALE_PIVOT = 100.0

    private const val MIN_BUFFER_MS = 20_000
    private const val MAX_BUFFER_MS = 90_000
    private const val BACK_BUFFER_MS = 20_000
    private const val TV_START_MS = 2_000
    private const val PHONE_START_MS = 5_000
    private const val TV_REBUFFER_MS = 6_000
    private const val PHONE_REBUFFER_MS = 12_000

    // The bounds every window is clamped into. Named rather than inline because
    // each one is a limit that was learned: below a floor the player stalls on
    // every hiccup, above a ceiling it holds memory no stream needs.
    private const val LOW_RAM_FLOOR_MB = 50
    private const val LOW_RAM_CEILING_MB = 64
    private const val BUDGET_FLOOR_MB = 80
    private const val TV_CEILING_MB = 200
    private const val PHONE_CEILING_MB = 350

    private const val MIN_SCALE = 0.5
    private const val MAX_SCALE = 2.0

    private const val MIN_BUFFER_FLOOR_MS = 15_000
    private const val MIN_BUFFER_CEILING_MS = 40_000
    private const val MAX_BUFFER_FLOOR_MS = 50_000
    private const val MAX_BUFFER_CEILING_MS = 180_000
    private const val BACK_BUFFER_FLOOR_MS = 10_000
    private const val BACK_BUFFER_CEILING_MS = 40_000
    private const val TV_START_FLOOR_MS = 1_500
    private const val TV_START_CEILING_MS = 3_000
    private const val PHONE_START_FLOOR_MS = 3_000
    private const val PHONE_START_CEILING_MS = 10_000
    private const val TV_REBUFFER_FLOOR_MS = 4_000
    private const val TV_REBUFFER_CEILING_MS = 10_000
    private const val PHONE_REBUFFER_FLOOR_MS = 8_000
    private const val PHONE_REBUFFER_CEILING_MS = 20_000
}
