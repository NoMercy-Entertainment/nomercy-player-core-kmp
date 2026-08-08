// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * What the player asks a stream to aim for.
 *
 * Every field is optional and that is the contract, not laziness: a caller
 * saying only "no wider than 1920" must not have a frame rate invented for it,
 * because a stream filtering on an invented number drops renditions the caller
 * never excluded.
 */
public data class StreamCapabilities(
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val framerate: Double? = null,
    /** Which way to lean when a device can decode a rendition but not comfortably. */
    val preferred: StreamPreference? = null,
)

/**
 * Smoothly, or without flattening the battery.
 *
 * The same pair `mediaCapabilities.decodingInfo` reports and [CanPlayResult]
 * carries, asked the other way round: there it is what the device answers, here
 * it is what the caller wants when the two disagree.
 */
public enum class StreamPreference(public val id: String) {
    SMOOTH("smooth"),
    POWER_EFFICIENT("powerEfficient"),
}
