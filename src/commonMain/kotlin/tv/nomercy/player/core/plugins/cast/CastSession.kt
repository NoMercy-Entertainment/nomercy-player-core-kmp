// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.cast

/**
 * The receiver, as much of it as this plugin needs.
 *
 * A port rather than an SDK, for the reason [ChromeCastMediaCtors] gives: core
 * links no Play Services and no AVFoundation, so the framework arrives from
 * outside. What it also buys is a cast sender that can be driven in a test
 * without a television in the room, which is the only way the disconnect and
 * resume paths were ever going to be exercised.
 *
 * Connection here, commands in [CastTransport]. They separate because they have
 * different lifetimes: a session exists from the moment a viewer picks a device
 * until it goes away, while the commands are only meaningful in between — and
 * because a platform that offers routing without remote control implements one
 * and not the other.
 */
public interface CastSession : CastTransport {

    /** Whether a receiver is connected right now. */
    public fun isConnected(): Boolean

    /** The receiver's name, for the chrome to show. */
    public fun deviceName(): String?

    /** Opens the platform's own picker and connects to whatever is chosen. */
    public suspend fun connect()

    public suspend fun disconnect()

    /** Sends media to the receiver and starts it at [positionSeconds]. */
    public suspend fun load(media: CastMediaInfo, positionSeconds: Double)

    /**
     * Everything the receiver reports back, including its position.
     *
     * The receiver is the clock once a session is running, so this is the only
     * source of truth for where playback is. A sender that stopped listening
     * would hand a viewer resuming locally the position the film was at when
     * casting STARTED.
     */
    public fun observe(listener: (CastSenderEvents) -> Unit): () -> Unit
}
