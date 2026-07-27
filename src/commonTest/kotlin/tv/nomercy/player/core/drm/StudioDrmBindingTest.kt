// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// A studio scheme, and the honest reasons it will not run yet.
//
// The point of these is that being blocked is stated rather than discovered. A
// binding that silently returned ready and failed at the first key would look
// complete here and fail on every device.
class StudioDrmBindingTest {

    @Test
    fun aSchemeWithNoLicenceEndpointIsBlockedRatherThanReady() {
        val blocked: DrmBindingResult.Blocked? = licenseUrlOrBlocked(DrmConfig(DrmScheme.WIDEVINE))

        assertEquals(DrmErrorCodes.NO_LICENSE_URL, blocked?.code)
    }

    @Test
    fun theReasonNamesTheSchemeSoTheBlockerIsAccountedFor() {
        // "Blocked" on its own is a feature nobody can account for. Naming it
        // is the difference between waiting on one known dependency and having
        // lost track of why something does not work.
        val blocked: DrmBindingResult.Blocked? = licenseUrlOrBlocked(DrmConfig(DrmScheme.FAIRPLAY))

        assertTrue(blocked!!.reason.contains("FAIRPLAY"), "the reason said ${blocked.reason}")
    }

    @Test
    fun aBlankEndpointCountsAsNoEndpoint() {
        // Configuration comes from a server response and an empty string is what
        // an unset field arrives as. Treating it as a URL is a request to
        // nowhere and an error a viewer cannot act on.
        val blocked: DrmBindingResult.Blocked? =
            licenseUrlOrBlocked(DrmConfig(DrmScheme.WIDEVINE, licenseUrl = "   "))

        assertEquals(DrmErrorCodes.NO_LICENSE_URL, blocked?.code)
    }

    @Test
    fun anEndpointThatExistsIsNotItselfABlocker() {
        // The day the server route ships, this stops answering. Everything
        // beyond it is the platform's business.
        val blocked: DrmBindingResult.Blocked? =
            licenseUrlOrBlocked(DrmConfig(DrmScheme.WIDEVINE, licenseUrl = "https://server.example/license"))

        assertEquals(null, blocked)
    }

    @Test
    fun aBlockedResultIsDistinguishableFromReadyWithoutReadingStrings() {
        // A caller deciding whether to offer another version should branch on
        // the shape, not on parsing a message meant for a person.
        val result: DrmBindingResult = DrmBindingResult.Blocked(DrmErrorCodes.NO_LICENSE_URL, "no endpoint")

        assertIs<DrmBindingResult.Blocked>(result)
        assertTrue(DrmBindingResult.Ready !is DrmBindingResult.Blocked)
    }
}
