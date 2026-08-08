// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * What each stream event carries.
 *
 * A sealed set rather than a map keyed by event name, because the web's version
 * is a map from a literal union to a payload type — a compile-time pairing — and
 * a `Map<String, Any?>` would throw that away and hand every listener a cast.
 * The pairing is the value.
 */
public sealed interface StreamEventPayloadMap {

    public val event: StreamEvent

    /** Quality levels are available. */
    public data object ManifestLoaded : StreamEventPayloadMap {
        override val event: StreamEvent = StreamEvent.MANIFEST_LOADED
    }

    public data class LevelSwitched(
        val level: Int,
        val height: Int?,
        val bitrate: Int?,
    ) : StreamEventPayloadMap {
        override val event: StreamEvent = StreamEvent.LEVEL_SWITCHED
    }

    /** ABR weighed a level and did not take it. */
    public data class LevelConsidered(val level: Int, val reason: String?) : StreamEventPayloadMap {
        override val event: StreamEvent = StreamEvent.LEVEL_CONSIDERED
    }

    public data class FragmentLoaded(
        val url: String?,
        val durationSeconds: Double?,
    ) : StreamEventPayloadMap {
        override val event: StreamEvent = StreamEvent.FRAGMENT_LOADED
    }

    /** A key exchange is pending. */
    public data class Encrypted(val keySystem: String?) : StreamEventPayloadMap {
        override val event: StreamEvent = StreamEvent.ENCRYPTED
    }

    public data class Failed(val error: StreamErrorPayload) : StreamEventPayloadMap {
        override val event: StreamEvent = StreamEvent.ERROR
    }
}
