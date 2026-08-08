// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.embed

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
