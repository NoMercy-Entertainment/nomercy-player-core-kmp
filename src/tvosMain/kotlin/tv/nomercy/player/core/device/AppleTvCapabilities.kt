// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext
import tv.nomercy.player.core.ports.appleDisplayIsHdr

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

    // The same probe the playback path uses, so the capability a chrome reads and
    // the capability a backend decides on cannot disagree about one television.
    //
    // Deferred rather than computed when this object initialises: an object's
    // initialiser runs on whichever thread first touches it, and UIScreen is the
    // main thread's to answer.
    override val hasHdrDisplay: Boolean by lazy { appleDisplayIsHdr() }
}

public actual fun platformDeviceCapabilities(context: PlatformContext): DeviceCapabilities =
    AppleTvCapabilities
