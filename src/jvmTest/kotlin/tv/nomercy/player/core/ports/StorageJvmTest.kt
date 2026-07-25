// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val VOLUME = "volume"

class StorageJvmTest {

    @Test
    fun theRealPreferencesBackedStoreRoundTrips() = runTest {
        val storage = defaultStorage("test-${System.nanoTime()}")

        storage.set(VOLUME, "abc")
        assertEquals("abc", storage.get(VOLUME))

        storage.remove(VOLUME)
        assertNull(storage.get(VOLUME))
    }

    @Test
    fun twoNamespacesDoNotSeeEachOthersKeys() = runTest {
        val stamp = System.nanoTime()
        val video = defaultStorage("video-$stamp")
        val music = defaultStorage("music-$stamp")

        video.set(VOLUME, "80")
        music.set(VOLUME, "40")

        assertEquals("80", video.get(VOLUME))
        assertEquals("40", music.get(VOLUME))
    }
}
