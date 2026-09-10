// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

// F18 on real hardware: drives the straggling-release race for real.
@UnstableApi
class Media3SystemTransportDoubleReleaseTest {

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun onMainThread(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    @Test
    fun aStragglingReleaseOnAnAlreadyReleasedSessionDoesNotCrashTheProcess() {
        var first: Media3SystemTransport? = null
        var second: Media3SystemTransport? = null
        var thrown: Throwable? = null

        onMainThread { first = Media3SystemTransport(context()) }
        onMainThread { second = Media3SystemTransport(context()) }

        onMainThread {
            try {
                first?.release()
            } catch (error: Throwable) {
                thrown = error
            }
        }

        onMainThread { second?.release() }

        if (thrown != null) {
            throw AssertionError(
                "release() on an already-released session threw and was not caught: $thrown",
                thrown,
            )
        }
    }
}
