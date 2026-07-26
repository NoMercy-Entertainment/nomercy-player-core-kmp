// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

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
public fun androidDisplayIsHdr(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
        as? android.hardware.display.DisplayManager
        ?: return false
    val display = manager.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return false

    @Suppress("DEPRECATION")
    return display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
}
