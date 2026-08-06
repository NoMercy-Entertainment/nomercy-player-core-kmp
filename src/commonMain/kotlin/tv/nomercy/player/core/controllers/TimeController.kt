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
import tv.nomercy.player.core.ports.Clock
import tv.nomercy.player.core.ports.TimeRange
import tv.nomercy.player.core.ports.defaultClock
import tv.nomercy.player.core.events.ItemEndingSoon
import tv.nomercy.player.core.events.PreventedAction
import tv.nomercy.player.core.events.ProgressPayload
import tv.nomercy.player.core.events.RateChange
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.PlayerConfig
import kotlin.math.absoluteValue

private const val MIN_RATE = 0.25
private const val MAX_RATE = 2.0
private const val PERCENT = 100.0
private const val DEFAULT_ENDING_SOON_SECONDS = 10.0
private const val DEFAULT_PROGRESS_INTERVAL_MS = 5_000L
private const val MILLIS_PER_SECOND = 1_000.0

// How far the playhead may be carried past the engine's last word.
//
// The floor keeps a chatty engine from pinning the carry to nothing between two
// reports that arrived back to back; the ceiling is what an engine that has gone
// quiet is allowed to cost before the playhead simply waits for it.
private const val MIN_CADENCE_MS = 50L
private const val MAX_CADENCE_MS = 1_000L

// Reports do not arrive evenly. Carrying for exactly one measured interval
// leaves the playhead frozen for whatever a late one is late by, which is a
// stall every time the machine is busy — so the carry is allowed two intervals,
// under a hard ceiling that an engine which has stopped speaking cannot pass.
private const val CARRY_INTERVALS = 2L
private const val MAX_CARRY_MS = 750L

// How much of real time the clock may spend correcting itself. At a tenth, a
// quarter-second of drift is paid off across two and a half seconds running 10%
// fast, which no viewer can see and nothing on screen jumps for.
private const val SLEW_FRACTION = 0.1

// Past this the engine has not corrected the clock, it has moved: a seek, an
// advance, a scrubber. Those are supposed to jump.
private const val SEEK_THRESHOLD_SECONDS = 1.0

