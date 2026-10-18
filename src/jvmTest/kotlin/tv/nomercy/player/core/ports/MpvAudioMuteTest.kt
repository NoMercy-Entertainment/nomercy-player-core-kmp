// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class MpvAudioMuteTest {

    @Test
    fun muteSurvivesTheCrossfadeToTheStandbyHandle() {
        val fake = FakeLibMpv()
        val backend = MpvAudioBackend { MpvVideoBackend(fake.library) }

        try {
            runBlocking {
                backend.mute()
                backend.loadSecondary("http://192.0.2.1/next.mp3")
                backend.crossfade(10, CrossfadeCurve.EQUAL_POWER)
            }

            val lastMutePerHandle: Map<String, String> = fake.properties
                .filter { it.name == "mute" }
                .groupBy { it.handle.pointer.toString() }
                .mapValues { (_, sets) -> sets.last().value }
            assertEquals(2, lastMutePerHandle.size, "both handles must have been muted")
            assertEquals(setOf("yes"), lastMutePerHandle.values.toSet())
        } finally {
            backend.release()
        }
    }
}
