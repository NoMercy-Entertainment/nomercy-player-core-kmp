// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

import tv.nomercy.player.core.ports.CapabilitiesProbe
import tv.nomercy.player.core.ports.DecodeProfile

// One rung and what the device said about it.
//
// [smooth] is carried rather than acted on. A 4K rung the device can decode but
// not at full rate is still a choice somebody with a big screen may want, and
// dropping it silently is a decision this library does not get to make. A chrome
// marks it; the viewer decides.
public data class UsableRung(
    val descriptor: QualityDescriptor,
    val smooth: Boolean,
    val powerEfficient: Boolean,
)

// The renditions a stream offers, in one order regardless of which engine
// reported them.
public class QualityLadder(descriptors: List<QualityDescriptor>) {

    // Sorted on construction, so every consumer sees the same ladder. The three
    // engines hand it over in three different orders and none of them is wrong.
    public val descriptors: List<QualityDescriptor> = descriptors.distinct().sorted()

    public fun isEmpty(): Boolean = descriptors.isEmpty()

    public fun size(): Int = descriptors.size

    // Asks the device about each rung and keeps the ones it can decode.
    //
    // Unsupported rungs leave; unsmooth ones stay, marked. If nothing survives,
    // the result is empty and the caller raises core:media/codec-unsupported —
    // it must not fall back to playing something and seeing what happens, which
    // is the failure this whole path exists to prevent.
    public suspend fun usable(probe: CapabilitiesProbe): List<UsableRung> =
        descriptors.mapNotNull { descriptor ->
            val capability = probe.canDecode(
                DecodeProfile(
                    contentType = descriptor.codec ?: "",
                    height = descriptor.height,
                    bitrate = descriptor.bitrate,
                ),
            )
            if (!capability.supported) {
                null
            } else {
                UsableRung(descriptor, capability.smooth, capability.powerEfficient)
            }
        }

    // The best rung the device handles smoothly, for a player choosing on its
    // own. Null when nothing is smooth, which is the caller's cue to ask rather
    // than to guess.
    public fun bestSmooth(rungs: List<UsableRung>): QualityDescriptor? =
        rungs.filter { it.smooth }.maxByOrNull { it.descriptor }?.descriptor

    override fun equals(other: Any?): Boolean =
        other is QualityLadder && descriptors == other.descriptors

    override fun hashCode(): Int = descriptors.hashCode()

    override fun toString(): String = "QualityLadder(${descriptors.joinToString { it.label() }})"
}
