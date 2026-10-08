// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libmpv

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
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

            // Through the helper, which copies the string out and frees what
            // libmpv allocated. Reading the pointer directly and letting JNA
            // convert it leaks the original on every call.
            val idle: String? = mpv.property(handle, "idle-active")
            assertTrue(idle == "yes" || idle == "no", "idle-active came back as $idle")
        } finally {
            mpv.mpv_terminate_destroy(handle)
        }
    }

    // A Mac or Linux user whose region writes 0,5 got a null handle and a black
    // player: mpv_create refuses any numeric locale but "C". Loading the
    // library is what has to fix it, because every caller loads first.
    // Anything in the process may set the locale from the user's region before
    // the player starts (on CI the full suite did), so the test sets a comma
    // locale itself and then asks for a handle.
    @Test
    fun aHandleIsCreatedWhenTheProcessHasACommaDecimalLocale() {
        val category: Int? = when {
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> 4
            System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> 1
            else -> null
        }
        if (category == null) {
            println("SKIPPED: mpv does not check the numeric locale on ${System.getProperty("os.name")}")
            return
        }
        val libc: TestLibC = Native.load(Platform.C_LIBRARY_NAME, TestLibC::class.java)
        val hostile: String? = listOf("nl_NL.UTF-8", "de_DE.UTF-8", "fr_FR.UTF-8")
            .firstNotNullOfOrNull { name -> libc.setlocale(category, name) }
        if (hostile == null) {
            println("SKIPPED: no comma-decimal locale installed")
            return
        }
        try {
            val mpv: LibMpv? = libraryOrNull()
            if (mpv == null) {
                println("SKIPPED: no ${LibMpv.SONAME} on jna.library.path")
                return
            }
            val handle: MpvHandle = assertNotNull(mpv.mpv_create(), "mpv_create returned null under $hostile")
            mpv.mpv_terminate_destroy(handle)
        } finally {
            libc.setlocale(category, "C")
        }
    }

    @Suppress("FunctionNaming")
    interface TestLibC : Library {
        fun setlocale(category: Int, locale: String?): String?
    }

    @Test
    fun theSonameIsThePlatformsNotTheBareName() {
        // Windows ships libmpv-2.dll, Linux libmpv.so.2, macOS libmpv.2.dylib.
        // JNA finds none of them from "mpv", and the failure reads as a missing
        // library rather than a wrong name.
        assertTrue(LibMpv.SONAME != "mpv", "the bare name resolves on no platform")
    }
}
