// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Which session [NoMercyPlaybackService] answers a browser with, and when it
// registers it. Playback attaches to the session a browser holds, so that one
// is never removed or released while a browser is bound. Main thread only.
internal class BrowseSessionCoordinator<S : Any>(
    private val build: () -> S,
    private val add: (S) -> Unit,
    private val remove: (S) -> Unit,
    private val release: (S) -> Unit,
) {
    var transport: S? = null
        private set

    private var held: S? = null
    private var binds: Int = 0

    val current: S? get() = held

    // True when nothing is left to serve and the service should stop.
    fun onPublished(session: S?): Boolean {
        if (session != null) {
            hold(session)
            transport = session
            return false
        }
        val ended: Boolean = transport != null
        transport = null
        if (!ended || binds > 0) return false
        drop()
        return true
    }

    fun onBind() {
        binds++
    }

    // True when the last browser left and no transport session is playing.
    fun onUnbind(): Boolean {
        if (binds > 0) binds--
        if (binds > 0 || transport != null) return false
        drop()
        return true
    }

    fun sessionForBrowser(): S? {
        if (held == null) {
            val built: S = runCatching(build).getOrNull() ?: return null
            hold(built)
        }
        return held
    }

    fun shutdown() {
        transport = null
        drop()
    }

    // A different session replaces the held one; the same one is already
    // registered and is left alone.
    private fun hold(session: S) {
        val old: S? = held
        if (old === session) return
        if (old != null) {
            runCatching { remove(old) }
            runCatching { release(old) }
        }
        held = session
        runCatching { add(session) }
    }

    private fun drop() {
        val old: S = held ?: return
        held = null
        runCatching { remove(old) }
        runCatching { release(old) }
    }
}
