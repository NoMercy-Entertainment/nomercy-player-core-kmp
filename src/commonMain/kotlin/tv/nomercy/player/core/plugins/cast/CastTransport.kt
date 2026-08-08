// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.cast

/**
 * Driving whatever is playing on the receiver.
 *
 * Seconds rather than the player's milliseconds, because that is the unit every
 * receiver framework speaks and converting once at this boundary beats
 * converting in each implementation — where one of them will eventually do it
 * the wrong way round and seek to hour four hundred.
 *
 * Volume is 0..1 here for the same reason: the frameworks agree on that scale,
 * and the plugin converts from the player's 0..100 in one place.
 */
public interface CastTransport {

    public suspend fun play()

    public suspend fun pause()

    public suspend fun stop()

    public suspend fun seekTo(positionSeconds: Double)

    /** 0..1. */
    public suspend fun setVolume(level: Double)

    public suspend fun setMuted(muted: Boolean)
}
