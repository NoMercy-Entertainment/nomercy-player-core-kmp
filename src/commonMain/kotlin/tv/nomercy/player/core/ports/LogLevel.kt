// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * How much the player says.
 *
 * Ordered least to most verbose, and the order is the point: a threshold is a
 * comparison, so `silent → error → warn → info → debug → trace` has to be a rank
 * rather than a set of names. The web keeps the same ladder and ranks `silent`
 * at -1 so it sits below every real level; here the enum's own ordinal does that
 * work, with [SILENT] first for the same reason.
 *
 * An enum rather than a string alias, because the web's `LogLevel` is a union of
 * six literals and Kotlin's equivalent of a closed set of values is an enum. A
 * typo cannot compile.
 */
public enum class LogLevel {
    SILENT,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE,
    ;

    /**
     * Whether a line at [level] should be written when this is the threshold.
     *
     * [SILENT] writes nothing at all, including errors — it is a threshold below
     * every level rather than a level anything is logged at.
     */
    public fun allows(level: LogLevel): Boolean = this != SILENT && level != SILENT && level <= this
}

/**
 * Every line the player produces, handed somewhere.
 *
 * A consumer wiring Sentry breadcrumbs or Datadog implements this rather than a
 * whole [Logger]: the kit's own logger keeps its formatting and scoping and the
 * sink only receives the result. Same three arguments the web passes.
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
