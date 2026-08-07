// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libmpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The binding round-trips a real handle against the real library.
 *
 * Skipped, loudly, when no libmpv is on the path - a test that quietly passes
 * without the library is the empty measurement that agrees with everything.
 * Point it at one with -Djna.library.path=<dir containing libmpv-2.dll>.
 */
class LibMpvBindingTest {

    private fun libraryOrNull(): LibMpv? = runCatching { LibMpv.load() }.getOrNull()

    @Test
    fun aHandleTakesAPropertyAndGivesItBack() {
        val mpv: LibMpv? = libraryOrNull()
        if (mpv == null) {
            println("SKIPPED: no ${LibMpv.SONAME} on jna.library.path")
            return
        }

        val handle: MpvHandle = assertNotNull(mpv.mpv_create(), "mpv_create returned null")
        try {
            // Set BEFORE initialize, which is what mpv requires of options that
            // decide how the player is built. Doing it after returns
            // MPV_ERROR_OPTION_ERROR and the player runs with a video output
            // nobody asked for.
            assertEquals(0, mpv.mpv_set_option_string(handle, "vo", "null"), "vo=null rejected")
            assertEquals(0, mpv.mpv_set_option_string(handle, "ao", "null"), "ao=null rejected")
            assertEquals(0, mpv.mpv_initialize(handle), "mpv_initialize failed")

            // The property that closes quality switching. Assignable at runtime,
            // which is the whole difference from libVLC 3.
            assertEquals(0, mpv.mpv_set_property_string(handle, "edition", "2"), "edition rejected")

            val idle: String? = mpv.mpv_get_property_string(handle, "idle-active")
            assertTrue(idle == "yes" || idle == "no", "idle-active came back as $idle")
        } finally {
            mpv.mpv_terminate_destroy(handle)
        }
    }

    @Test
    fun theSonameIsThePlatformsNotTheBareName() {
        // Windows ships libmpv-2.dll, Linux libmpv.so.2, macOS libmpv.2.dylib.
        // JNA finds none of them from "mpv", and the failure reads as a missing
        // library rather than a wrong name.
        assertTrue(LibMpv.SONAME != "mpv", "the bare name resolves on no platform")
    }
}
