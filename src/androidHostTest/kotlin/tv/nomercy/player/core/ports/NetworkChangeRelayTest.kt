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

class NetworkChangeRelayTest {

    private val received: MutableList<NetworkSnapshot> = mutableListOf()
    private var reading: NetworkSnapshot = NetworkSnapshot(online = true, type = NetworkType.WIFI)
    private val relay = NetworkChangeRelay(read = { reading }, fn = { received += it })

    @Test
    fun losingTheNetworkReportsOfflineEvenWhileTheActiveNetworkStillAnswers() {
        relay.onLost()

        assertEquals(listOf(NetworkSnapshot(online = false, type = NetworkType.NONE)), received)
    }

    @Test
    fun aNetworkComingBackReportsWhatIsReadAtThatMoment() {
        reading = NetworkSnapshot(online = true, type = NetworkType.CELLULAR)

        relay.onAvailable()

        assertEquals(listOf(NetworkSnapshot(online = true, type = NetworkType.CELLULAR)), received)
    }

    @Test
    fun aChangedTransportReportsTheNewType() {
        reading = NetworkSnapshot(online = true, type = NetworkType.ETHERNET)

        relay.onCapabilitiesChanged()

        assertEquals(listOf(NetworkSnapshot(online = true, type = NetworkType.ETHERNET)), received)
    }
}
