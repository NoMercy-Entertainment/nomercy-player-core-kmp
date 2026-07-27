// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext

// Android will not say what a television crops.
//
// There was an API for it and it was removed after API 21, so a modern Android
// TV application has no way to ask. The industry figure is what is left, and it
// is applied only where it is needed: a phone crops nothing, and insetting one
// would move the controls away from the edge for no reason.
//
// A box that does know better overrides this at the chrome, which is why the
// contract is four numbers rather than a boolean.
public actual fun platformOverscan(context: PlatformContext): SafeAreaInsets =
    if (platformDeviceCapabilities(context).formFactor == FormFactor.Tv) {
        DEFAULT_TV_OVERSCAN
    } else {
        SafeAreaInsets()
    }
