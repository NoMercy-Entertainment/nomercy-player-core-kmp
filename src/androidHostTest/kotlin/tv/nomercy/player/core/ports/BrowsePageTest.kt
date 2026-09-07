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
import kotlin.test.assertTrue

// What a car actually asks for when it walks a browse tree.
//
// No Android types here on purpose: the paging is arithmetic, and arithmetic
// that only a head unit can check is arithmetic nobody checks.
//
// Lettered entries rather than numbered ones so the expected slices read as
// themselves — a page asserted as [3, 4, 5] says nothing about whether 3 is an
// entry or an index.
class BrowsePageTest {

    private val catalogue: List<String> = ('a'..'j').map(Char::toString)

    @Test
    fun eachPageCarriesItsOwnSliceNotTheFirstOneAgain() {
        assertEquals(listOf("a", "b", "c"), catalogue.browsePage(FIRST_PAGE, PAGE_SIZE))
        assertEquals(listOf("d", "e", "f"), catalogue.browsePage(SECOND_PAGE, PAGE_SIZE))
        assertEquals(listOf("g", "h", "i"), catalogue.browsePage(THIRD_PAGE, PAGE_SIZE))
    }

    @Test
    fun theLastPageIsShortRatherThanOutOfBounds() {
        assertEquals(listOf("j"), catalogue.browsePage(LAST_PAGE, PAGE_SIZE))
    }

    @Test
    fun aPagePastTheEndIsEmptyRatherThanAThrow() {
        assertTrue(catalogue.browsePage(A_PAGE_PAST_THE_END, PAGE_SIZE).isEmpty())
    }

    // The end index, computed in Int: page 0 with an unbounded page size
    // overflows negative and subList throws — on the one request that asked for
    // the entire library.
    @Test
    fun askingForEverythingReturnsEverything() {
        assertEquals(catalogue, catalogue.browsePage(FIRST_PAGE, EVERYTHING))
    }

    // The start index, same overflow: page 2 of an unbounded page size wraps
    // negative, which slips past the past-the-end guard and hands subList a
    // negative index.
    @Test
    fun aPageFarPastTheEndOfAnUnboundedWindowIsEmptyRatherThanAThrow() {
        assertTrue(catalogue.browsePage(THIRD_PAGE, EVERYTHING).isEmpty())
    }

    @Test
    fun aNonsenseWindowIsEmptyRatherThanAThrow() {
        assertTrue(catalogue.browsePage(page = -1, pageSize = PAGE_SIZE).isEmpty())
        assertTrue(catalogue.browsePage(FIRST_PAGE, pageSize = 0).isEmpty())
    }

    private companion object {
        const val PAGE_SIZE: Int = 3
        const val FIRST_PAGE: Int = 0
        const val SECOND_PAGE: Int = 1
        const val THIRD_PAGE: Int = 2
        const val LAST_PAGE: Int = 3
        const val A_PAGE_PAST_THE_END: Int = 99
        const val EVERYTHING: Int = Int.MAX_VALUE
    }
}
