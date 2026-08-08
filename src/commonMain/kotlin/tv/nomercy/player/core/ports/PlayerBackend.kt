// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * What a player's engine offers beyond playing.
 *
 * Both members are optional and that is the contract: an engine rendering into a
 * buffer has no media element to hand out, and one without an audio graph has no
 * node to tap. A consumer asking for either gets null and does without, rather
 * than an engine inventing a stub that behaves like neither.
 */
public interface PlayerBackend {

    /** The platform's own media object, when the engine has one to expose. */
    public fun mediaElement(): Any? = null

    /** A node to tap for analysis, when the engine has an audio graph. */
    public fun outputNode(): Any? = null
}
