// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext

// The Cast receiver's own form: a television with no pointer and no touch,
// driven entirely by the sender phone's remote-control custom messages and
// CAF's own PlayerManager play/pause/seek events — never a mouse or a finger.
internal object WebReceiverCapabilities : DeviceCapabilities {
    override val formFactor: FormFactor = FormFactor.Tv
    override val hasDpad: Boolean = true
    override val hasTouch: Boolean = false
    override val hasPointer: Boolean = false
    override val hasHardwareVolumeKeys: Boolean = false

    // Not probed. Chromecast/CAF hardware HDR support varies per device and no
    // browser API exposes the attached panel's real HDR capability — same
    // conservative "say no rather than guess" call jvmMain's desktop actual
    // makes for the same reason.
    override val hasHdrDisplay: Boolean = false
}

public actual fun platformDeviceCapabilities(context: PlatformContext): DeviceCapabilities =
    WebReceiverCapabilities
