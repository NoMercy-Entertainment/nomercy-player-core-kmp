// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device.fakes

import tv.nomercy.player.core.device.DeviceCapabilities
import tv.nomercy.player.core.device.FormFactor

// A device the test decides the shape of.
//
// Every chrome behaviour worth testing is a consequence of these flags, and none
// of them can be produced on the machine running the suite: a desktop cannot be
// asked to become a television.
//
// The defaults are the ordinary device of each kind, so a case that cares about
// one flag sets that one and reads as being about it.
internal data class FakeDeviceCapabilities(
    override val formFactor: FormFactor,
    override val hasDpad: Boolean = formFactor == FormFactor.Tv,
    override val hasTouch: Boolean = formFactor == FormFactor.Phone || formFactor == FormFactor.Tablet,
    override val hasPointer: Boolean = formFactor == FormFactor.Desktop,
    override val hasHardwareVolumeKeys: Boolean = formFactor != FormFactor.Tv,
) : DeviceCapabilities
