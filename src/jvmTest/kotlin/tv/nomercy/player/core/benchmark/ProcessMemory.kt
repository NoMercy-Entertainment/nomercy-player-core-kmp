// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.benchmark

import java.io.File

/**
 * How much memory this process actually holds, in megabytes.
 *
 * The resident set and not the JVM heap. A decoder allocates its frame pool,
 * its demuxer buffers and its scaler outside the heap entirely, so a heap
 * reading of an engine comparison would put both engines at the same number and
 * report that memory is not a differentiator. The figure a viewer sees in their
 * task manager is the one that has to move.
 *
 * Zero when the platform cannot be asked, which reads as "not measured" in the
 * report rather than as a suspiciously good result.
 */
internal object ProcessMemory {

    fun residentMb(): Long = when {
        os.startsWith("windows") -> fromTasklist()
        os.startsWith("linux") -> fromProcStatm()
        os.startsWith("mac") -> fromPs()
        else -> 0L
    }

    // tasklist reports "12,345 K" with the thousands separator of the console
    // locale, so every non-digit goes before it is read as a number.
    private fun fromTasklist(): Long = command(
        "tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH",
    )
        ?.substringAfterLast(',')
        ?.filter { character -> character.isDigit() }
        ?.toLongOrNull()
        ?.div(KB_PER_MB)
        ?: 0L

    // Field two is the resident set in pages, and the page size is the one the
    // kernel was built with rather than a constant worth guessing at.
    private fun fromProcStatm(): Long = File("/proc/self/statm").takeIf(File::isFile)
        ?.readText()
        ?.trim()
        ?.split(' ')
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?.times(LINUX_PAGE_BYTES)
        ?.div(BYTES_PER_MB)
        ?: 0L

    private fun fromPs(): Long =
        command("ps", "-o", "rss=", "-p", "$pid")?.trim()?.toLongOrNull()?.div(KB_PER_MB) ?: 0L

    // A machine that will not answer is a machine with no reading, and the
    // report prints that as a dash. The exception itself is not carried: this
    // is a measurement beside a playback test, and failing the run because the
    // memory column is blank would trade the thing being measured for the
    // instrument measuring it.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun command(vararg argv: String): String? = try {
        val process: Process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val output: String = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.lines().firstOrNull { line -> line.isNotBlank() }
    } catch (unavailable: RuntimeException) {
        null
    } catch (refused: java.io.IOException) {
        null
    }

    private val pid: Long by lazy { ProcessHandle.current().pid() }
    private val os: String by lazy { System.getProperty("os.name").orEmpty().lowercase() }

    private const val KB_PER_MB: Long = 1024
    private const val BYTES_PER_MB: Long = 1024 * 1024
    private const val LINUX_PAGE_BYTES: Long = 4096
}
