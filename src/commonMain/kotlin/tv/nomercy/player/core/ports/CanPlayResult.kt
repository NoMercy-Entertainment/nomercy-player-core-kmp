// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Whether this device can decode a codec, and at what cost.
 *
 * Three answers rather than one boolean, because "can decode" and "can decode
 * without dropping frames" are different questions and a client conflating them
 * plays a slideshow. It is the distinction `mediaCapabilities.decodingInfo`
 * draws and `MediaSource.isTypeSupported` does not — a Chrome build reports
 * hvc1 support on hardware with no HEVC decoder, and the picture is grey.
 */
public data class CanPlayResult(
    /** True when this device can decode the codec/container combination. */
    val supported: Boolean,
    /** True when it decodes smoothly, with no dropped frames expected. */
    val smooth: Boolean,
    /** True when it decodes without excessive battery drain. */
    val powerEfficient: Boolean,
)
