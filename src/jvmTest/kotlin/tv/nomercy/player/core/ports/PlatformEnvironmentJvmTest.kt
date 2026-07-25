// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Constructing a PlatformContext needs a platform actual, so the round-trip can
// only be asserted from a platform source set.
class PlatformEnvironmentJvmTest {

    @AfterTest
    fun tearDown() = PlatformEnvironment.reset()

    @Test
    fun anInstalledContextComesBackAsTheSameInstance() {
        val context = PlatformContext()

        PlatformEnvironment.install(context)

        assertTrue(PlatformEnvironment.isInstalled())
        assertSame(context, PlatformEnvironment.requireContext())
    }

    @Test
    fun installingAgainReplacesRatherThanAccumulates() {
        PlatformEnvironment.install(PlatformContext())
        val second = PlatformContext()

        PlatformEnvironment.install(second)

        assertSame(second, PlatformEnvironment.requireContext())
    }

    @Test
    fun resetLeavesTheHolderEmptyAgain() {
        PlatformEnvironment.install(PlatformContext())

        PlatformEnvironment.reset()

        assertFalse(PlatformEnvironment.isInstalled())
    }
}
