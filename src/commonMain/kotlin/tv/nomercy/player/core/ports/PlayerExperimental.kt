// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Replacing a player method at run time, and putting it back.
 *
 * Every override returns its OWN undo rather than there being a global reset:
 * two consumers overriding two methods have to unwind independently, and a
 * single `restoreAll` would let the second one's teardown silently revert the
 * first.
 */
public interface PlayerExperimental {

    /** Replaces [method]; the returned function puts the original back. */
    public fun override(method: String, fn: (List<Any?>) -> Any?): () -> Unit

    public fun restore(method: String)

    public fun overrides(): List<MethodOverride>
}

/**
 * One method that is not the one the library shipped, and who replaced it.
 *
 * [by] is the point of this type. "Why is seek behaving strangely" is answerable
 * in one call when the answer carries a name and unanswerable when it does not,
 * and the plugin that overrode it is rarely the one being suspected.
 */
public data class MethodOverride(
    val method: String,
    /** The plugin id that installed it, or [CONSUMER]. */
    val by: String,
) {
    public companion object {
        public const val CONSUMER: String = "consumer"
    }
}
