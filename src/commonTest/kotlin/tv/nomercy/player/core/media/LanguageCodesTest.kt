// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageCodesTest {

    // The whole reason the table exists: a muxer writes the bibliographic code
    // and the file beside it uses the 639-1 one, and comparing the raw strings
    // makes one language into two.
    @Test
    fun bothIso6392FormsFoldOntoTheSameLanguage() {
        assertEquals("de", normalizeLanguage("ger"))
        assertEquals("de", normalizeLanguage("deu"))
        assertEquals("de", normalizeLanguage("de"))

        assertEquals("fr", normalizeLanguage("fre"))
        assertEquals("fr", normalizeLanguage("fra"))

        assertEquals("zh", normalizeLanguage("chi"))
        assertEquals("zh", normalizeLanguage("zho"))
    }

    // A viewer who chose German did not mean to exclude Austrian German.
    @Test
    fun aRegionalTagKeepsOnlyItsLanguage() {
        assertEquals("de", normalizeLanguage("de-AT"))
        assertEquals("pt", normalizeLanguage("pt-BR"))
        assertEquals("de", normalizeLanguage("GER"))
    }

    // An unknown code is passed through rather than dropped: a language this
    // table has never heard of still has to compare equal to itself, or the
    // track carrying it can never be matched to its own sidecar.
    @Test
    fun anUnrecognisedCodeSurvivesLowercased() {
        assertEquals("qya", normalizeLanguage("qya"))
        assertEquals("qya", normalizeLanguage("QYA"))
    }

    // Absent is a real answer. Inventing a language for it would make two
    // unlabelled tracks look like the same one and silently hide the second.
    @Test
    fun absentStaysAbsent() {
        assertNull(normalizeLanguage(null))
        assertEquals("", normalizeLanguage(""))
    }

    // Guards a truncated table, which is the failure this port is exposed to:
    // the entries are generated from the reference's own map, and a partial copy
    // would pass every case above while silently never matching the languages it
    // dropped. The count is the reference's, taken from its source.
    @Test
    fun theTableIsWholeAndWellFormed() {
        assertEquals(203, LANG_CANONICAL.size)
        assertTrue(LANG_CANONICAL.values.all { it.length == 2 }, "every target is a 639-1 pair")
        assertTrue(LANG_CANONICAL.keys.all { it.length == 3 }, "every key is a 639-2 triple")
    }
}
