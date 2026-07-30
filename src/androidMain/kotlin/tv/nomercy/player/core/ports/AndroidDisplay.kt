// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

// Whether this device's default display can show HDR at all.
//
// Asked of the DisplayManager rather than of a Display handed in, because a
// backend is constructed with a Context and the display it will end up on is not
// known until something is drawn. The default display is the honest answer for a
// decision made before there is a surface — and on the device this matters for,
// a television, there is only one.
//
// An empty capability list is the answer for most phones and for every emulator,
// which is why the constraint above defaults to capping rather than to allowing.
public fun androidDisplayIsHdr(context: Context): Boolean {
    val manager: DisplayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        ?: return false
    val display: Display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false

    // The current mode's own list, where the platform has one. Display-wide
    // capabilities describe every mode the panel could be switched into, so a
    // television negotiated down to an SDR mode still reports HDR types through
    // them — and the question here is what is on screen now, not what the cable
    // could carry after a mode change this library never asks for.
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        display.mode.supportedHdrTypes.isNotEmpty()
    } else {
        display.isHdr
    }
}
