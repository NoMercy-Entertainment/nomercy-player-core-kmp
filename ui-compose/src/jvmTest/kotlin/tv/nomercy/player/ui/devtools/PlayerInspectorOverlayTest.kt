// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.ui.devtools

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import tv.nomercy.player.core.devtools.PlayerInspector
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.testing.FakePlayer
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PlayerInspectorOverlayTest {

    // Rendered from a real inspector over a real emitter, not from a list of
    // rows handed in. The overlay's whole job is that what the player announced
    // reaches the screen, and a test that passes it prepared rows would still
    // pass with the subscription deleted.
    @Test
    fun whatThePlayerAnnouncedReachesTheScreen() = runComposeUiTest {
        val player = FakePlayer()
        val inspector = PlayerInspector(player)

        setContent { PlayerInspectorOverlay(inspector) }

        player.emit(CoreEvents.Play, PlaySource("user"))
        waitForIdle()

        onNodeWithText("play").assertIsDisplayed()
        onNodeWithText("PlaySource(source=user)").assertIsDisplayed()
    }

    // The header is the part that says the same event fired four hundred times,
    // which is the thing reading the lines one at a time never tells you.
    @Test
    fun theHeaderCountsWhatTheStreamContains() = runComposeUiTest {
        val player = FakePlayer()
        val inspector = PlayerInspector(player)

        setContent { PlayerInspectorOverlay(inspector) }

        repeat(3) { player.emit(CoreEvents.Play, PlaySource("user")) }
        player.emit(CoreEvents.Pause, PlaySource("user"))
        waitForIdle()

        onNodeWithText("4 event(s)").assertIsDisplayed()
        onNodeWithText("play×3  pause×1").assertIsDisplayed()
    }
}
