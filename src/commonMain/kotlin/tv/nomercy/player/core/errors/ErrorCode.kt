// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

// The universal identifier for a failure: namespace:category/reason, the same
// string on the web, here, in the docs and in a bug report.
//
// The namespace is what makes the scheme extensible without a registry. Core
// owns "core"; a plugin owns its own id, so fillz:viz/canvas-init is a valid
// code nobody had to allow-list. Parsing is strict — lower-case, hyphenated,
// exactly three parts — because a code that only nearly matches would be
// grouped separately by every dashboard that reads it.
public data class ErrorCode(
    val namespace: String,
    val category: String,
    val reason: String,
) {
    override fun toString(): String = "$namespace:$category/$reason"

    public companion object {
        private val SHAPE = Regex("^([a-z0-9-]+):([a-z0-9-]+)/([a-z0-9-]+)$")

        public fun parseOrNull(code: String): ErrorCode? {
            val match = SHAPE.matchEntire(code) ?: return null
            val (namespace, category, reason) = match.destructured
            return ErrorCode(namespace, category, reason)
        }

        public fun parse(code: String): ErrorCode =
            parseOrNull(code)
                ?: throw IllegalArgumentException(
                    "malformed error code: '$code' (expected namespace:category/reason)",
                )

        public fun make(namespace: String, category: String, reason: String): String =
            ErrorCode(namespace, category, reason).toString()
    }
}
