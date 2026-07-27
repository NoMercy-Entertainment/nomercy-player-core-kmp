// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// The Apple platforms that are not iPhones, which is where a television lives.
//
// Asserted on this target rather than only in common code, because that is where
// it would break: an accidental override that throws on one platform is invisible
// to a shared test and crashes the moment a chrome asks whether the button
// belongs on screen.
class AppleExternalPlaybackDefaultTest {

    @Test
    fun thisPlatformSaysItHasNowhereToSendPlaybackTo() {
        val external: ExternalPlayback = UnsupportedExternalPlayback

        assertFalse(external.isSupported, "this platform claimed an external route sender")
        assertEquals(ExternalPlaybackState.Unsupported, external.state.value)
    }

    @Test
    fun askingAnywayIsSafeRatherThanFatal() {
        // Callers hold the port unconditionally and make these calls on every
        // platform. A throw here is a crash on the platforms that were never
        // going to support it in the first place.
        val external: ExternalPlayback = UnsupportedExternalPlayback

        external.setAllowed(true)
        external.setAllowed(false)
        external.showRoutePicker()
        external.dispose()
        external.dispose()

        assertEquals(ExternalPlaybackState.Unsupported, external.state.value)
    }
}
