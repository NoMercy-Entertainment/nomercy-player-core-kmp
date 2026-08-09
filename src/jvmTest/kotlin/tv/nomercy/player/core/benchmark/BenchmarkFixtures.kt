// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.benchmark

import java.io.File

/** One entry of the testbed's catalogue, as the benchmark reads it. */
internal data class BenchmarkFixture(val id: String, val url: String)

/**
 * The films the benchmark runs, read from the testbed's own catalogue.
 *
 * A tab-separated listing rather than a list in here, because a fixture added to
 * the testbed and not to the benchmark is a film nobody measures — and the one
 * that goes unmeasured is always the awkward one. The file is generated from the
 * catalogue, so the two cannot drift apart silently.
 */
internal object BenchmarkFixtures {

    const val PROPERTY: String = "nomercy.benchmark.fixtures"

    /** Every fixture named by the listing, or null when none was given. */
    fun fromProperty(): List<BenchmarkFixture>? =
        System.getProperty(PROPERTY)?.let { path -> read(File(path)) }

    fun read(listing: File): List<BenchmarkFixture> = listing.readLines()
        .filter { line -> line.isNotBlank() && !line.startsWith('#') }
        .map { line -> BenchmarkFixture(line.substringBefore('\t').trim(), line.substringAfter('\t').trim()) }
        .filter { fixture -> fixture.url.isNotEmpty() }

    /**
     * The subset a run was narrowed to, or all of them.
     *
     * Chasing one film is the common case while a defect is open, and rerunning
     * twenty to look at one is how a measurement stops being run at all.
     */
    fun narrow(fixtures: List<BenchmarkFixture>, only: String?): List<BenchmarkFixture> {
        val wanted: Set<String> = only?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()
        return if (wanted.isEmpty()) fixtures else fixtures.filter { fixture -> fixture.id in wanted }
    }
}
