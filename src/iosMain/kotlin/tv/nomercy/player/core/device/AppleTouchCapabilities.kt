// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import tv.nomercy.player.core.ports.PlatformContext

// An iPhone or an iPad. Both are touch, neither has a remote.
//
// Kept apart from the television rather than branched at runtime: they are
// separate source sets and separate binaries, so this is decided when the build
// is made rather than every time something asks.
internal class AppleTouchCapabilities(override val formFactor: FormFactor) : DeviceCapabilities {

    override val hasDpad: Boolean = false

    override val hasTouch: Boolean = true

    // A trackpad can be attached to an iPad, and it is reported false anyway.
    // Chrome that shrank its touch targets because a pointer was present would
    // be unusable the moment the keyboard case came off, and touch is the design
    // the platform is built around.
    override val hasPointer: Boolean = false

    override val hasHardwareVolumeKeys: Boolean = true

    // UIScreen.potentialEDRHeadroom answers this properly. Until it is read, false.
    override val hasHdrDisplay: Boolean = false
}

public actual fun platformDeviceCapabilities(context: PlatformContext): DeviceCapabilities =
    AppleTouchCapabilities(
        if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
            FormFactor.Tablet
        } else {
            FormFactor.Phone
        },
    )
