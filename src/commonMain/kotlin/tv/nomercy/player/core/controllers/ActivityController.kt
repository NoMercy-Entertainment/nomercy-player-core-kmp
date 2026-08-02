// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.nomercy.player.core.events.ActivityPayload
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.player.PlayState

// Whether the viewer is doing anything, and the countdown that decides they
// have stopped.
//
// The whole autohide behaviour is one event: `activity { active }`. A chrome
// binds to it and shows or fades itself. Keeping the rule here rather than in
// each chrome is what makes a Compose overlay, a tvOS one and a web one agree
// about when controls disappear.
//
// No input listeners live here. On the web the player owns a container element
// and can watch it; native has no such thing, and a library that tried to
// install global input hooks would be fighting whatever the app already does.
// The UI calls bumpActivity() on pointer, touch, key and remote input.
public class ActivityController(
    private val ctx: PlayerContext,
    private val scope: CoroutineScope,
    private val inactivityMs: Long,
) {

    private var active: Boolean = false
    private var countdown: Job? = null

    // How many callers are currently keeping the controls up.
    private var holds: Int = 0
    private var tracking: Boolean = inactivityMs > 0L

    // Whether the built-in countdown owns the activity event.
    //
    // A chrome with a richer state machine of its own — one that keeps controls
    // up while a menu is open, say — takes over by turning this off and becomes
    // the sole emitter. Turning it off also cancels an armed countdown, or the
    // controls it is now responsible for would be hidden out from under it.
    public fun activityTracking(): Boolean = tracking

    public fun activityTracking(enabled: Boolean) {
        tracking = enabled && inactivityMs > 0L
        if (!tracking) cancelCountdown()
    }

    // The viewer did something. Shows the controls and restarts the countdown.
    //
    // Called by the UI for input it can see, and by anything else that counts
    // as intent the UI cannot: a media-session command, a remote, a Connect
    // message from a phone across the room.
    public fun bumpActivity() {
        if (!tracking) return
        setActive(true)
        armCountdown()
    }

    // Called when playback resumes, including from somewhere no UI saw it — a
    // headphone button, a car head unit. Without this, controls shown during a
    // pause stay up for the rest of the film: the countdown only hides while
    // playing, so it expired harmlessly while paused and nothing re-armed it.
    public fun onPlaybackResumed(): Unit = bumpActivity()

    // Keep the controls up until whoever asked says otherwise.
    //
    // A COUNT rather than a flag, and that is the whole point. The nearest thing
    // here was activityTracking(false), which one caller turning back on
    // undoes for every other — a dialog and a menu both open, the dialog closes,
    // and the countdown resumes while the menu is still up. That is a control bar
    // vanishing under an open menu, which gets reported as flakiness rather than
    // as the missing refcount it is.
    public fun holdChrome() {
        holds += 1
        bumpActivity()
    }

    // Floored at zero rather than allowed to go negative, so a double release
    // cannot wedge the chrome permanently hidden behind a hold nobody took.
    public fun releaseChrome() {
        holds = if (holds > 0) holds - 1 else 0
        if (holds == 0) bumpActivity()
    }

    public fun chromeHolds(): Int = holds

    public fun dispose() {
        cancelCountdown()
    }

    private fun armCountdown() {
        cancelCountdown()
        if (inactivityMs <= 0L) return
        countdown = scope.launch {
            delay(inactivityMs)
            countdown = null
            maybeHide()
        }
    }

    private fun cancelCountdown() {
        countdown?.cancel()
        countdown = null
    }

    // Hide only while playback runs.
    //
    // Paused, buffering, stopped or ended, the controls stay up — a viewer who
    // paused is looking at the controls, and fading them is how a player loses
    // an argument with someone trying to read a chapter title. The countdown is
    // deliberately not re-armed here; the next bump arms it.
    private fun maybeHide() {
        if (holds > 0) return
        if (ctx.playState != PlayState.PLAYING) return
        setActive(false)
    }

    // Emitted on the flip only. A chrome re-rendering on every pointer move
    // would repaint sixty times a second for a value that did not change.
    private fun setActive(next: Boolean) {
        if (active == next) return
        active = next
        ctx.emit(CoreEvents.Activity, ActivityPayload(active = next))
    }
}
