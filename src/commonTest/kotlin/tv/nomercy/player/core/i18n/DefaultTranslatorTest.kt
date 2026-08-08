// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.i18n

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.ports.TranslationLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The translator the kit ships.
 *
 * The cases are the web's `__tests__/translator.test.ts` and the resolution half
 * of `__tests__/translator-bcp47.test.ts`.
 */
class DefaultTranslatorTest {

    private fun translator(
        language: String? = null,
        translations: Map<String, Map<String, String>> = emptyMap(),
        loader: TranslationLoader? = null,
        onMissing: ((String, String) -> String)? = null,
        fallback: String? = "en",
    ): DefaultTranslator = DefaultTranslator(
        DefaultTranslatorOptions(
            language = language,
            translations = translations,
            loadTranslations = loader,
            onMissingTranslation = onMissing,
            fallbackLanguage = fallback,
        ),
    )

    @Test
    fun theLanguageDefaultsToEnglish() {
        assertEquals("en", translator().language())
    }

    @Test
    fun anInitialLanguageIsHonoured() {
        assertEquals("nl", translator(language = "nl").language())
    }

    @Test
    fun aSeededBundleIsRead() {
        val subject = translator(translations = mapOf("en" to mapOf("play" to "Play")))

        assertEquals("Play", subject.t("play"))
    }

    @Test
    fun anUntranslatedKeyAnswersWithItself() {
        // Not an empty string. A button labelled with its own key is a bug
        // report somebody can act on; a blank button is not.
        assertEquals("play", translator().t("play"))
    }

    @Test
    fun theMissingHandlerAnswersInstead() {
        val subject = translator(onMissing = { key, language -> "$language:$key" })

        assertEquals("en:play", subject.t("play"))
    }

    @Test
    fun theMissingHandlerDoesNotRunForAKeyThatIsThere() {
        var calls = 0
        val subject = translator(
            translations = mapOf("en" to mapOf("play" to "Play")),
            onMissing = { _, _ -> calls += 1; "missed" },
        )

        assertEquals("Play", subject.t("play"))
        assertEquals(0, calls)
    }

    @Test
    fun aVariableIsReplaced() {
        val subject = translator(translations = mapOf("en" to mapOf("ep" to "Episode {n}")))

        assertEquals("Episode 4", subject.t("ep", mapOf("n" to "4")))
    }

    @Test
    fun everyVariableIsReplaced() {
        val subject = translator(
            translations = mapOf("en" to mapOf("ep" to "S{s}E{e}")),
        )

        assertEquals("S2E7", subject.t("ep", mapOf("s" to "2", "e" to "7")))
    }

    @Test
    fun aVariableNobodySuppliedIsLeftAsWritten() {
        // Left rather than blanked, so the gap names the variable that was
        // missing instead of producing "Episode " and a support ticket.
        val subject = translator(translations = mapOf("en" to mapOf("ep" to "Episode {n}")))

        assertEquals("Episode {n}", subject.t("ep"))
        assertEquals("Episode {n}", subject.t("ep", mapOf("other" to "4")))
    }

    @Test
    fun theMissingHandlersAnswerIsInterpolatedToo() {
        val subject = translator(onMissing = { key, _ -> "[$key {n}]" })

        assertEquals("[ep 4]", subject.t("ep", mapOf("n" to "4")))
    }

    // ── The fallback chain ───────────────────────────────────────────────────

    @Test
    fun aRegionalKeyComesFromTheRegionalBundle() {
        val subject = translator(
            language = "pt-BR",
            translations = mapOf(
                "pt-BR" to mapOf("play" to "Reproduzir"),
                "pt" to mapOf("play" to "Reproduzir PT"),
            ),
        )

        assertEquals("Reproduzir", subject.t("play"))
    }

    @Test
    fun aKeyTheRegionalBundleLacksComesFromItsParent() {
        val subject = translator(
            language = "pt-BR",
            translations = mapOf(
                "pt-BR" to mapOf("play" to "Reproduzir"),
                "pt" to mapOf("pause" to "Pausar"),
            ),
        )

        assertEquals("Pausar", subject.t("pause"))
    }

    @Test
    fun aKeyNoVariantHasComesFromTheGlobalDefault() {
        val subject = translator(
            language = "pt-BR",
            translations = mapOf(
                "pt-BR" to emptyMap(),
                "en" to mapOf("stop" to "Stop"),
            ),
        )

        assertEquals("Stop", subject.t("stop"))
    }

    @Test
    fun aNullFallbackLanguageDisablesTheGlobalDefault() {
        // What a translator under test wants: a missing string is visible
        // rather than quietly English.
        val subject = translator(
            language = "pt-BR",
            translations = mapOf("en" to mapOf("stop" to "Stop")),
            fallback = null,
        )

        assertEquals("stop", subject.t("stop"))
    }

    // ── Switching language ───────────────────────────────────────────────────

    @Test
    fun theActiveLanguageSwitches() = runTest {
        val subject = translator()

        subject.language("nl")

        assertEquals("nl", subject.language())
    }

    @Test
    fun theLoaderRunsOnTheFirstSwitchToANewLanguage() = runTest {
        val asked: MutableList<String> = mutableListOf()
        val subject = translator(
            loader = TranslationLoader { tag ->
                asked += tag
                mapOf("play" to "Afspelen")
            },
        )

        subject.language("nl")

        assertEquals(listOf("nl"), asked)
        assertEquals("Afspelen", subject.t("play"))
    }

