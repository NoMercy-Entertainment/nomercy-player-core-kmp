// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.util.prefs.Preferences

public actual fun defaultStorage(namespace: String): Storage = object : Storage {
    private val prefs: Preferences = Preferences.userRoot().node("nmplayer/" + namespace)

    override suspend fun get(key: String): String? = prefs.get(key, null)

    // Flushed rather than left to the JVM's own schedule: a desktop app killed
    // from the dock does not get a chance to write later.
    override suspend fun set(key: String, value: String) {
        prefs.put(key, value)
        prefs.flush()
    }

    override suspend fun remove(key: String) {
        prefs.remove(key)
        prefs.flush()
    }
}
