// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemverTest {

    @Test
    fun majorMinorAndPatchOrderNumericallyNotAsText() {
        assertTrue(compareSemver("2.0.0", "1.9.9") > 0)
        assertTrue(compareSemver("2.1.0", "2.1.1") < 0)
        assertEquals(0, compareSemver("2.0.0", "2.0.0"))
        // The one string comparison would get wrong.
        assertTrue(compareSemver("2.0.10", "2.0.9") > 0)
    }

    @Test
    fun missingSegmentsReadAsZero() {
        assertEquals(0, compareSemver("2", "2.0.0"))
        assertTrue(compareSemver("2.1", "2.0.5") > 0)
    }

    @Test
    fun aFinishedReleaseOutranksAnyPreReleaseOfIt() {
        assertTrue(compareSemver("2.0.0", "2.0.0-rc.1") > 0)
        assertTrue(compareSemver("2.0.0-rc.1", "2.0.0") < 0)
    }

    @Test
    fun preReleaseIdentifiersFollowSemverPrecedence() {
        assertTrue(compareSemver("2.0.0-rc.1", "2.0.0-rc.2") < 0)
        assertTrue(compareSemver("2.0.0-rc.10", "2.0.0-rc.2") > 0)
        // Numeric identifiers rank below alphanumeric ones.
        assertTrue(compareSemver("2.0.0-1", "2.0.0-alpha") < 0)
        // All shared identifiers equal, so the longer one wins.
        assertTrue(compareSemver("2.0.0-rc.1.1", "2.0.0-rc.1") > 0)
        assertEquals(0, compareSemver("2.0.0-rc.1", "2.0.0-rc.1"))
    }

    @Test
    fun garbageSegmentsDegradeToZeroRatherThanThrowing() {
        // A version string from a third-party plugin is untrusted input, and a
        // crash while validating one is worse than treating it as 0.
        assertEquals(0, compareSemver("x.y.z", "0.0.0"))
        assertTrue(compareSemver("1.0.0", "x") > 0)
    }
}
