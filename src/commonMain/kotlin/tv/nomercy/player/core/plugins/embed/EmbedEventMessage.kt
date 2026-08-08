// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.embed

/**
 * One forwarded event on the wire.
 *
 * [type] is fixed and that is the whole point of it: a host page receives
 * messages from every frame and every script on it, and a discriminator it can
 * check first is what stops it parsing somebody else's traffic as ours.
 */
public data class EmbedEventMessage(
    val name: String,
    val data: EmbedForwardedEvent,
) {
    public val type: String = EVENT_TYPE

    public companion object {
        public const val EVENT_TYPE: String = "nm:event"
    }
}
