// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Who is allowed to play.
//
// One holder per key at a time; everybody else queues behind them and is handed
// the lock in turn as it is released. The web player elects across browser tabs
// with the Web Locks API, which the browser also releases when a tab is closed;
// here the scope is whatever the implementation can see, and the honest default
// [InProcessLeaderLock] sees one process.
//
// A port rather than a fixed implementation because the interesting scopes are
// per-platform and none of them is the one core could hard-code: Android has
// audio focus, which is arbitrated by the system across apps, and a desktop app
// wanting one player across two windows needs neither.
public interface LeaderLock {

    // Queue for [key]. [onGranted] runs when this caller holds it, which may be
    // before this call returns if nobody else does.
    public fun request(key: String, onGranted: () -> Unit): LeaderLockRequest
}

// A place in the queue, whether or not it has come up yet.
//
// One verb, because withdrawing from the queue and giving up the lock are the
// same intention — "I am no longer asking" — and a caller that had to know
// which of the two it was doing would have to track the grant itself, which is
// exactly what it asked this object to do.
public interface LeaderLockRequest {
    public fun release()
}

// Leadership among the players in one process.
//
// The default, and enough for the case that actually bites: an app that builds
// a second player before the first has gone away, with both holding audio
// focus and both playing. It cannot see other processes, so it is not a
// substitute for audio focus on a platform that arbitrates centrally — a
// consumer with that needs implements [LeaderLock] over it.
//
// Not thread-safe, and deliberately so rather than by omission. Every caller is
// a plugin reacting to a player event, and those arrive on the player's own
// dispatcher; a lock that took a lock to hand out a lock would be buying
// safety for a contention that does not happen.
public class InProcessLeaderLock : LeaderLock {

    public companion object {
        // What [tv.nomercy.player.core.plugins.leader.TabLeaderPlugin] uses when
        // the consumer names none. Shared on purpose: two players electing on
        // two separate locks would both win.
        public val Shared: InProcessLeaderLock = InProcessLeaderLock()
    }

    private val queues: MutableMap<String, MutableList<Waiter>> = mutableMapOf()

    override fun request(key: String, onGranted: () -> Unit): LeaderLockRequest {
        val waiter = Waiter(onGranted)
        val queue: MutableList<Waiter> = queues.getOrPut(key) { mutableListOf() }
        queue += waiter

        if (queue.first() === waiter) waiter.grant()

        return Request(key, waiter)
    }

    // Whether this caller was the holder or still waiting, the queue moves on
    // the same way. The next in line is granted only if the one leaving was
    // holding — releasing a queued request must not promote anybody.
    private fun release(key: String, waiter: Waiter) {
        val queue: MutableList<Waiter> = queues[key] ?: return
        val wasHolder: Boolean = queue.firstOrNull() === waiter

        queue.remove(waiter)
        if (queue.isEmpty()) {
            queues.remove(key)
            return
        }

        if (wasHolder) queue.first().grant()
    }

    private class Waiter(private val onGranted: () -> Unit) {
        private var granted: Boolean = false

        // Idempotent, because a waiter promoted twice would tell its plugin it
        // had just acquired a lock it already held.
        fun grant() {
            if (granted) return
            granted = true
            onGranted()
        }
    }

    private inner class Request(
        private val key: String,
        private val waiter: Waiter,
    ) : LeaderLockRequest {
        private var done: Boolean = false

        override fun release() {
            if (done) return
            done = true
            this@InProcessLeaderLock.release(key, waiter)
        }
    }
}
