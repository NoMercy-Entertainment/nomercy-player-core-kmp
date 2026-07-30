// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import tv.nomercy.player.core.ports.PlatformContext
import tv.nomercy.player.core.ports.androidDisplayIsHdr

// Android, which is every one of these shapes and will not say which.
//
// No single signal answers it. A television reports its UI mode, but a box
// running a phone build of the system does not; the leanback feature is
// declared by set-top boxes that report no UI mode at all; and screen width
// separates a tablet from a phone but says nothing about either of the first
// two. The client this replaces merged three signals in one place and the web
// player read a string, and they disagreed about the same device.
//
// Merged here, once, so everything downstream reads one answer.
internal class AndroidCapabilities(context: Context) : DeviceCapabilities {

    private val packages: PackageManager = context.packageManager

    private val leanback: Boolean =
        packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packages.hasSystemFeature(FEATURE_LEANBACK_ONLY)

    private val television: Boolean = leanback || uiModeIsTelevision(context)

    private val configuration: Configuration = context.resources.configuration

    override val formFactor: FormFactor = when {
        television -> FormFactor.Tv
        configuration.smallestScreenWidthDp >= TABLET_WIDTH_DP -> FormFactor.Tablet
        else -> FormFactor.Phone
    }

    // The declared feature or an attached one. A phone with a controller plugged
    // in genuinely has a directional pad, and refusing to route its presses
    // because the device is a phone is a controller that does nothing.
    override val hasDpad: Boolean =
        leanback || configuration.navigation == Configuration.NAVIGATION_DPAD

    override val hasTouch: Boolean = packages.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    override val hasPointer: Boolean = false

    // Boxes vary and the remote's volume usually goes to the panel rather than
    // to the running application. Assuming a television has none is the safe
    // way round: the cost is a volume control not drawn on a device that could
    // have handled it, against a control that visibly does nothing.
    override val hasHardwareVolumeKeys: Boolean = !television

    // The same probe the playback path uses, so the capability a chrome reads and
    // the capability a backend decides on cannot disagree about one panel.
    override val hasHdrDisplay: Boolean = androidDisplayIsHdr(context)

    private companion object {
        // Declared by set-top boxes that report no television UI mode. Not in
        // the platform constants until a later API level than this library
        // supports, so it is named rather than assumed absent.
        const val FEATURE_LEANBACK_ONLY = "android.software.leanback_only"

        const val TABLET_WIDTH_DP = 600
    }
}

private fun uiModeIsTelevision(context: Context): Boolean {
    val modes: UiModeManager? = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return modes?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

public actual fun platformDeviceCapabilities(context: PlatformContext): DeviceCapabilities =
    AndroidCapabilities(context.androidContext)
