// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

// How bad an error is. [level] exists so severities can be compared and
// filtered — a log sink that wants "warning and above" needs an order, and
// enum ordinal would silently change the moment someone reorders the constants.
public enum class Severity(public val token: String, public val level: Int) {
    INFO("info", 1),
    WARNING("warning", 2),
    ERROR("error", 3),
    FATAL("fatal", 4);

    public companion object {
        public fun fromToken(token: String): Severity =
            entries.firstOrNull { it.token == token }
                ?: throw IllegalArgumentException("unknown Severity token: $token")
    }
}
