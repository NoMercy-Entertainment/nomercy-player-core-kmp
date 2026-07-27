// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

// The desktop engine, which has no key box of any kind.
//
// It decodes practically everything and decrypts nothing that needs a licence,
// so AES-128 is the only protection a desktop client can honour. That is not a
// gap to be closed later: a studio key box on a desktop is a vendor SDK and a
// signed application, which is a different product.
//
// Saying so plainly is what lets a chrome offer the AES-128 version of a film
// instead of failing at the first key.
public class VlcjDrmCapability : DrmCapability {

    override fun supports(scheme: DrmScheme): Boolean =
        scheme == DrmScheme.AES_128 || scheme == DrmScheme.CLEAR
}
