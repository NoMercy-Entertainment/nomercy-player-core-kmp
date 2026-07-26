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

// What the video player registers.
private val VIDEO_TOKENS: Map<String, String> = mapOf(
    "S" to "token.season",
    "E" to "token.episode",
)

// A translator standing in for a Dutch viewer, so the test can tell an
// interpolated string from a passed-through one.
private fun dutch(key: String, vars: Map<String, String>): String {
    val number: String = vars.getValue("number")
    return when (key) {
        "token.season" -> "Seizoen $number"
        "token.episode" -> "Aflevering $number"
        else -> key
    }
}

class TitleTokensTest {

    private fun interpolate(text: String, registry: Map<String, String> = VIDEO_TOKENS): String =
        TitleTokens.interpolate(text, registry, ::dutch)

    @Test
    fun aStoredTitleBecomesTheViewersLanguage() {
        // The whole point: a server that sent "Season 2" would be sending
        // English to a Dutch viewer.
        assertEquals("Seizoen 2 Aflevering 5", interpolate("%S2 %E5"))
    }

    @Test
    fun theTextAroundATokenIsUntouched() {
        assertEquals(
            "Breaking Bad - Seizoen 2 Aflevering 5 - Grilled",
            interpolate("Breaking Bad - %S2 %E5 - Grilled"),
        )
    }

    @Test
    fun multipleDigitsStayTogether() {
        // Matching one digit would turn episode 12 into "Aflevering 1" followed
        // by a loose 2.
        assertEquals("Aflevering 12", interpolate("%E12"))
    }

    @Test
    fun anUnregisteredLetterIsLeftVerbatim() {
        // Something a newer server sent. Shown as it came rather than swallowed:
        // a viewer seeing "%X3" has something to report, one seeing a gap has
        // nothing.
        assertEquals("%X3 Aflevering 5", interpolate("%X3 %E5"))
    }

    @Test
    fun anEmptyRegistryChangesNothing() {
        // Core's default. A no-op rather than a wrong guess about what S means.
        assertEquals("%S2 %E5", interpolate("%S2 %E5", registry = emptyMap()))
    }

    @Test
    fun runningItTwiceGivesTheSameAnswer() {
        // Resolved text has no tokens left to match, so an ingest path that
        // processes an item twice cannot double-translate it.
        val once: String = interpolate("%S2 %E5")

        assertEquals(once, interpolate(once))
    }

    @Test
    fun aPercentThatIsNotATokenIsNotTouched() {
        // Ordinary in a title: a battery percentage, a discount, a filename.
        assertEquals("100% Wolf", interpolate("100% Wolf"))
    }

    @Test
    fun aLetterWithoutDigitsIsNotAToken() {
        // "%S" alone says nothing about which season, and treating it as one
        // would hand the translator an empty number.
        assertEquals("%S and %E", interpolate("%S and %E"))
    }

    @Test
    fun anEmptyTitleStaysEmpty() {
        assertEquals("", interpolate(""))
    }

    @Test
    fun aLowercaseTokenResolvesThroughItsOwnRegistration() {
        // The pattern accepts either case, so a registry that only registered
        // the uppercase letter must not silently answer for the lowercase one —
        // they are different keys and could mean different things.
        assertEquals("%s2", interpolate("%s2"))
        assertEquals("Seizoen 2", TitleTokens.interpolate("%s2", mapOf("s" to "token.season"), ::dutch))
    }
}
