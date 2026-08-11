// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext

// A browser tab has no way to ask its host TV what it crops, same gap
// androidMain's own doc comment describes for Android TV — so this always
// applies the industry figure, same as androidMain's actual.
public actual fun platformOverscan(context: PlatformContext): SafeAreaInsets =
    if (platformDeviceCapabilities(context).formFactor == FormFactor.Tv) {
        DEFAULT_TV_OVERSCAN
    } else {
        SafeAreaInsets()
    }
