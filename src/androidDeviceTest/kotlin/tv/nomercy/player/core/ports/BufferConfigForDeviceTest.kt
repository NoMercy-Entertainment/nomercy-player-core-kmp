// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// What the budget arithmetic is actually fed on this device.
//
// BufferBudgetTest covers the arithmetic and can run anywhere, because it is
// handed numbers. This covers the half that cannot be faked: whether the
// numbers it gets are the device's real ones. A form-factor check that silently
// answers false is invisible in a unit test and costs a television its whole
// TV-specific configuration.
class BufferConfigForDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theFormFactorMatchesWhatThePlatformSays() {
        // Against the platform rather than against a constant, so the same test
        // is meaningful on a phone and on a television. Hard-coding either
        // answer would make it pass on one device by ignoring the other.
        val leanback: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        assertEquals(
            leanback,
            bufferConfigForDevice(context).isTvDevice,
            "the budget disagrees with the platform about whether this is a television",
        )
    }

    @Test
    fun theBudgetIsBoundedAndUsable() {
        val config: BufferConfig = bufferConfigForDevice(context)

        // A budget of zero is what a heap lookup that failed produces, and
        // Media3 accepts it — it just never buffers enough to start, which
        // presents as a video that spins forever on a working connection.
        assertTrue(config.targetBufferBytes > 0, "no buffer budget at all: $config")
        assertTrue(config.minBufferMs > 0 && config.maxBufferMs >= config.minBufferMs, "inverted window: $config")
        assertTrue(
            config.bufferForPlaybackMs in 1..config.minBufferMs,
            "playback would start outside the buffer window: $config",
        )
    }

    @Test
    fun aTelevisionStartsSoonerThanItsCeilingWouldSuggest() {
        // The bedroom-TV rule. A television has a large screen and a small
        // heap, so it gets a tighter ceiling and a faster start than the
        // ceiling alone implies — the alternative is the ANR this heuristic was
        // written for.
        val config: BufferConfig = bufferConfigForDevice(context)
        if (!config.isTvDevice) {
            println("not a television — the TV branch of the budget is not exercised here")
            return
        }

        assertTrue(
            config.bufferForPlaybackMs < config.maxBufferMs,
            "a television would wait for its whole ceiling before starting: $config",
        )
    }
}
