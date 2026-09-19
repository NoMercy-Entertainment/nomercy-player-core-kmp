// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Media3SystemTransport.release() used to leave this executor's single,
// non-daemon thread running forever — confirmed live as a growing thread
// count that OOMed a 32-bit Android TV (pthread_create failing) after 4.4
// days of re-entering a watch screen. Every construction leaked one live
// thread because release() never called shutdown().
class ArtworkLoaderExecutorTest {

    @Test
    fun releaseShutsDownTheExecutorSoTheThreadDoesNotLeak() {
        val loader = ArtworkLoaderExecutor()

        assertFalse(loader.service.isShutdown, "a fresh executor should not already be shut down")

        loader.release()

        assertTrue(loader.service.isShutdown, "release() must shut down the executor or its thread leaks")
    }

    @Test
    fun releaseIsIdempotent() {
        val loader = ArtworkLoaderExecutor()

        loader.release()
        loader.release()

        assertTrue(loader.service.isShutdown)
    }
}
