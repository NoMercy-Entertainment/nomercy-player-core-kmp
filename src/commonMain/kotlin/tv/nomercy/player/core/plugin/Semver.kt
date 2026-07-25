// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

private const val CORE_SEGMENTS = 3

// Three-way version compare: negative, zero or positive for a against b.
//
// This is what every "needs at least version X" check in the registry runs on,
// so it follows semver precedence rather than string order: 2.0.0 beats
// 2.0.0-rc.1, and 2.0.10 beats 2.0.9. Missing segments read as zero, so "2"
// and "2.0.0" are the same version.
internal fun compareSemver(verA: String, verB: String): Int {
    val (numsA: List<Int>, preA: String) = parseSemver(verA)
    val (numsB: List<Int>, preB: String) = parseSemver(verB)

    val core: Int = compareCore(numsA, numsB)
    return if (core != 0) core else comparePreRelease(preA, preB)
}

private fun parseSemver(version: String): Pair<List<Int>, String> {
    val dash: Int = version.indexOf('-')
    val main: String = if (dash >= 0) version.substring(0, dash) else version
    val pre: String = if (dash >= 0) version.substring(dash + 1) else ""
    val nums: MutableList<Int> = main.split(".").map { it.toIntOrNull() ?: 0 }.toMutableList()
    while (nums.size < CORE_SEGMENTS) nums.add(0)
    return nums to pre
}

private fun compareCore(numsA: List<Int>, numsB: List<Int>): Int {
    for (index in 0 until CORE_SEGMENTS) {
        val cmp: Int = numsA[index].compareTo(numsB[index])
        if (cmp != 0) return cmp
    }
    return 0
}

private fun comparePreRelease(preA: String, preB: String): Int = when {
    preA == preB -> 0
    // A finished release outranks any pre-release of the same version.
    preA.isEmpty() -> 1
    preB.isEmpty() -> -1
    else -> compareIdentifiers(preA.split("."), preB.split("."))
}

private fun compareIdentifiers(idsA: List<String>, idsB: List<String>): Int {
    for (index in 0 until minOf(idsA.size, idsB.size)) {
        val cmp: Int = compareIdentifier(idsA[index], idsB[index])
        if (cmp != 0) return cmp
    }
    // Every shared identifier matched, so the one with more of them wins:
    // rc.1.1 comes after rc.1.
    return idsA.size.compareTo(idsB.size)
}

// Numeric identifiers compare as numbers and always rank below alphanumeric
// ones, which is what puts rc.2 after rc.10's numeric sibling in the right
// place instead of sorting them as text.
private fun compareIdentifier(idA: String, idB: String): Int {
    val numA: Int? = idA.toIntOrNull()
    val numB: Int? = idB.toIntOrNull()
    return when {
        numA != null && numB != null -> numA.compareTo(numB)
        numA != null -> -1
        numB != null -> 1
        else -> idA.compareTo(idB)
    }
}