// Until the engine has reported twice there is nothing to measure. Half a second
// is libVLC's own worst case, which is the engine this exists for.
private const val DEFAULT_CADENCE_MS = 500L

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
    private val clock: Clock = defaultClock(),
) {

    init {
        // Watched here rather than left to a caller, because nothing was calling
        // it. checkItemEndingSoon was written, tested and reachable from nowhere
        // but its own test, so `itemEndingSoon` never fired in a real player on
        // any platform — and everything downstream of it is silently idle:
        // AutoAdvancePlugin's hand-off window, the preload strategy's head start,
        // a chrome's "up next" card. The reference calls the same check from its
        // timeupdate handler, which is this event.
        ctx.on(CoreEvents.Time) { update ->
            checkItemEndingSoon(update.time, update.duration, endingSoonThreshold)
            emitProgress(update.time)
        }
    }

    private var endingSoonThreshold: Double = DEFAULT_ENDING_SOON_SECONDS
    private var progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS
    // Null until the first report. The reference gets the same first-tick-always
    // behaviour from a zero stamp against a wall clock, which only holds because
    // its epoch is 1970 — an injected clock counting from zero would swallow the
    // first window, and a viewer who watched thirty seconds and left would have
    // no position saved at all.
    private var lastProgressEmit: Long? = null

    // Setup hands over the two numbers a host tunes here.
    //
    // Read once rather than looked up per tick: this runs on every position
    // update the engine sends, and both values are fixed for the session.
    public fun configure(config: PlayerConfig) {
        endingSoonThreshold = config.itemEndingSoonThreshold
        progressIntervalMs = config.progressIntervalMs
    }

    // The same numbers `time` carries, at a rate something can persist at.
    //
    // `time` arrives on every engine tick, which is a watch-position write
    // several times a second per viewer. Zero turns it off; the first tick after
    // setup always emits, because the last-emitted stamp starts at zero.
    private fun emitProgress(position: Double) {
        if (progressIntervalMs <= 0L) return

        val now: Long = clock.now()
        val previous: Long? = lastProgressEmit
        if (previous != null && now - previous < progressIntervalMs) return
        lastProgressEmit = now

        val total: Double = duration()
        val percentage: Double = if (total > 0.0) position / total * PERCENT else 0.0
        ctx.emit(CoreEvents.Progress, ProgressPayload(position, total, percentage))
    }

    /**
     * Where playback actually is, asked of the engine rather than remembered.
     *
     * This returned the value cached from the last `timeupdate`, and an engine
     * decides for itself how often that fires — libVLC raises it a few times a
     * second, so the answer was the same number for hundreds of milliseconds
     * and then jumped. Anything following the playhead per frame inherited that
     * cadence: a subtitle overlay drawing at 120Hz still stepped three times a
     * second, because it was drawing the same position over and over.
     *
     * Asking the engine was half of it, and the half that was assumed was
     * wrong: a media element's currentTime is exact whenever it is read, but
     * libVLC answers get_time from its last input update rather than computing
     * it on demand. Measured on the desktop testbed, the subtitle overlay asked
     * sixty times a second and got a new number four times.
     *
     * So the report is carried forward by the time since it arrived. That is
     * not a prediction: it is how far the media has run while the engine was
     * not speaking, which is what a clock does between ticks. It is bounded by
     * the cadence the engine is measured to report at, because an engine that
     * stops speaking — a stall, a released backend — would otherwise walk the
     * playhead into a part of the film nobody is watching. It follows the rate,
     * so half speed carries half as far. It stands still while paused, since a
     * paused engine reports the same position forever.
     *
     * A report that lands BEHIND what was already shown is the usual case, not
     * the exception — the clock is ahead by design and the engine catches up
     * from underneath. Neither snapping to it nor absorbing it whole works:
     * both were measured on the desktop testbed and both jump, the first every
     * report and the second whenever the absorbed error ran out. So the report
     * steers the clock instead, at a tenth of real time, and the clock runs
     * imperceptibly fast or slow until the two agree.
     *
     * The remembered value stays as the fallback, and it is not a formality:
     * between an item being released and the next backend arriving there is no
     * engine to ask, and the last known position is what a progress bar should
     * still be showing.
     *
     * Not while the media is stale. Between an advance and the incoming item's
     * mount the engine still attached is the OUTGOING one, and asking it hands
     * back the previous item's position stamped with the new item's identity —
     * the same trap publishPosition already guards, and reading around it here
     * would reopen it one call later.
     */
    public fun time(): Double {
        if (ctx.mediaIsStale()) return ctx.internalCurrentTime

        val reported: Double = ctx.backend?.currentTime() ?: return ctx.internalCurrentTime
        val now: Long = clock.now()
        anchor(reported, now)

        val answer: Double = steered(now)
        answered = answer
        answeredWhen = now
        return answer
    }

    // Takes a new report and remembers when it arrived.
    //
    // A report is not a correction to apply on the spot, it is the truth this
    // clock converges on. Applying one directly is what made the playhead
    // bounce: it describes where the media was when the engine last looked, so
    // it lands behind what has already been shown every single time.
    private fun anchor(reported: Double, now: Long) {
        if (reported == anchoredAt) return

        if (anchoredAt >= 0.0) reportedEveryMs = (now - anchoredWhen).coerceIn(MIN_CADENCE_MS, MAX_CADENCE_MS)
        anchoredAt = reported
        anchoredWhen = now
    }

    private fun carryCeilingMs(): Long = (reportedEveryMs * CARRY_INTERVALS).coerceAtMost(MAX_CARRY_MS)

    // Where the engine says the media is now: its last report plus the time
    // since it arrived, which is what that report would say if the engine were
    // asked again. Bounded, so an engine that has stopped speaking parks the
    // playhead rather than running away with it.
    private fun engineNow(now: Long): Double {
        // Nothing to add while the media is not running. A paused engine reports
        // the same position forever, and carrying that walks the subtitles into
        // a part of the film nobody is watching.
        if (ctx.playState != PlayState.PLAYING) return anchoredAt

        val sinceMs: Long = (now - anchoredWhen).coerceAtMost(carryCeilingMs())
        return anchoredAt + sinceMs / MILLIS_PER_SECOND * ctx.playbackRate
    }

    // The clock's own answer, moved on by real time and steered toward the
    // engine rather than snapped to it.
    //
    // The steering is the point. Every report lands somewhere the clock is not,
    // and paying that difference in one step is a jump a viewer sees — measured
    // on the desktop testbed at three quarters of a second inside a single
    // frame. Paid a fraction at a time, the clock runs a few percent fast or
    // slow for a moment and nothing on screen moves suddenly at all.
    //
    // A difference too large to steer away is a seek, an advance, a scrubber
    // being dragged. Those are supposed to jump, and they are taken whole.
    private fun steered(now: Long): Double {
        val target: Double = engineNow(now)

        // Not playing is not a clock problem. A paused engine, a stopped one and
        // one that has just been seeked all know exactly where they are, and a
        // remembered answer would override the truth with a stale copy — which
        // is what a seek looked like from here until this branch said so.
        if (ctx.playState != PlayState.PLAYING || answeredWhen < 0L) return target

        val elapsedMs: Long = now - answeredWhen
        val free: Double = answered + elapsedMs / MILLIS_PER_SECOND * ctx.playbackRate
        val error: Double = target - free
        if (error.absoluteValue > SEEK_THRESHOLD_SECONDS) return target

        val allowed: Double = elapsedMs / MILLIS_PER_SECOND * SLEW_FRACTION
        val steered: Double = free + error.coerceIn(-allowed, allowed)

        val total: Double = ctx.internalDuration
        return if (total > 0.0) steered.coerceAtMost(total) else steered
    }

    // The last position the engine actually reported, when it arrived, and how
    // far apart its reports have been.
    //
    // Negative until the first one, because zero is a position a player can
    // legitimately be at and starting there would read the opening frame as a
    // repeat.
    private var anchoredAt: Double = -1.0
    private var anchoredWhen: Long = 0L
    private var reportedEveryMs: Long = DEFAULT_CADENCE_MS

    // The last answer this gave and when. Negative until the first one, because
    // there is nothing to advance from before the clock has answered once.
    private var answered: Double = 0.0
    private var answeredWhen: Long = -1L

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
