// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.cast

/**
 * How the cast sender behaves.
 *
 * [resumeLocalOnDisconnect] decides whether a viewer whose television drops off
 * the network is left staring at a stopped player or picks up where the receiver
 * was — and picking up needs the position the receiver last reported, which is
 * why [CastSenderEvents.RemoteState] carries one rather than being an occasional
 * courtesy.
 */
public data class CastSenderOptions(
    /** The receiver application id to launch. */
    val chromecastAppId: String? = null,

    /** Offer AirPlay alongside Chromecast where the platform has it. */
    val enableAirPlay: Boolean = false,

    /** A private namespace for messages a stock receiver would not understand. */
    val customReceiverNamespace: String? = null,

    /** Resume locally when the receiver goes away. */
    val resumeLocalOnDisconnect: Boolean = false,

    /** Content type sent when an item does not name one. */
    val defaultContentType: String? = null,

    /** Whether the media is live, which changes the receiver's stream type. */
    val live: Boolean = false,
)
