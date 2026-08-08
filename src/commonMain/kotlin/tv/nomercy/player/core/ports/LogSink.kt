// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Every line the player produces, handed somewhere.
 *
 * A consumer wiring Sentry breadcrumbs or Datadog implements this rather than a
 * whole [Logger]: the kit's own logger keeps its formatting and its scoping, and
 * the sink only receives the result.
 */
public fun interface LogSink {
    public fun write(level: LogLevel, prefix: String, args: List<Any?>)
}

/**
 * How a logger was built.
 *
 * [prefix] is what makes a line attributable — a plugin's logger carries its id,
 * so its output reads `[nmplayer][lyrics]` and a support ticket names the plugin
 * that was talking rather than the player.
 */
public data class LoggerOptions(
    /** Verbosity threshold. */
    val level: LogLevel = LogLevel.INFO,
    /** What every line from this logger is tagged with. */
    val prefix: String = "",
)
