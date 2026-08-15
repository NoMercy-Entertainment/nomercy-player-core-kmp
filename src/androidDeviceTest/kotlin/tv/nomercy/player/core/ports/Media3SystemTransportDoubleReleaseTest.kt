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

// F18, driven for real: does Media3's own MediaSession.release() actually
// throw on a second call, on a real device, through the exact race this
// class's constructor and release() describe in their own comments?
//
// Media3SystemTransportTest above never drives this race — it builds and
// releases exactly one instance. This reproduces the real sequence: a
// second instance's constructor pre-emptively releases the first instance's
// raw session (the same call PlaybackForegroundSession makes on a real
// navigate-away-and-back), and then the FIRST instance's own release() runs
// afterward, on the object the second instance already released — the
// straggling-dispose race neither instance's own `released` flag can see
// coming, per Media3SystemTransport's own comments.
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

        onMainThread {
            // The OLD holder — analogous to the screen a viewer is navigating
            // away from.
            first = Media3SystemTransport(context())
        }

        onMainThread {
            // The NEW holder's constructor runs before the old one's dispose
            // gets here — the exact ordering PlaybackForegroundSession's own
            // comment names as confirmed live on a real device.
            second = Media3SystemTransport(context())
        }

        onMainThread {
            // The straggling dispose: this instance's own `released` is still
            // false, because the pre-emptive release above ran on the raw
            // Media3 session directly and never touched this flag.
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
