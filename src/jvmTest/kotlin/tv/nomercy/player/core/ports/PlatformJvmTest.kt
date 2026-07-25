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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformJvmTest {

    @Test
    fun theDefaultPlatformAnswersEveryQuestionWithoutBeingConfigured() {
        val platform = defaultPlatform()

        assertEquals(NetworkType.UNKNOWN, platform.network.type())
        assertTrue(platform.network.isOnline())
        assertTrue(platform.visibility.isVisible())
    }

    @Test
    fun theDefaultWakeLockSaysItIsUnsupportedRatherThanPretending() {
        val wakeLock = defaultPlatform().wakeLock

        assertFalse(wakeLock.isSupported())
        assertFalse(wakeLock.isHeld())
    }

    @Test
    fun holdingTheNoopWakeLockDoesNotMakeItClaimToBeHeld() = runTest {
        val wakeLock = defaultPlatform().wakeLock

        wakeLock.acquire()

        // Reporting held after a no-op acquire is the lie that would make a
        // chrome draw a control that does nothing.
        assertFalse(wakeLock.isHeld())
        wakeLock.release()
    }

    @Test
    fun anUnmeasuredLinkReportsNullRatherThanAGuess() {
        val network = defaultPlatform().network

        assertNull(network.downlinkMbps())
        assertNull(network.rttMs())
    }

    @Test
    fun theVideoOnlyControllersAreAbsentOnTheDefault() {
        val platform = defaultPlatform()

        assertNull(platform.fullscreen)
        assertNull(platform.pip)
    }

    @Test
    fun subscribingToTheStaticMonitorsGivesADisposableThatIsSafeToCallTwice() {
        val platform = defaultPlatform()

        val network = platform.network.subscribe { }
        val visibility = platform.visibility.subscribe { }
        network.dispose()
        network.dispose()
        visibility.dispose()

        assertTrue(platform.network.isOnline())
    }

    @Test
    fun thePermissiveProbeAllowsEverythingButDoesNotClaimEfficiency() = runTest {
        val capability = defaultPlatform().capabilities.canDecode(DecodeProfile("video/mp4"))

        assertTrue(capability.supported)
        assertFalse(capability.powerEfficient)
    }
}
