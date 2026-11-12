// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The artwork fetch sends the token to the media host only, never to a third-party cover host. */
class ArtworkRequestHeadersTest {
    @After
    fun reset() {
        PlatformEnvironment.installArtworkRequestHeaders { emptyMap() }
        PlatformEnvironment.installArtworkAuthHeaders { emptyMap() }
    }

    @Test
    fun urlAwareProviderSendsTheHeaderOnlyToItsHost() {
        PlatformEnvironment.installArtworkRequestHeaders { url ->
            if (url.startsWith("https://media.example.test/")) mapOf("Authorization" to "Bearer test") else emptyMap()
        }

        assertEquals(emptyMap<String, String>(), artworkHeadersFor("https://images.example.org/x.jpg"))
        assertEquals(
            mapOf("Authorization" to "Bearer test"),
            artworkHeadersFor("https://media.example.test/x.jpg"),
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyProviderStillAppliesToEveryHost() {
        PlatformEnvironment.installArtworkAuthHeaders { mapOf("Authorization" to "Bearer old") }

        assertEquals(
            mapOf("Authorization" to "Bearer old"),
            artworkHeadersFor("https://images.example.org/x.jpg"),
        )
    }
}
