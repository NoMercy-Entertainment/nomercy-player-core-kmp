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
import kotlin.test.assertFailsWith

class StateEnumsTest {

    @Test
    fun lifecycleTokensMatchTheWebStrings() {
        assertEquals(
            listOf(
                "idle", "setup", "ready", "loading", "starting", "playing", "paused",
                "buffering", "seeking", "ended", "stopped", "disposing", "disposed",
            ),
            PlayerPhase.entries.map { it.token },
        )
        assertEquals(
            listOf("idle", "loading", "playing", "paused", "stopped", "error"),
            PlayState.entries.map { it.token },
        )
        assertEquals(listOf("not-setup", "setup", "ready", "disposed"), SetupState.entries.map { it.token })
    }

    @Test
    fun settingUpCarriesTheWebTokenNotItsKotlinName() {
        // The one place the Kotlin name and the wire string deliberately differ.
        // Anything serialising state must read token, never name.
        assertEquals("setup", SetupState.SETTING_UP.token)
    }

    @Test
    fun derivedTokensMatchTheWebStrings() {
        assertEquals(listOf("unmuted", "muted"), VolumeState.entries.map { it.token })
        assertEquals(listOf("off", "all", "one"), RepeatState.entries.map { it.token })
        assertEquals(listOf("off", "on"), ShuffleState.entries.map { it.token })
        assertEquals(listOf("idle", "loading", "seeking", "stalled"), BufferState.entries.map { it.token })
        assertEquals(listOf("online", "offline", "slow"), NetworkState.entries.map { it.token })
        assertEquals(
            listOf("unavailable", "available", "connecting", "connected", "disconnected"),
            CastState.entries.map { it.token },
        )
        assertEquals(listOf("auto", "manual"), QualityState.entries.map { it.token })
        assertEquals(listOf("default", "manual"), AudioTrackState.entries.map { it.token })
        assertEquals(listOf("visible", "hidden"), VisibilityState.entries.map { it.token })
    }

    @Test
    fun everyTokenRoundTripsThroughFromToken() {
        val roundTrips: List<Pair<List<TokenEnum>, (String) -> TokenEnum>> = listOf(
            PlayerPhase.entries to PlayerPhase::fromToken,
            PlayState.entries to PlayState::fromToken,
            SetupState.entries to SetupState::fromToken,
            VolumeState.entries to VolumeState::fromToken,
            RepeatState.entries to RepeatState::fromToken,
            ShuffleState.entries to ShuffleState::fromToken,
            BufferState.entries to BufferState::fromToken,
            NetworkState.entries to NetworkState::fromToken,
            CastState.entries to CastState::fromToken,
            QualityState.entries to QualityState::fromToken,
            AudioTrackState.entries to AudioTrackState::fromToken,
            VisibilityState.entries to VisibilityState::fromToken,
        )

        // Adding a state enum without adding it here is the failure this cannot
        // catch, so the count is asserted against the list length below.
        assertEquals(12, roundTrips.size)
        roundTrips.forEach { (entries, fromToken) ->
            entries.forEach { entry -> assertEquals(entry, fromToken(entry.token)) }
        }
    }

    @Test
    fun anUnknownTokenIsRejectedAndTheMessageNamesTheType() {
        val failure = assertFailsWith<IllegalArgumentException> { PlayState.fromToken("nope") }
        assertEquals("unknown PlayState token: nope", failure.message)
    }
}
