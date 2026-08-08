// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * What a stream tells the player while it plays.
 *
 * The six the web defines, and no more: an engine reporting something else has
 * to earn a name in the contract first, because a seventh added here is a
 * message no web consumer can handle and no other engine will ever send.
 */
public enum class StreamEvent(public val id: String) {
    /** Manifest or metadata parsed; quality levels are now available. */
    MANIFEST_LOADED("manifest-loaded"),

    /** ABR or an explicit call switched to a new rendition. */
    LEVEL_SWITCHED("level-switched"),

    /** ABR weighed a candidate and did not switch. Informational. */
    LEVEL_CONSIDERED("level-considered"),

    /** A media segment finished downloading. */
    FRAGMENT_LOADED("fragment-loaded"),

    /** An encrypted segment appeared; a key exchange is pending. */
    ENCRYPTED("encrypted"),

    /** Something went wrong, fatally or not. */
    ERROR("error"),
    ;

    public companion object {
        /** The event with this wire id, or null — a name we do not know is not a crash. */
        public fun of(id: String): StreamEvent? = entries.firstOrNull { entry -> entry.id == id }
    }
}

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

/**
 * A chance to rewrite a response before the engine sees it.
 *
 * This is where a token is refreshed after a 401, a manifest is patched, or a
 * CDN host is swapped. It RETURNS a response rather than mutating one, because
 * the web's does and because a mutated response cannot be replaced by a
 * different one — which is exactly what a retry has to do.
 */
public fun interface StreamInterceptor {
    public suspend fun intercept(url: String, response: FetchResponse): FetchResponse
}
