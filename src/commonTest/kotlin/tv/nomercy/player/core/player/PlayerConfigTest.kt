// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerConfigTest {

    @Test
    fun everyDefaultMatchesTheWebBasePlayerConfig() {
        val config = PlayerConfig()

        assertEquals(100, config.defaultVolume)
        assertEquals(10_000L, config.metricsIntervalMs)
        assertEquals(5_000L, config.progressIntervalMs)
        assertEquals(4_000L, config.inactivityMs)
        assertEquals(10_000L, config.beforeEventTimeoutMs)
        assertEquals(30_000L, config.pluginInitTimeoutMs)
        assertEquals(10.0, config.itemEndingSoonThreshold)
        assertEquals(10.0, config.preloadLeadSeconds)
        assertEquals(3.0, config.crossfadeLeadSeconds)
        assertEquals(3.0, config.crossfadeTailSeconds)
        assertFalse(config.crossfadeEnabled)
        assertFalse(config.pauseWhenHidden)
        assertEquals(WakeLockPolicy.AUTO, config.wakeLock)
        assertEquals(OfflinePolicy.CONTINUE_BUFFERED, config.onOffline)
    }

    @Test
    fun theBeforeEventTimeoutDefaultAgreesWithTheEmittersOwn() {
        // Two places name the same 10s cap. If they drift, a config that looks
        // authoritative would be silently ignored by dispatchBefore.
        assertEquals(
            tv.nomercy.player.core.events.EventEmitter.DEFAULT_BEFORE_TIMEOUT_MS,
            PlayerConfig().beforeEventTimeoutMs,
        )
    }

    @Test
    fun policyTokensMatchTheWebUnions() {
        assertEquals(listOf("auto", "always", "never"), WakeLockPolicy.entries.map { it.token })
        assertEquals(listOf("pause", "continue-buffered", "ignore"), OfflinePolicy.entries.map { it.token })
        assertEquals(OfflinePolicy.IGNORE, OfflinePolicy.fromToken("ignore"))
        assertEquals(WakeLockPolicy.NEVER, WakeLockPolicy.fromToken("never"))
    }

    @Test
    fun copyOverridesOneTunableAndLeavesTheRest() {
        val music = PlayerConfig().copy(crossfadeEnabled = true, defaultVolume = 70)

        assertTrue(music.crossfadeEnabled)
        assertEquals(70, music.defaultVolume)
        assertEquals(10_000L, music.metricsIntervalMs)
    }
}
