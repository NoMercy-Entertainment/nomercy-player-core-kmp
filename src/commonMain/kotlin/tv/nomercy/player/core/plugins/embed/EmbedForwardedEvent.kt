// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.embed

import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.player.ActionOptions

/**
 * The eight events an embedded player sends out to whatever hosts it.
 *
 * Eight, and not "whatever the player emits". An embed forwards across a trust
 * boundary — a page that is not ours, on an origin we do not control — and every
 * event forwarded is a promise about a payload shape that someone else's code
 * reads. Forwarding the internal bus wholesale would make every future internal
 * event a public API the day it was added.
 */
public sealed interface EmbedForwardedEvent {

    /** The wire name, exactly as the host reads it. */
    public val name: String

    /** The player is ready. No payload. */
    public data object Ready : EmbedForwardedEvent {
        override val name: String = "ready"
    }

    public data class Play(val options: ActionOptions = ActionOptions()) : EmbedForwardedEvent {
        override val name: String = "play"
    }

    public data class Pause(val options: ActionOptions = ActionOptions()) : EmbedForwardedEvent {
        override val name: String = "pause"
    }

    /** Playback reached the end. No payload. */
    public data object Ended : EmbedForwardedEvent {
        override val name: String = "ended"
    }

    public data class Time(val time: Double) : EmbedForwardedEvent {
        override val name: String = "time"
    }

    public data class Volume(val level: Double) : EmbedForwardedEvent {
        override val name: String = "volume"
    }

    public data class Mute(val muted: Boolean) : EmbedForwardedEvent {
        override val name: String = "mute"
    }

    public data class Error(val error: EmbedSerializedError) : EmbedForwardedEvent {
        override val name: String = "error"
    }
}

/**
 * One forwarded event on the wire.
 *
 * [type] is fixed and is the whole point of it: a host page receives messages
 * from every frame and every script on it, and a discriminator it can check
 * first is what stops it parsing somebody else's traffic as ours.
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

/**
 * A player error, flattened for a host that cannot receive an exception.
 *
 * [context] is a map of strings rather than the error's own payload, because
 * this crosses to another origin: an object graph carrying engine handles or a
 * file path is a leak, and one that cannot be serialised silently becomes the
 * empty object on the far side.
 */
public data class EmbedSerializedError(
    val code: String,
    val severity: Severity,
    val scope: ErrorScope,
    val message: String? = null,
    val suggestion: String? = null,
    val context: Map<String, String> = emptyMap(),
)
