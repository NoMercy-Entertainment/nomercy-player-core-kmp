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
