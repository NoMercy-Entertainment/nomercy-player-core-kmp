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
import kotlin.test.assertNull

class ActionOptionsTest {

    @Test
    fun defaultsMatchTheWebActionOptions() {
        val opts = ActionOptions()

        assertNull(opts.source)
        assertFalse(opts.silent)
        assertFalse(opts.autoplay)
    }

    @Test
    fun theSourceConstantsAreTheWebTokens() {
        assertEquals("user", ActionSource.USER)
        assertEquals("remote", ActionSource.REMOTE)
        assertEquals("plugin", ActionSource.PLUGIN)
    }

    @Test
    fun copyChangesOnlyTheNamedField() {
        val base = ActionOptions()

        val remote = base.copy(source = ActionSource.REMOTE)

        assertEquals("remote", remote.source)
        assertFalse(remote.silent)
        assertNull(base.source)
    }
}
