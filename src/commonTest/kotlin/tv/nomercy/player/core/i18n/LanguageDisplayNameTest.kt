// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A track menu in Dutch listed `English (Full)` and `Croatian (Full)` under a
 * header reading `Ondertiteling`, because the label was stored when the track
 * was parsed rather than composed when the row was drawn.
 */
class LanguageDisplayNameTest {

    @Test
    fun aLanguageIsNamedInTheViewersLanguage() {
        assertEquals("Engels", languageDisplayName("eng", "nl"), "eng in Dutch")
        assertEquals("English", languageDisplayName("eng", "en"), "eng in English")
        assertEquals("Nederlands", languageDisplayName("nld", "nl"), "nld in Dutch")
    }

    @Test
    fun theBibliographicCodesResolveWithoutHelp() {
        // ISO 639-2/B, which is what containers actually carry. Deliberately not
        // in the quirks map: adding entries a platform already knows is how a
        // table drifts from the system it is meant to agree with.
        assertEquals("Frans", languageDisplayName("fre", "nl"), "fre is French")
        assertEquals("Duits", languageDisplayName("ger", "nl"), "ger is German")
    }

    @Test
    fun theQuirksAreCodesNoResolverKnows() {
        // `bra` and `pob` come off discs and encoder output, not out of a spec,
        // and no platform resolves either.
        assertEquals(
            languageDisplayName("pt-BR", "nl"),
            languageDisplayName("bra", "nl"),
            "bra must resolve as pt-BR",
        )
    }

    @Test
    fun anUnresolvableCodeIsNullRatherThanItself() {
        // Every platform resolver echoes an unknown tag back unchanged, and a
        // row reading "zzz" is a row a viewer cannot choose. Null lets the caller
        // fall back to something it can explain.
        assertNull(languageDisplayName("zzz", "nl"), "an unknown code must not echo")
        assertNull(languageDisplayName(null, "nl"), "a missing code must not throw")
        assertNull(languageDisplayName("", "nl"), "a blank code must not throw")
    }
}
