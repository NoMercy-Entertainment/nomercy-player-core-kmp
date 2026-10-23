// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A car row tap reaches the app only if the session's player says it takes a
// media item: Media3 drops playFromMediaId before onAddMediaItems otherwise.
class TransportCommandsTest {

    @Test
    fun aBareTransportTakesAMediaItemSoACarTapReachesTheApp() {
        val ids = TransportSimpleBasePlayer.commandIdsFor(TransportActions())

        assertTrue(Player.COMMAND_SET_MEDIA_ITEM in ids)
    }

    @Test
    fun aWiredTransportTakesAMediaItemToo() {
        val wired = TransportActions(onPlay = {}, onPause = {}, onSkipBackward = {}, onSkipForward = {})

        assertTrue(Player.COMMAND_SET_MEDIA_ITEM in TransportSimpleBasePlayer.commandIdsFor(wired))
    }

    // CHANGE_MEDIA_ITEMS would send the empty answer to setMediaItems, which
    // this bridge has no queue to take.
    @Test
    fun theTransportDoesNotClaimAQueue() {
        val ids = TransportSimpleBasePlayer.commandIdsFor(TransportActions())

        assertFalse(Player.COMMAND_CHANGE_MEDIA_ITEMS in ids)
    }
}
