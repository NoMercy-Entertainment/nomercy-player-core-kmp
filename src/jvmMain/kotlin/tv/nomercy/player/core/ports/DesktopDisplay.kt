// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Whether a desktop monitor can show HDR, which the JDK will not say.
//
// Always false, and that is a measurement rather than a stub. The whole of what
// the JDK offers about a screen is GraphicsDevice, DisplayMode and
// GraphicsConfiguration, and none of the three carries a transfer function:
// DisplayMode answers width, height, refresh rate and bit depth, and bit depth is
// not the question — ten-bit SDR panels are ordinary. GraphicsConfiguration gives
// a ColorModel, and java.awt.color.ColorSpace names no PQ, no HLG and no BT.2020
// to recognise one by. There is no AWT equivalent of Display.getHdrCapabilities.
//
// It is also per-monitor rather than per-machine, and a window can be dragged
// from an HDR screen to an SDR one mid-playback, so even a correct answer would
// have to be re-asked on every move. Both halves are missing, not one.
//
// So this is the conservative half of the trade every other platform makes
// deliberately: a desktop caps to the best SDR rung. The cost is one rung on a
// machine that had an HDR monitor; the alternative cost is the washed-out picture
// that started this.
//
// It costs nothing today either way, because the other input is missing too:
// libvlc_video_track_t carries no transfer function, so no desktop rung is ever
// identified as HDR to decide about. See VlcjVideoBackend.canToneMapHdrToSdr.
// A function rather than a constant on purpose: it is the JVM's answer to the
// question androidDisplayIsHdr and appleDisplayIsHdr answer, and the three call
// sites read the same way. It stops being constant the day AWT can be asked.
@Suppress("FunctionOnlyReturningConstant")
public fun desktopDisplayIsHdr(): Boolean = false
