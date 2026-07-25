// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

public data class DecodeProfile(
    val contentType: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val framerate: Int? = null,
)

// Three answers, not one: [smooth] is whether it decodes at full rate,
// [powerEfficient] whether it does so in hardware. A rendition can be supported
// and still be the wrong choice on battery.
public data class DecodeCapability(
    val supported: Boolean,
    val smooth: Boolean,
    val powerEfficient: Boolean,
)

// Asks the device what it can actually play, so the quality ladder is filtered
// before a viewer picks something that stalls.
public interface CapabilitiesProbe {
    public suspend fun canDecode(profile: DecodeProfile): DecodeCapability
    public suspend fun supportedCodecs(): List<String>
}
