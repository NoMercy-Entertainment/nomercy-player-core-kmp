// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

// One player making sound in this process at a time.
//
// [AudioFocusPlugin] answers the OS asking this app to stop; this answers a
// question the OS has no way to know about at all. Video and music are
// separate hosts — a video player instance and a music player instance in the
// same app each request their own OS-level audio focus independently, and
// Android hands both of them a grant, because from the OS's point of view
// they are the same well-behaved app asking twice. Nothing before this
// noticed the second request should have paused the first.
//
// Process-scoped rather than per-plugin — the R4 finding this closes named
// the invariant exactly: "video and music are separate hosts, so the current
// invariant permits two live sessions." A singleton is the one shape that
// invariant can hold across two hosts that otherwise share nothing.
public object ProcessPlaybackOwner {

    // The current holder's own way to stop. Reassigned rather than queued —
    // there is only ever one owner, never a waiting line, because a second
    // claim always means "something new is about to make sound now."
    private var current: (() -> Unit)? = null

    // This caller is about to play. Pauses whoever held the speaker before —
    // arbitration is a pause, never a replaced session left running unheard
    // and unpaused in the background — and remembers how to pause the new
    // holder in turn, for whoever claims it next.
    public fun claim(pauseThisOne: () -> Unit) {
        val previous: (() -> Unit)? = current
        current = pauseThisOne
        if (previous !== pauseThisOne) previous?.invoke()
    }

    // The caller has genuinely stopped — a deliberate stop, not a pause
    // arbitration can undo. Only clears the slot if this caller is still the
    // one holding it: a claim that already lost the slot to someone newer has
    // nothing left here to release.
    public fun release(pauseThisOne: () -> Unit) {
        if (current === pauseThisOne) current = null
    }

    // Test-only. A process-wide singleton that outlived the test before it
    // would fail the next one for a reason that test never touched.
    internal fun resetForTest() {
        current = null
    }
}