    @Test
    fun theLoaderDoesNotRunAgainForALanguageAlreadyLoaded() = runTest {
        val asked: MutableList<String> = mutableListOf()
        val subject = translator(
            loader = TranslationLoader { tag -> asked += tag; emptyMap() },
        )

        subject.language("nl")
        subject.language("en")
        subject.language("nl")

        assertEquals(listOf("nl", "en"), asked)
    }

    @Test
    fun theLoaderIsNotAskedForALanguageThatWasSeeded() = runTest {
        val asked: MutableList<String> = mutableListOf()
        val subject = translator(
            translations = mapOf("nl" to mapOf("play" to "Afspelen")),
            loader = TranslationLoader { tag -> asked += tag; emptyMap() },
        )

        subject.language("nl")

        assertEquals(emptyList(), asked)
    }

    @Test
    fun aLoaderWithNothingToOfferIsNotAFailure() = runTest {
        val subject = translator(loader = TranslationLoader { null })

        subject.language("nl")

        assertEquals("play", subject.t("play"))
    }

    @Test
    fun aLoaderThatThrowsDoesNotBreakTheSwitch() = runTest {
        // Losing a translation is cosmetic; an exception here would take down
        // the surface that was mid-rebuild.
        val subject = translator(
            translations = mapOf("en" to mapOf("play" to "Play")),
            loader = TranslationLoader { error("no network") },
        )

        subject.language("nl")

        assertEquals("nl", subject.language())
        assertEquals("Play", subject.t("play"))
    }

    @Test
    fun switchingToARegionalTagLoadsItsParentToo() = runTest {
        val asked: MutableList<String> = mutableListOf()
        val subject = translator(
            loader = TranslationLoader { tag -> asked += tag; emptyMap() },
        )

        subject.language("pt-BR")

        assertEquals(listOf("pt-BR", "pt"), asked)
    }

    // ── Contributed bundles ──────────────────────────────────────────────────

    @Test
    fun aContributedBundleMergesIntoAnExistingLanguage() {
        val subject = translator(translations = mapOf("en" to mapOf("play" to "Play")))

        subject.addTranslations(mapOf("en" to mapOf("pause" to "Pause")))

        assertEquals("Play", subject.t("play"))
        assertEquals("Pause", subject.t("pause"))
    }

    @Test
    fun theLastWriteWins() {
        val subject = translator(translations = mapOf("en" to mapOf("play" to "Play")))

        subject.addTranslations(mapOf("en" to mapOf("play" to "Resume")))

        assertEquals("Resume", subject.t("play"))
    }

    /**
     * A plugin's static bundle must not satisfy the loader's contract.
     *
     * Marking it loaded would mean switching to that language afterwards skips
     * the fetch that carries the rest of the strings, and the chrome comes up
     * with one plugin translated and nothing else.
     */
    @Test
    fun aContributedBundleDoesNotSatisfyTheLoader() = runTest {
        val asked: MutableList<String> = mutableListOf()
        val subject = translator(
            loader = TranslationLoader { tag ->
                asked += tag
                mapOf("play" to "Afspelen")
            },
        )

        subject.addTranslations(mapOf("nl" to mapOf("cast" to "Casten")))
        subject.language("nl")

        assertEquals(listOf("nl"), asked)
        assertEquals("Afspelen", subject.t("play"))
        assertEquals("Casten", subject.t("cast"))
    }

    // ── One key at a time ────────────────────────────────────────────────────

    @Test
    fun aSingleKeyIsReadAndWritten() {
        val subject = translator()

        subject.translation("en", "play", "Play")

        assertEquals("Play", subject.translation("en", "play"))
        assertEquals("Play", subject.t("play"))
    }

    @Test
    fun anAbsentKeyReadsAsNull() {
        assertNull(translator().translation("en", "play"))
    }

    @Test
    fun writingASingleKeySeedsANewLanguage() {
        val subject = translator()

        subject.translation("nl", "play", "Afspelen")

        assertEquals("Afspelen", subject.translation("nl", "play"))
    }

    // ── Removal ──────────────────────────────────────────────────────────────

    @Test
    fun aPrefixLeavesEveryLanguageByDefault() {
        // How a plugin's strings leave with it.
        val subject = translator(
            translations = mapOf(
                "en" to mapOf("cast.start" to "Cast", "play" to "Play"),
                "nl" to mapOf("cast.start" to "Casten"),
            ),
        )

        subject.removeTranslations("cast.")

        assertNull(subject.translation("en", "cast.start"))
        assertNull(subject.translation("nl", "cast.start"))
        assertEquals("Play", subject.translation("en", "play"))
    }

    @Test
    fun removalIsScopedWhenALanguageIsNamed() {
        val subject = translator(
            translations = mapOf(
                "en" to mapOf("cast.start" to "Cast"),
                "nl" to mapOf("cast.start" to "Casten"),
            ),
        )

        subject.removeTranslations("cast.", "en")

        assertNull(subject.translation("en", "cast.start"))
        assertEquals("Casten", subject.translation("nl", "cast.start"))
    }

    @Test
    fun aPrefixNothingMatchesRemovesNothing() {
        val subject = translator(translations = mapOf("en" to mapOf("play" to "Play")))

        subject.removeTranslations("cast.")

        assertEquals("Play", subject.translation("en", "play"))
    }

    @Test
    fun disposingClearsEveryBundle() {
        val subject = translator(translations = mapOf("en" to mapOf("play" to "Play")))

        subject.dispose()

        assertNull(subject.translation("en", "play"))
        assertEquals("play", subject.t("play"))
    }
}
