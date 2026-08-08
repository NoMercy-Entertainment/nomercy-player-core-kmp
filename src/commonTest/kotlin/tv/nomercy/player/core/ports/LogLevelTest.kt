// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The threshold, which is the only part of a log level that can be wrong.
//
// A name is a name; an ORDER is a decision, and getting it backwards means a
// player set to `error` writes trace lines in production or one set to `trace`
// writes nothing during an investigation.
class LogLevelTest {

    @Test
    fun theLadderRunsFromLeastToMostVerbose() {
        assertEquals(
            listOf(
                LogLevel.SILENT,
                LogLevel.ERROR,
                LogLevel.WARN,
                LogLevel.INFO,
                LogLevel.DEBUG,
                LogLevel.TRACE,
            ),
            LogLevel.entries,
            "the web's ladder is silent -> error -> warn -> info -> debug -> trace",
        )
    }

    @Test
    fun aThresholdAllowsItselfAndEverythingQuieter() {
        assertTrue(LogLevel.INFO.allows(LogLevel.ERROR))
        assertTrue(LogLevel.INFO.allows(LogLevel.WARN))
        assertTrue(LogLevel.INFO.allows(LogLevel.INFO))
    }

    @Test
    fun aThresholdRefusesAnythingNoisierThanItself() {
        assertFalse(LogLevel.INFO.allows(LogLevel.DEBUG))
        assertFalse(LogLevel.INFO.allows(LogLevel.TRACE))
        assertFalse(LogLevel.ERROR.allows(LogLevel.WARN))
    }

    // Below every level rather than one of them. An implementation that treated
    // it as a name would still write errors, which is the one thing the person
    // who chose it asked not to happen.
    @Test
    fun silentWritesNothingAtAllIncludingErrors() {
        LogLevel.entries.forEach { level ->
            assertFalse(LogLevel.SILENT.allows(level), "silent allowed $level")
        }
    }

    @Test
    fun aLoggerDefaultsToInfoWithNoPrefix() {
        val options = LoggerOptions()

        assertEquals(LogLevel.INFO, options.level)
        assertEquals("", options.prefix)
    }

    // A sink is handed the level, the prefix and the arguments — the three the
    // web passes — so a consumer bridging to Sentry keeps the kit's formatting
    // and scoping instead of reimplementing them.
    @Test
    fun aSinkReceivesTheLevelThePrefixAndTheArguments() {
        val written: MutableList<Triple<LogLevel, String, List<Any?>>> = mutableListOf()
        val sink = LogSink { level, prefix, args -> written += Triple(level, prefix, args) }

        sink.write(LogLevel.WARN, "[nmplayer][lyrics]", listOf("late", 42))

        assertEquals(
            listOf(Triple(LogLevel.WARN, "[nmplayer][lyrics]", listOf<Any?>("late", 42))),
            written,
        )
    }
}
