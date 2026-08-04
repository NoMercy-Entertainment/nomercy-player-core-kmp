// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.nomercy.player.core.errors.ErrorCode
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.StreamError
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.ports.Clock
import tv.nomercy.player.core.ports.LoadOptions

// What a player does about a failure before it gives up on the item.
//
// The web's `_attachHlsErrorHandler` has this decision tree and this port had
// none of it: every engine failure went straight out as fatal, so a dropped
// packet on a phone that walked past a wall ended the film, and the viewer got
// an error card for something the browser would have swallowed in a second.
//
// The schedule is the whole contribution. Reloading a source is something any
// engine can do; knowing to reload it three times with a widening gap, to treat
// a second decode failure inside five seconds as the real answer, and to forget
// the count once frames are flowing again, is the part that was written down
// once on the web and nowhere here.
public enum class FailureKind {
    // The connection, not the file: a fragment that did not arrive, a manifest
    // that timed out. Worth retrying, because the thing that broke is outside.
    NETWORK,

    // The bytes. A decoder that refused a format, a track that would not
    // initialise. Worth exactly one attempt — a flush and a reload fixes a
    // corrupt buffer and does nothing at all for a stream this device cannot
    // decode, and the difference shows within seconds.
    MEDIA,

    // Everything else fatal. One reload from the current position, then out.
    OTHER,
}

public enum class RecoveryStep {
    // Load it again from where it stopped.
    RELOAD,

    // Out of attempts. The failure is the answer.
    ESCALATE,
}

// Deliberately not a coroutine, a clock or an event emitter: it decides, and the
// bridge that owns those does the deciding's consequences. That is what makes it
// testable without an engine, which is the reason none of this was caught.
public class StreamRecovery(
    private val maxNetworkRetries: Int = MAX_NETWORK_RETRIES,
) {
    private var networkRetries: Int = 0

    // Its own flag rather than a zero timestamp. A monotonic clock starts at
    // zero, so "attempted at 0" and "never attempted" were the same value and the
    // very first decode failure of a run got two attempts instead of one — which
    // is exactly the moment the policy matters most.
    private var mediaAttempted: Boolean = false
    private var mediaAttemptAtMs: Long = 0L

    // The step to take, and the counters move with it — asking twice is two
    // attempts, which is what a caller that asks and then acts means.
    public fun stepFor(kind: FailureKind, nowMs: Long): RecoveryStep = when (kind) {
        FailureKind.NETWORK -> networkStep()
        else -> singleAttemptStep(nowMs)
    }

    private fun networkStep(): RecoveryStep {
        if (networkRetries >= maxNetworkRetries) return RecoveryStep.ESCALATE

        networkRetries += 1
        return RecoveryStep.RELOAD
    }

    // A second one inside the window means the reload did not take. The window
    // rather than a count, because a decode failure an hour later is a different
    // failure and deserves its own attempt.
    //
    // Shared by MEDIA and OTHER because their answer is the same one — try once,
    // believe the repeat. Naming them separately still earns its keep at the call
    // site, where a caller reading NETWORK against MEDIA is reading the actual
    // distinction the schedule turns on.
    private fun singleAttemptStep(nowMs: Long): RecoveryStep {
        if (mediaAttempted && (nowMs - mediaAttemptAtMs) < MEDIA_WINDOW_MS) return RecoveryStep.ESCALATE

        mediaAttempted = true
        mediaAttemptAtMs = nowMs
        return RecoveryStep.RELOAD
    }

    // One second, then two, then four. How long to wait before the attempt the
    // step above just granted.
    public fun retryDelayMs(kind: FailureKind): Long = when (kind) {
        FailureKind.NETWORK -> FIRST_RETRY_DELAY_MS shl (networkRetries - 1).coerceAtLeast(0)
        else -> 0L
    }

    // Frames are flowing, so whatever went wrong is over. Without this a film
    // watched through three bad patches over two hours would exhaust its budget
    // on the third and end, having recovered perfectly from the first two.
    public fun progressed() {
        networkRetries = 0
    }

    // A different item is a different stream, and nothing it inherits from the
    // last one is true of it.
    public fun reset() {
        networkRetries = 0
        mediaAttempted = false
        mediaAttemptAtMs = 0L
    }

    public companion object {
        public const val MAX_NETWORK_RETRIES: Int = 3
        public const val FIRST_RETRY_DELAY_MS: Long = 1_000L
        public const val MEDIA_WINDOW_MS: Long = 5_000L
    }
}

