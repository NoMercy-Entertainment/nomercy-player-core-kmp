// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Language names, against the platform's own tables.
//
// The point of replacing the app's hand-written map is that this list is not
// ours to keep, so the tests check the properties that matter rather than
// asserting a hundred spellings back at the platform.
class LanguageNamesTest {

    private val original: Locale = Locale.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(original)
    }

    @Test
    fun aLanguageIsNamedInTheViewersOwnLanguage() {
        // The whole reason for the change. The app's map was English-only, so a
        // Dutch viewer picking a Dutch dub read the word "Dutch" in an
        // otherwise Dutch interface.
        Locale.setDefault(Locale.forLanguageTag("nl"))
        assertEquals("Nederlands", displayLanguage("nld"))

        Locale.setDefault(Locale.ENGLISH)
        assertEquals("Dutch", displayLanguage("nld"))
    }

    @Test
    fun bothSpellingsOfALanguageAgree() {
        // Manifests carry two-letter and three-letter codes interchangeably,
        // sometimes within one title. Two menu rows for one language is what
        // happens when they are not reconciled.
        Locale.setDefault(Locale.ENGLISH)

        assertEquals(displayLanguage("nl"), displayLanguage("nld"))
        assertEquals(displayLanguage("de"), displayLanguage("deu"))
        assertEquals(displayLanguage("ja"), displayLanguage("jpn"))
    }

    @Test
    fun aRegionalVariantKeepsItsBaseLanguageName() {
        // pt-BR is a different dub from pt and the tags must not be collapsed,
        // but the language name is still Portuguese. The region belongs in a
        // qualifier, not in the language column.
        Locale.setDefault(Locale.ENGLISH)

        assertEquals("Portuguese", displayLanguage("pt-BR"))
    }

    @Test
    fun anUnknownTagComesBackAsItself() {
        // The most specific true thing available. Three rows reading "Unknown"
        // cannot be told apart; three reading their own tags can.
        assertEquals("qaa", displayLanguage("qaa"))
        assertEquals("", displayLanguage(""))
    }

    @Test
    fun aLanguageTheAppsMapNeverHadIsStillNamed() {
        // The second failure of a hand-kept list: it is missing whatever ships
        // next. Cherokee was not in the app's hundred entries.
        Locale.setDefault(Locale.ENGLISH)
        val name: String = displayLanguage("chr")

        assertTrue(name.isNotBlank() && name != "chr", "the platform did not name chr, got '$name'")
    }

    @Test
    fun aQualifierIsOnlyNeededWhenLanguagesRepeat() {
        // "English (EAC3)" is noise next to "Japanese". It stops being noise
        // when the other row is also English.
        assertEquals(false, labelsNeedQualifier(listOf("en", "ja")))
        assertEquals(true, labelsNeedQualifier(listOf("en", "en")))
        assertEquals(false, labelsNeedQualifier(listOf("en")))
        assertEquals(false, labelsNeedQualifier(emptyList()))
    }
}
