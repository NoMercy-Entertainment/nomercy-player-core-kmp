// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext
import tv.nomercy.player.core.ports.desktopDisplayIsHdr

// A desktop: a pointer and a keyboard.
//
// Touch is reported absent even on a laptop that has a touchscreen, because the
// question this answers is which chrome to draw and such a machine is still
// driven by a pointer. A gamepad arrives as a directional input through the
// input adapter rather than by changing what the machine says it is.
internal object DesktopCapabilities : DeviceCapabilities {
    override val formFactor: FormFactor = FormFactor.Desktop
    override val hasDpad: Boolean = false
    override val hasTouch: Boolean = false
    override val hasPointer: Boolean = true
    override val hasHardwareVolumeKeys: Boolean = false

    // The same probe the playback path uses. It answers false on every machine,
    // and desktopDisplayIsHdr says which JDK APIs were checked to conclude that.
    override val hasHdrDisplay: Boolean = desktopDisplayIsHdr()
}

public actual fun platformDeviceCapabilities(context: PlatformContext): DeviceCapabilities =
    DesktopCapabilities
