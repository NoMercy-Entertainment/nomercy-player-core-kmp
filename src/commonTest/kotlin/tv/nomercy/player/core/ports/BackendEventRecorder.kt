// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.test.assertTrue

// The ruler every engine is measured against.
//
// One recorder and one matcher, shared by the ExoPlayer, AVPlayer and VLCJ
// acceptance gates, so "does this engine conform" means the same thing three
// times. Three bespoke assertions would each be right about a different thing.
class BackendEventRecorder {
    private val lock = SynchronizedObject()
    private val order: MutableList<String> = mutableListOf()

    constructor(bus: StringEventBus) {
        for (name in CanonicalBackendEvent.ALL) bus.on(name) { add(name) }
    }

    constructor(backend: MediaBackend) {
        for (name in CanonicalBackendEvent.ALL) backend.on(name) { add(name) }
    }

    // Engine callbacks arrive off their own threads, so recording has to be
    // safe from all of them or the recorded order is fiction.
    private fun add(name: String): Unit = synchronized(lock) {
        order.add(name)
        Unit
    }

    fun names(): List<String> = synchronized(lock) { order.toList() }
}

// Does `required` appear inside `recorded`, in order?
//
// A subsequence, not an exact list. Engines emit their own extras — buffering,
// tracks, rate changes — and requiring an exact match would fail every engine
// for being itself. What has to hold is the relative order of the points that
// mean something to the controllers above.
fun assertCanonicalSubsequence(recorded: List<String>, required: List<String>) {
    var cursor = 0
    for (event in recorded) {
        if (cursor < required.size && event == required[cursor]) cursor++
    }

    assertTrue(
        cursor == required.size,
        buildString {
            appendLine("canonical order not satisfied.")
            appendLine("  required, in this order: $required")
            appendLine("  recorded:                $recorded")
            append("  matched $cursor of ${required.size}")
            if (cursor < required.size) append(" — stopped looking for \"${required[cursor]}\"")
        },
    )
}
