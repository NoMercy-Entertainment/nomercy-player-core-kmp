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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformEnvironmentTest {

    @AfterTest
    fun tearDown() = PlatformEnvironment.reset()

    @Test
    fun askingBeforeAnythingIsInstalledFailsWithTheFixInTheMessage() {
        PlatformEnvironment.reset()

        val failure = assertFailsWith<IllegalStateException> { PlatformEnvironment.requireContext() }

        assertTrue(failure.message.orEmpty().contains("PlatformEnvironment.install"))
    }

    @Test
    fun aPortCanAskWhetherThereIsOneWithoutRiskingAThrow() {
        PlatformEnvironment.reset()

        assertFalse(PlatformEnvironment.isInstalled())
    }
}
