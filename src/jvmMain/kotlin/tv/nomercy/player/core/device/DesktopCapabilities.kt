// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext

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

    // No portable way to ask a desktop panel from the JVM: it is per-monitor, and a
    // window can be dragged between an HDR one and an SDR one mid-playback.
    override val hasHdrDisplay: Boolean = false
}

public actual fun platformDeviceCapabilities(context: PlatformContext): DeviceCapabilities =
    DesktopCapabilities
