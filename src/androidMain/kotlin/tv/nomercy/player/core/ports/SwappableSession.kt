// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// One session whose player changes. A car follows only the session it was first
// answered with; playback attaches to it and detaches when it ends.
// Main thread only, like the session it wraps.
internal class SwappableSession<S : Any, P : Any>(
    private val newIdle: () -> P,
    private val build: (P) -> S,
    private val setPlayer: (S, P) -> Unit,
    private val dispose: (S) -> Unit,
) {
    private var session: S? = null
    private var idle: P? = null

    var attached: P? = null
        private set

    fun obtain(): S {
        session?.let { return it }
        val player: P = newIdle()
        val built: S = build(player)
        idle = player
        session = built
        return built
    }

    fun attach(player: P): S {
        val held: S = obtain()
        if (attached !== player) {
            setPlayer(held, player)
            attached = player
        }
        return held
    }

    // False for a player that is no longer the attached one: a superseded
    // transport releasing late must not take the session from its successor.
    fun detach(player: P): Boolean {
        if (attached !== player) return false
        attached = null
        val held: S? = session
        val standIn: P? = idle
        if (held != null && standIn != null) runCatching { setPlayer(held, standIn) }
        return true
    }

    fun isAttached(player: P): Boolean = attached === player

    // True when the session was disposed. Never while a player is attached.
    fun releaseIfIdle(): Boolean {
        if (attached != null) return false
        val held: S = session ?: return false
        session = null
        idle = null
        runCatching { dispose(held) }
        return true
    }
}
