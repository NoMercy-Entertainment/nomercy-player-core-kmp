// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.ports.Storage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// That what a plugin writes is still there for the NEXT player.
//
// The check nothing had. defaultStorage has had working actuals on Android,
// Apple and the JVM since the port began, with its own tests, and was called by
// nothing outside them — the player defaulted to an in-memory map, so every
// plugin's storage was discarded at exit.
//
// Nothing inside one process could see it. A plugin's save-then-restore test
// passes against a map, the row shows up in the plugin tree with its switch,
// and the only symptom is a viewer relaunching the app to find their subtitle
// language gone. Which is the entire job of the plugin that found this.
//
// So this reads through a SECOND player rather than the one that wrote. That is
// the closest a test in one process can stand to a relaunch, and it is the line
// an in-memory default cannot cross.
class PlayerStorageOutlivesTheProcessTest {

    @Test
    fun whatOnePlayerStoresTheNextPlayerCanRead() = kotlinx.coroutines.test.runTest {
        val key = "outlives-${kotlin.random.Random.nextLong()}"

        ComposedPlayer().rootStorage.set(key, "nld")

        // A different instance, constructed the way a consumer constructs one:
        // no storage argument, so it takes whatever the default is. An
        // InMemoryStorage default makes this null however correct the plugin is.
        val next: Storage = ComposedPlayer().rootStorage
        assertNotNull(next.get(key), "a second player could not read what the first one stored")
        assertEquals("nld", next.get(key), "the value came back changed")

        next.remove(key)
    }

    // Two plugins that both store "enabled" must not overwrite each other, and
    // that separation has to survive the same crossing.
    @Test
    fun twoPluginsKeysStaySeparateAcrossPlayers() = kotlinx.coroutines.test.runTest {
        val stamp = kotlin.random.Random.nextLong()

        // The shape NamespacedStorage writes: the library's namespace, then the
        // plugin's id, then the plugin's own key.
        val equalizer = "nmplayer-equalizer-enabled-$stamp"
        val preferences = "nmplayer-video-preferences-enabled-$stamp"

        val first: Storage = ComposedPlayer().rootStorage
        first.set(equalizer, "true")
        first.set(preferences, "false")

        val next: Storage = ComposedPlayer().rootStorage
        assertEquals("true", next.get(equalizer), "the equaliser's key did not survive")
        assertEquals("false", next.get(preferences), "the preferences key did not survive")

        next.remove(equalizer)
        next.remove(preferences)
    }
}
