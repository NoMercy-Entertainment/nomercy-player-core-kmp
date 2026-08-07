// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

// The desktop, which has no key box of any kind — on either engine.
//
// Named for the platform rather than for libVLC, because the answer does not
// change with the engine: libmpv has no key box either, and a capability named
// after whichever engine happened to be first is a capability that looks
// engine-specific when it is not.
//
// It decodes practically everything and decrypts nothing that needs a licence,
// so AES-128 is the only protection a desktop client can honour. That is not a
// gap to be closed later: a studio key box on a desktop is a vendor SDK and a
// signed application, which is a different product.
//
// Saying so plainly is what lets a chrome offer the AES-128 version of a film
// instead of failing at the first key.
public class DesktopDrmCapability : DrmCapability {

    override fun supports(scheme: DrmScheme): Boolean =
        scheme == DrmScheme.AES_128 || scheme == DrmScheme.CLEAR
}
