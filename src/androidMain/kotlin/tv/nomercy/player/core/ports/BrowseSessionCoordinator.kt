// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Which session [NoMercyPlaybackService] answers a browser with.
//
// A car that opens the app cold binds the service before any player exists. The
// transport's session is the answer once there is one; until then a browse-only
// session stands in, so the car gets a root to ask for. Only one of the two is
// ever registered: the browse session goes out before the transport's goes in.
// Main thread only, like the service that owns it.
internal class BrowseSessionCoordinator<S : Any>(
    private val build: () -> S,
    private val add: (S) -> Unit,
    private val remove: (S) -> Unit,
    private val release: (S) -> Unit,
) {
    var transport: S? = null
        private set

    private var browse: S? = null
    private var binds: Int = 0

    val current: S? get() = transport ?: browse

    // True when nothing is left to serve and the service should stop.
    fun onPublished(session: S?): Boolean {
        if (session == null && transport == null) return false
        transport?.let { old -> if (old !== session) runCatching { remove(old) } }
        transport = session
        if (session != null) {
            dropBrowse()
            runCatching { add(session) }
        } else if (binds > 0) {
            ensureBrowse()
        }
        return session == null && binds == 0
    }

    fun onBind() {
        binds++
    }

    // True when the last browser left and no transport session is playing.
    fun onUnbind(): Boolean {
        if (binds > 0) binds--
        if (binds > 0 || transport != null) return false
        dropBrowse()
        return true
    }

    fun sessionForBrowser(): S? {
        if (transport == null) ensureBrowse()
        return current
    }

    fun shutdown() {
        transport?.let { runCatching { remove(it) } }
        transport = null
        dropBrowse()
    }

    private fun ensureBrowse() {
        if (browse != null) return
        val built: S = runCatching(build).getOrNull() ?: return
        browse = built
        runCatching { add(built) }
    }

    private fun dropBrowse() {
        val old: S = browse ?: return
        browse = null
        runCatching { remove(old) }
        runCatching { release(old) }
    }
}