// The policy above, with the things it needs to act: a scope to wait on, a clock
// to measure the window with, and the player whose item is reloaded.
//
// Separate from the bridge because the bridge is already the widest class here —
// it is the translation of an entire engine vocabulary — and recovery is a
// second job with its own state. Given no scope it declines every time, which is
// what a fixture building a bare bridge gets and is the behaviour every platform
// had before this existed.
internal class FailureRecovery(
    private val ctx: PlayerContext,
    private val scope: CoroutineScope?,
    private val clock: Clock,
    private val policy: StreamRecovery = StreamRecovery(),
) {
    // Whether the failure was taken over, so the caller knows not to also report
    // it as the end of the item.
    //
    // Reloading at the position it died on is the one move every engine can make.
    // hls.js has three finer ones — resume the loader, flush and re-append,
    // destroy and rebuild — and which applies is an hls.js detail; what survives
    // the port is the schedule and the giving up, and neither existed here.
    fun tryRecover(engineWord: String?): Boolean {
        val item: PlaylistItem = reloadTarget() ?: return false

        // Asked SECOND, because asking spends an attempt. Ahead of the target it
        // would burn the budget on a player with nothing queued and have none
        // left for the item that eventually is.
        if (!worthRetrying(engineWord)) return false

        // Read BEFORE the reload, which resets the engine's clock. Without this a
        // recovered stream restarts the film, which is worse than the error card
        // it replaced.
        val resumeAtMs: Long = ((ctx.backend?.currentTime() ?: 0.0) * MS_PER_SECOND)
            .toLong()
            .coerceAtLeast(0L)

        // Non-fatal, the contract's word for "something went wrong and the stream
        // is not dead". A chrome can say so; one that says nothing shows the
        // spinner it was already showing, which is also honest.
        ctx.emit(CoreEvents.StreamError, StreamError(details = engineWord ?: "", fatal = false))

        val wait: Long = policy.retryDelayMs(failureKindOf(engineWord))

        scope?.launch {
            if (wait > 0L) delay(wait)
            ctx.loadQuietly(item, LoadOptions(startPositionMs = resumeAtMs, autoplay = true))
        }

        return true
    }

    // What there is to reload, if anything. No scope means nothing can be
    // scheduled, and no current item means there is nothing to schedule.
    private fun reloadTarget(): PlaylistItem? = if (scope == null) null else ctx.queue.current()

    // Frames are flowing again, so the retries that got here are spent budget
    // rather than a running total.
    fun progressed() {
        policy.progressed()
    }

    /**
     * The connection came back.
     *
     * A drop longer than the retry budget spends every attempt against a
     * network that is not there, escalates, and then nothing happens when the
     * network returns — the viewer is left on an error over a working
     * connection, and their only move is to leave the screen and come back.
     * Filed as "the native player does not handle network interruptions
     * gracefully".
     *
     * The budget is forgiven first: those attempts failed for a reason that has
     * since gone away, and holding them against the stream would mean one bad
     * tunnel costs the rest of the film its recovery.
     */
    fun networkRestored() {
        policy.progressed()
        val item: PlaylistItem = reloadTarget() ?: return

        val resumeAtMs: Long = ((ctx.backend?.currentTime() ?: 0.0) * MS_PER_SECOND)
            .toLong()
            .coerceAtLeast(0L)

        scope?.launch {
            ctx.loadQuietly(item, LoadOptions(startPositionMs = resumeAtMs, autoplay = true))
        }
    }

    // A word that parses as one of our own codes is a DECISION, not a hiccup: the
    // HDR refusal exists to stop playback and explain itself, and reloading the
    // item would put the viewer through the same refusal twice before showing it.
    // The engines' own failures are the ones with no code in this scheme, which is
    // the same test the announcement makes to decide what to call them.
    private fun worthRetrying(engineWord: String?): Boolean {
        if (engineWord != null && ErrorCode.parseOrNull(engineWord) != null) return false

        return policy.stepFor(failureKindOf(engineWord), clock.now()) == RecoveryStep.RELOAD
    }
}

private const val MS_PER_SECOND = 1_000.0

// Which of the three a reported failure is.
//
// The engines each have their own vocabulary — Media3 hands over names like
// ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, libVLC and AVFoundation say something
// else entirely — so this reads the words rather than an enum none of them
// share. An unrecognised failure is OTHER, which gets one attempt: the safe end
// of the wrong guess is trying once more, not refusing to.
public fun failureKindOf(reported: String?): FailureKind {
    val word: String = reported?.uppercase() ?: return FailureKind.OTHER

    return when {
        // Asked BEFORE the network words, and that order is the fix.
        //
        // A server that ANSWERED with 404 is not a connection that dropped, but
        // Media3 spells it ERROR_CODE_IO_BAD_HTTP_STATUS — which contains both
        // "_IO_" and "HTTP", so it was read as a transient network failure and
        // reloaded five times against a server that will keep saying 404. That
        // is Stoney's "a server restart locks the video with a 404 and no longer
        // goes to the offline screen": the budget is spent silently on a
        // permanent failure, and the escalation that would show the offline
        // screen arrives a minute late or not at all.
        //
        // A refused status gets the OTHER treatment — one reload from the
        // current position, then out — because a library that moved while the
        // player was paused is worth exactly one attempt and no more.
        REFUSED_WORDS.any { it in word } -> FailureKind.OTHER
        NETWORK_WORDS.any { it in word } -> FailureKind.NETWORK
        MEDIA_WORDS.any { it in word } -> FailureKind.MEDIA
        else -> FailureKind.OTHER
    }
}

// The server answered and said no. Not a connection problem, whatever the
// engine's constant happens to be spelled like.
private val REFUSED_WORDS: List<String> = listOf(
    "BAD_HTTP",
    "HTTP_STATUS",
    "NOT_FOUND",
    "404",
    "403",
    "410",
)

private val NETWORK_WORDS: List<String> = listOf(
    "_IO_",
    "NETWORK",
    "TIMEOUT",
    "CONNECTION",
    "HTTP",
    "UNREACHABLE",
)

private val MEDIA_WORDS: List<String> = listOf(
    "DECOD",
    "DECRYPT",
    "PARSING",
    "MALFORMED",
    "AUDIO_TRACK",
    "CODEC",
    "FORMAT",
)
