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

// The cases are the web's `__tests__/translator-bcp47.test.ts`.
class Bcp47FallbackChainTest {

    @Test
    fun aBareLanguageIsItsOwnChain() {
        assertEquals(listOf("en"), Bcp47FallbackChain.candidatesFor("en"))
    }

    @Test
    fun aRegionVariantWalksOneParent() {
        assertEquals(listOf("pt-BR", "pt"), Bcp47FallbackChain.candidatesFor("pt-BR"))
    }

    @Test
    fun aScriptAndRegionTagWalksEachLevel() {
        assertEquals(
            listOf("zh-Hant-TW", "zh-Hant", "zh"),
            Bcp47FallbackChain.candidatesFor("zh-Hant-TW"),
        )
    }

    @Test
    fun anEmptyTagHasNoCandidates() {
        // Not listOf("") — an empty tag would look up a bundle nobody has, and
        // the caller's `firstNotNullOfOrNull` would walk it every miss.
        assertEquals(emptyList(), Bcp47FallbackChain.candidatesFor(""))
    }

    @Test
    fun casingIsPreservedBecauseBundlesAreMatchedByteForByte() {
        assertEquals(listOf("zh-Hant", "zh"), Bcp47FallbackChain.candidatesFor("zh-Hant"))
    }
}
