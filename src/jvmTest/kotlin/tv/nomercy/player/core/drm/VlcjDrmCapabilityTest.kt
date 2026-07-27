// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The desktop engine's real answer, asserted where it compiles.
//
// The shared matrix test describes the shape; this one pins the actual class, so
// a change to it that contradicts the matrix reddens the build rather than
// quietly disagreeing with a test that never sees it.
class VlcjDrmCapabilityTest {

    private val desktop = VlcjDrmCapability()

    @Test
    fun itPlaysTheSchemeThatNeedsNoKeyBox() {
        assertTrue(desktop.supports(DrmScheme.AES_128))
        assertTrue(desktop.supports(DrmScheme.CLEAR))
    }

    @Test
    fun itClaimsNoStudioSchemeAtAll() {
        // Not a gap to be closed later. A studio key box on a desktop is a
        // vendor SDK and a signed application, which is a different product.
        assertFalse(desktop.supports(DrmScheme.WIDEVINE))
        assertFalse(desktop.supports(DrmScheme.FAIRPLAY))
        assertFalse(desktop.supports(DrmScheme.PLAYREADY))
    }
}
