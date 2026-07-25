// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Serializable
private data class Prefs(val volume: Int, val muted: Boolean)

@Serializable
private data class WiderPrefs(val volume: Int, val muted: Boolean, val theme: String)

private class FakeStorage : Storage {
    private val map = mutableMapOf<String, String>()
    override suspend fun get(key: String): String? = map[key]
    override suspend fun set(key: String, value: String) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
}

class StorageTest {

    @AfterTest
    fun tearDown() {
        Storage.onStorageDecodeError = null
    }

    @Test
    fun aRawStringRoundTripsAndRemoveClearsIt() = runTest {
        val storage = FakeStorage()
        assertNull(storage.get("k"))

        storage.set("k", "v")
        assertEquals("v", storage.get("k"))

        storage.remove("k")
        assertNull(storage.get("k"))
    }

    @Test
    fun jsonRoundTripsThroughTheThreeRawOperations() = runTest {
        val storage = FakeStorage()

        storage.setJSON("prefs", Prefs(volume = 80, muted = true), Prefs.serializer())

        assertEquals(Prefs(80, true), storage.getJSON("prefs", Prefs.serializer()))
    }

    @Test
    fun anAbsentKeyReadsAsNullRatherThanThrowing() = runTest {
        assertNull(FakeStorage().getJSON("never-written", Prefs.serializer()))
    }

    @Test
    fun unreadableStoredDataFallsBackToTheDefaultAndCanBeReported() = runTest {
        val storage = FakeStorage()
        var reportedKey: String? = null
        Storage.onStorageDecodeError = { key, _ -> reportedKey = key }
        storage.set("prefs", "{ not json")

        // A player that refuses to start because a preference blob changed
        // shape is worse than one that falls back.
        assertNull(storage.getJSON("prefs", Prefs.serializer()))
        assertEquals("prefs", reportedKey)
    }

    @Test
    fun dataWrittenByANewerVersionStillReads() = runTest {
        val storage = FakeStorage()
        storage.setJSON("prefs", WiderPrefs(80, true, "dark"), WiderPrefs.serializer())

        // Stored data outlives the version that wrote it, in both directions.
        assertEquals(Prefs(80, true), storage.getJSON("prefs", Prefs.serializer()))
    }
}
