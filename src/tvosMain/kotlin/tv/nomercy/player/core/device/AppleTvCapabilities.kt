// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext

// An Apple TV, which is only ever one thing.
//
// The remote has a touch surface and it is not touch input: it moves focus, it
// does not press what is under a finger. Reporting it as touch is how a
// television ends up with controls that can only be hit by aiming at them.
//
// Volume is not this application's to change. The remote talks to the panel or
// the receiver, so a volume control drawn here would be one that does nothing.
internal object AppleTvCapabilities : DeviceCapabilities {
    override val formFactor: FormFactor = FormFactor.Tv
    override val hasDpad: Boolean = true
    override val hasTouch: Boolean = false
    override val hasPointer: Boolean = false
    override val hasHardwareVolumeKeys: Boolean = false
}

public actual fun platformDeviceCapabilities(context: PlatformContext): DeviceCapabilities =
    AppleTvCapabilities
