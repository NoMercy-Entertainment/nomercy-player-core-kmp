// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import platform.UIKit.UIDevice
import platform.UIKit.UIScreen

// Whether the screen in front of the viewer can show more than standard range.
//
// One file for iOS and tvOS because UIKit answers both, the same way
// Device.apple.kt reads one idiom for both. On an Apple TV the value describes
// what the attached television negotiated, which is the answer that matters:
// the box is capable of HDR whether or not the set it is plugged into is.
//
// EDR headroom rather than AVDisplayCriteria. Criteria are a REQUEST — you build
// one to ask for a display mode — and carry nothing readable about what the
// display can do, so a capability read from them would be a reading of this
// library's own ambition.
public fun appleDisplayIsHdr(): Boolean {
    if (!headroomIsReadable()) return false

    // Exactly one is standard range: the brightest thing the screen can show is
    // as bright as diffuse white. Anything above it is headroom to put highlights
    // in, which is the whole of what HDR needs from a panel.
    return UIScreen.mainScreen.potentialEDRHeadroom.toDouble() > STANDARD_RANGE_HEADROOM
}

// potentialEDRHeadroom arrives in iOS 16 and tvOS 16, and there is no public API
// before it that answers this at all — not gamut, which is colour volume rather
// than range. So an older system reports SDR, which is the conservative half of
// the same trade the property itself is read for: an SDR decision on an HDR panel
// costs one rung, and an HDR decision on an SDR panel is the washed-out picture.
private fun headroomIsReadable(): Boolean {
    val major: Int = UIDevice.currentDevice.systemVersion
        .substringBefore('.')
        .toIntOrNull()
        ?: return false

    return major >= EDR_HEADROOM_SYSTEM_VERSION
}

private const val STANDARD_RANGE_HEADROOM = 1.0
private const val EDR_HEADROOM_SYSTEM_VERSION = 16
