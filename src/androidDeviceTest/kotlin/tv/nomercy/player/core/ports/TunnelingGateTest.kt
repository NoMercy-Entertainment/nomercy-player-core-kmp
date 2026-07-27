// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// Whether the engine was actually asked to tunnel, read off the engine.
//
// TunnelingRuleTest covers the decision and can run anywhere, because it is
// handed booleans. This covers the part that cannot be faked: that the decision
// reaches Media3, and that the surface precondition is applied in front of it.
//
// The precondition is why this file exists. Tunneling hands decoded frames
// straight to the display pipeline, so without a surface to hand them to the
// decoder never initialises — no error, no event, just an item that never
// becomes ready. Three audio-menu gates went red exactly that way on the
// television while the phone, which never tunnels, stayed green.
class TunnelingGateTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun loaded(name: String, surfaceAttached: Boolean): Boolean {
        val media: File = writeDualAudioClip(File(context.cacheDir, name))
        val backend = ExoPlayerVideoBackend(context)
        try {
            backend.videoSurfaceAttached = surfaceAttached
            runBlocking { backend.load(media.absolutePath, LoadOptions()) }
            // Read off Media3's own selector parameters rather than a field
            // this class wrote, so the gate cannot pass by agreeing with the
            // setter it is supposed to be checking.
            return backend.tunnelingActive
        } finally {
            backend.release()
            media.delete()
        }
    }

    @Test
    fun withNoSurfaceTunnelingIsNeverAskedFor() {
        // On every device, including a television. The form factor makes
        // tunneling desirable; the surface makes it possible, and desirable
        // without possible is a player that silently never starts.
        assertFalse(loaded("tunnel-none.mp4", surfaceAttached = false))
    }

    @Test
    fun theRuleIsWhatTheGateConsults() {
        // The decision itself, without loading anything. Claiming a surface
        // that does not exist would turn tunneling on for real on a television,
        // and an item that cannot initialise leaves the codec in a state the
        // next test inherits — which is one flaky failure in four runs, caused
        // entirely by the gate that was supposed to be protecting the fix.
        //
        // The rule's truth table is covered headlessly in TunnelingRuleTest.
        // What is left to check here is the precondition above, and that this
        // device answers the form-factor question the rule is asked.
        val isTv: Boolean = bufferConfigForDevice(context).isTvDevice

        assertEquals(
            isTv,
            TunnelingRule.shouldTunnel(isTv = isTv, sourceIsHls = false, refusedByAudioSink = false),
            "a direct MP4 on this form factor",
        )
        assertFalse(
            TunnelingRule.shouldTunnel(isTv = isTv, sourceIsHls = true, refusedByAudioSink = false),
            "HLS must never tunnel, on any device",
        )
    }
}
