// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.events.Subscription

// One message on the wire between a player and whatever is hosting it.
//
// The origin travels with the payload rather than being configured once,
// because it is the only thing the receiving side can check and it is different
// for every message. A transport that cannot attribute a message honestly
// reports an empty origin, which the allowlist then refuses — a transport that
// invented one would turn the check off while leaving it looking on.
public data class EmbedMessage(
    val origin: String,
    val payload: String,
)

// How an embedded player talks to its host.
//
// The web player is embedded in an `<iframe>` and speaks `postMessage`; a
// native player is embedded in a WebView, another process, or a receiver app,
// and speaks whatever that host understands. The protocol is the same in every
// case and lives in the plugin; only the pipe differs, so only the pipe is a
// port.
//
// JSON strings rather than a structured type, because both ends of this are
// usually not both Kotlin. The web host page already parses this exact envelope.
public interface EmbedTransport {

    public fun send(payload: String)

    public fun receive(fn: (EmbedMessage) -> Unit): Subscription
}
