// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIUserInterfaceIdiomPhone
import platform.UIKit.UIUserInterfaceIdiomTV

// iOS and tvOS from one file, because UIKit answers both.
//
// The idiom is the platform's own classification, not an inference: an Apple TV
// reports .tv because it is one. That also covers the case a user-agent parser
// gets wrong on the web, where an iPad reporting a desktop Safari string has to
// be caught by a separate touch-points heuristic.
private val DETECTED: Device by lazy {
    val device: UIDevice = UIDevice.currentDevice
    Device(formFactor = formFactorOf(device), os = osOf(device))
}

public actual fun currentDevice(): Device = DETECTED

private fun formFactorOf(device: UIDevice): FormFactor =
    when (device.userInterfaceIdiom) {
        UIUserInterfaceIdiomTV -> FormFactor.TV
        UIUserInterfaceIdiomPhone, UIUserInterfaceIdiomPad -> FormFactor.MOBILE
        // .mac and .vision, neither of which this player targets today. Desktop
        // is the honest answer for both: a pointer, a keyboard, and mains power.
        else -> FormFactor.DESKTOP
    }

private fun osOf(device: UIDevice): OperatingSystem =
    when {
        device.systemName.startsWith("tv", ignoreCase = true) -> OperatingSystem.TVOS
        device.systemName.startsWith("i", ignoreCase = true) -> OperatingSystem.IOS
        else -> OperatingSystem.UNKNOWN
    }
