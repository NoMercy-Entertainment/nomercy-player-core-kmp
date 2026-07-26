// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.TimeState
import tv.nomercy.player.core.ports.TimeRange
import tv.nomercy.player.core.events.ItemEndingSoon
import tv.nomercy.player.core.events.PreventedAction
import tv.nomercy.player.core.events.RateChange
import tv.nomercy.player.core.player.ActionOptions

private const val MIN_RATE = 0.25
private const val MAX_RATE = 2.0
private const val PERCENT = 100.0
private const val DEFAULT_ENDING_SOON_SECONDS = 10.0

private val OFFERED_RATES: List<Double> = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

// Ten seconds, which is what every player's skip button does and what a viewer
// expects without being told.
private const val DEFAULT_SKIP = 10.0

// Where the playhead is, how fast it is moving, and how close to the end.
//
// Seeking goes through the transport rather than being reimplemented here: a
// seek from a scrubber and a seek from `time(seconds)` are the same action and
// must produce the same events in the same order. Two copies of that cycle is
// two places for it to drift.
@Suppress("TooManyFunctions")
public class TimeController(
    private val ctx: PlayerContext,
    private val queue: QueueController,
    private val transport: TransportController,
) {

    public fun time(): Double = ctx.internalCurrentTime

    public suspend fun time(seconds: Double, opts: ActionOptions = ActionOptions()) {
        transport.seek(seconds.coerceAtLeast(0.0), opts)
    }

    public fun duration(): Double = ctx.internalDuration

    public fun buffered(): Double = ctx.backend?.buffered() ?: 0.0

    // Where data actually is.
    //
    // The engine's own ranges when it has them. When it does not, one range from
    // the start to the frontier it does report — which is what a single number
    // means, stated as a range so a caller has one shape to read rather than two
    // depending on which engine it got.
    public fun bufferedRanges(): List<TimeRange> {
        val reported: List<TimeRange> = ctx.backend?.bufferedRanges().orEmpty()
        if (reported.isNotEmpty()) return reported

        val frontier: Double = buffered()
        return if (frontier > 0.0) listOf(TimeRange(0.0, frontier)) else emptyList()
    }

    // Where the playhead may go.
    //
    // A complete file of known duration is seekable end to end, so an engine
    // that reports nothing gets that answer rather than an empty list a chrome
    // would read as "seeking is impossible" and disable its scrubber over.
    //
    // Empty stays empty when the duration is unknown, which is a live stream —
    // and there, empty is the truth: nobody knows where the window starts.
    public fun seekable(): List<TimeRange> {
        val reported: List<TimeRange> = ctx.backend?.seekableRanges().orEmpty()
        if (reported.isNotEmpty()) return reported

        val total: Double = duration()
        return if (total > 0.0) listOf(TimeRange(0.0, total)) else emptyList()
    }

    // How far through, as a number a progress bar can bind to directly. Zero
    // rather than a division by zero before the duration is known.
    public fun percentage(): Double =
        if (ctx.internalDuration <= 0.0) 0.0 else ctx.internalCurrentTime / ctx.internalDuration * PERCENT

    // A scrubber reports where the viewer let go as a fraction of the bar, and
    // it cannot know the duration before metadata arrives. Seeking to a
    // percentage of an unknown duration would jump to zero, so it does nothing.
    public suspend fun seekByPercentage(percent: Double, opts: ActionOptions = ActionOptions()) {
        if (ctx.internalDuration <= 0.0) return
        time(ctx.internalDuration * percent.coerceIn(0.0, PERCENT) / PERCENT, opts)
    }

    // Skip forward and back by a number of seconds.
    //
    // Clamped at both ends rather than allowed to run past them: a rewind that
    // lands at a negative position makes an engine seek to the start on one
    // platform and refuse on another, and a forward skip past the end is a seek
    // into nothing that some engines answer by ending the item.
    public suspend fun forward(seconds: Double = DEFAULT_SKIP, opts: ActionOptions = ActionOptions()) {
        time((time() + seconds).coerceIn(0.0, endOfItem()), opts)
    }

    public suspend fun rewind(seconds: Double = DEFAULT_SKIP, opts: ActionOptions = ActionOptions()) {
        time((time() - seconds).coerceIn(0.0, endOfItem()), opts)
    }

    // The duration when it is known, and the current position when it is not.
    // Clamping against zero would make every forward skip a no-op on a live
    // stream, which is where the duration is most often unknown.
    private fun endOfItem(): Double = if (duration() > 0.0) duration() else time() + DEFAULT_SKIP

    public fun playbackRate(): Double = ctx.playbackRate

    // The rates a UI should offer. Not a limit on what can be set — a consumer
    // driving 1.85 from a slider is fine — just the ones worth a menu item.
    public fun playbackRates(): List<Double> = OFFERED_RATES

    // One consistent snapshot rather than four calls a chrome has to make in
    // the right order.
    //
    // Taken together at one instant: reading time() and duration() separately
    // means a progress bar can render a position from after a seek against a
    // duration from before one, which shows up as a bar that jumps backwards.
    public fun timeData(): TimeState {
        val position: Double = time()
        val total: Double = duration()
        return TimeState(
            time = position,
            duration = total,
            buffered = buffered(),
            // Never negative. A backend reporting a position past a duration it
            // has not refreshed yet is ordinary at the end of an item, and a
            // chrome rendering "-3s remaining" looks broken.
            remaining = (total - position).coerceAtLeast(0.0),
            // Zero rather than a division by zero, which is the whole of a live
            // stream: the duration is unknown and a progress bar has nothing to
            // fill against.
            percentage = if (total > 0.0) (position / total) * PERCENT else 0.0,
        )
    }

    public suspend fun playbackRate(rate: Double, opts: ActionOptions = ActionOptions()) {
        val target: Double = rate.coerceIn(MIN_RATE, MAX_RATE)
        val outcome = ctx.dispatchBefore(CoreEvents.BeforePlaybackRate, RateChange(target))
        if (outcome.prevented) {
            ctx.emit(CoreEvents.PlaybackRatePrevented, PreventedAction(outcome.reason, opts.source))
            return
        }

        val resolved: Double = outcome.data.rate
        ctx.playbackRate = resolved
        ctx.emit(CoreEvents.PlaybackRate, RateChange(resolved))
        ctx.backend?.playbackRate(resolved)
        // What was asked for and what the engine did are announced separately,
        // so a backend that clamps silently is still visible to a listener.
        ctx.emit(CoreEvents.BackendRateChange, RateChange(ctx.backend?.playbackRate() ?: resolved))
    }

    // Fires once per item, not once per time update inside the window. A
    // preloader that started again on every tick would fetch the next item
    // forty times.
    public fun checkItemEndingSoon(
        currentTime: Double,
        duration: Double,
        thresholdSeconds: Double = DEFAULT_ENDING_SOON_SECONDS,
    ) {
        if (ctx.itemEndingSoonEmitted || duration <= 0.0) return

        val remaining: Double = duration - currentTime
        if (remaining > thresholdSeconds || remaining < 0.0) return

        ctx.itemEndingSoonEmitted = true
        ctx.emit(CoreEvents.ItemEndingSoon, ItemEndingSoon(queue.item(), remaining))
    }

    // Loading an item clears the latch on its own; this is for a caller that
    // rewound past the window and wants the warning again.
    public fun resetItemEndingSoonLatch() {
        ctx.itemEndingSoonEmitted = false
    }
}
