// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext

// Nothing, because the phone already did it.
//
// UIKit hands a view its own safe area, and a library adding a second inset on
// top would push the controls in twice: once for the notch the system already
// accounted for, and once again for a television this device is not.
public actual fun platformOverscan(context: PlatformContext): SafeAreaInsets = SafeAreaInsets()
