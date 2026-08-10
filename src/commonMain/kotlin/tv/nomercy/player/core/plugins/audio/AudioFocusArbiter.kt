// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.ports.FocusLossKind

// What to do about the speaker, decided without touching one.
//
// Pure so the six focus rules can be proven with no OS underneath them: an
// AudioManager callback or an AVAudioSession notification is one line calling
// in, and every branch after that line is arithmetic a commonTest can drive
// directly. The platform wiring — plugins/audio/AudioFocusPlugin.kt — is the
// one-line seam, not the rules.
public sealed interface FocusAction {

    // Nothing to do. The common answer: a duck that was already at this gain,
    // a loss that arrived while the user had already paused, a grant that
    // found nothing waiting to resume.
    public data object None : FocusAction

    public data object Pause : FocusAction

    // Only ever follows a [Pause] this arbiter itself issued. See
    // [AudioFocusArbiter.onFocusGained] — never resuming a pause the user
    // asked for is the one rule every other rule here exists to protect.
    public data object Resume : FocusAction

    // 1.0 is full volume, so a caller can treat "duck" and "stop ducking" as
    // the same action rather than two — the same reason [ports.SystemTransport]
    // treats setNowPlaying(null) as a state rather than a null special case.
    public data class SetDuckGain(val gain: Float) : FocusAction
}

// The six rules from the plan, decided in one place so they cannot drift
// between Android's AudioManager, Apple's AVAudioSession and whatever a
// desktop turns out to need. Neither of those systems agrees on vocabulary —
// Android has three loss types, Apple has two plus a "should resume" hint —
// which is why the seam below speaks [FocusLossKind] rather than either
// platform's own enum.
//
// One instance per player, not one per process: two players in the same app
// each decide their own pause/resume independently, and only afterwards does
// [ProcessAudioFocusOwner] answer the question this cannot — whether two of
// them should both be trying to make sound at once.
public class AudioFocusArbiter(

    // How quiet "quiet" is during a duckable interruption — a turn-by-turn
    // direction over music, not a call over it. 0.2 is Android's own
    // AudioManager sample-code convention; there is no platform default to
    // defer to instead, because ducking is a policy Android's guide states as
    // a recommendation rather than a number the OS supplies.
    private val duckGain: Float = DEFAULT_DUCK_GAIN,
) {

    // Distinguishes a pause this arbiter caused from one it did not, which is
    // the entire mechanism behind "resume only if we paused for the loss" —
    // P21.15 and P21.19 are the same flag read from two directions.
    private var pausedByArbiter: Boolean = false

    // The other half of that distinction. A loss arriving after the user
    // already paused must not un-pause them when focus returns, and must not
    // duck a track that is not playing either.
    private var pausedByUser: Boolean = false

    public val isPausedByArbiter: Boolean get() = pausedByArbiter

    // The transport told this arbiter the user asked to stop. Distinct from
    // [onFocusLost] telling it the OS did — the same STOPPED state, two
    // different reasons, and only one of them is this arbiter's business to
    // undo later.
    public fun onUserPaused() {
        pausedByUser = true
        pausedByArbiter = false
    }

    public fun onUserResumed() {
        pausedByUser = false
    }

    // The OS says something else needs the speaker.
    public fun onFocusLost(kind: FocusLossKind): FocusAction {
        if (pausedByUser) return FocusAction.None

        return when (kind) {
            // Duck rather than pause, and never flip pausedByArbiter — a
            // track that stays audible was never paused, so there is nothing
            // for a later grant to resume. P21.16.
            FocusLossKind.TRANSIENT_CAN_DUCK -> FocusAction.SetDuckGain(duckGain)

            // Both pause; only TRANSIENT sets the flag that makes the later
            // grant resume it. PERMANENT's caller is expected to abandon
            // focus right after this returns — see AudioFocusPlugin — which
            // means no grant is coming to read the flag regardless, but it is
            // set anyway so the two branches stay one rule instead of two
            // that happen to agree.
            FocusLossKind.TRANSIENT, FocusLossKind.PERMANENT -> {
                pausedByArbiter = true
                FocusAction.Pause
            }
        }
    }

    // The OS says the speaker is this app's again.
    public fun onFocusGained(): FocusAction {
        val wasPausedByArbiter: Boolean = pausedByArbiter
        pausedByArbiter = false

        return when {
            // The rule the whole class exists for: a user's own pause is
            // never this arbiter's to undo, however focus moves around it.
            pausedByUser -> FocusAction.None
            wasPausedByArbiter -> FocusAction.Resume
            // Nothing was paused, which means the last loss (if any) was a
            // duck. Un-duck unconditionally rather than tracking whether one
            // is active — setting 1.0 gain on an unducked track is a no-op a
            // caller does not have to guard against.
            else -> FocusAction.SetDuckGain(FULL_GAIN)
        }
    }

    // A headphone or Bluetooth disconnect. Not a focus event on any platform
    // — Android delivers it as its own broadcast — but the rule is the same
    // shape as a transient loss: pause, and only this arbiter's own pause is
    // eligible to resume later. P21.18.
    public fun onBecomingNoisy(): FocusAction {
        if (pausedByUser) return FocusAction.None
        pausedByArbiter = true
        return FocusAction.Pause
    }

    private companion object {
        const val DEFAULT_DUCK_GAIN = 0.2f
        const val FULL_GAIN = 1.0f
    }
}
