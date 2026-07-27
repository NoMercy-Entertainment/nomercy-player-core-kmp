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
import kotlin.test.assertNull

// What the library is told about protected content.
//
// Small, and worth having anyway: the key-system strings are the one part of
// this that cannot be checked at runtime. A typo in "com.widevine.alpha" is not
// an error, it is a device reporting that it cannot play the film.
class DrmConfigTest {

    @Test
    fun everyStudioSchemeCarriesTheIdentifierThePlatformsExpect() {
        assertEquals("com.widevine.alpha", DrmScheme.WIDEVINE.keySystem)
        assertEquals("com.apple.fps", DrmScheme.FAIRPLAY.keySystem)
        assertEquals("com.microsoft.playready", DrmScheme.PLAYREADY.keySystem)
    }

    @Test
    fun theSchemesWithNoKeySystemSayNullRatherThanAnEmptyString() {
        // An empty string is a key system a platform will try to look up and
        // fail on. Null is the only honest answer for a scheme that has none.
        assertNull(DrmScheme.AES_128.keySystem)
        assertNull(DrmScheme.CLEAR.keySystem)
    }

    @Test
    fun theProtectionLevelsSpellThemselvesTheWayTheContractDoes() {
        assertEquals("type-0", HdcpLevel.TYPE_0.wireValue)
        assertEquals("type-1", HdcpLevel.TYPE_1.wireValue)
        assertEquals("none", HdcpLevel.NONE.wireValue)
    }

    @Test
    fun aConfigCarriesTheFieldsTheContractNames() {
        val config = DrmConfig(
            scheme = DrmScheme.WIDEVINE,
            licenseUrl = "https://server.example/license",
            hdcpRequired = HdcpLevel.TYPE_1,
        )

        assertEquals(DrmScheme.WIDEVINE, config.scheme)
        assertEquals("https://server.example/license", config.licenseUrl)
        assertEquals(HdcpLevel.TYPE_1, config.hdcpRequired)
        assertNull(config.certificate)
    }

    @Test
    fun anAes128ConfigNeedsNoLicenceServerAtAll() {
        // The near-term realistic case. Its key arrives in the manifest, so a
        // config that demanded a licence URL would make the only scheme that
        // works today impossible to express.
        val config = DrmConfig(scheme = DrmScheme.AES_128)

        assertNull(config.licenseUrl)
        assertNull(config.signRequest)
    }
}
