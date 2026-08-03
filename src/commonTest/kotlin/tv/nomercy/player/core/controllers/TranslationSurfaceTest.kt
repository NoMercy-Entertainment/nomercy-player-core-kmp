// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.ports.Translations
import tv.nomercy.player.core.ports.Translator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tv.nomercy.player.testing.FakeMediaBackend

// A translator that only records, so what reaches the port is the assertion.
private class RecordingTranslator : Translator {
    val added: MutableList<Translations> = mutableListOf()
    val removed: MutableList<Pair<String, String?>> = mutableListOf()
    var current: String = "en"
    private val table: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    override fun t(key: String, vars: Map<String, String>): String = table[current]?.get(key) ?: key

    override fun language(): String = current

    override suspend fun language(lang: String) {
        current = lang
    }

    override fun addTranslations(bundle: Translations) {
        added += bundle
        bundle.forEach { (lang, entries) -> table.getOrPut(lang) { mutableMapOf() }.putAll(entries) }
    }

    override fun translation(lang: String, key: String): String? = table[lang]?.get(key)

    override fun translation(lang: String, key: String, value: String) {
        table.getOrPut(lang) { mutableMapOf() }[key] = value
    }

    override fun removeTranslations(prefix: String, lang: String?) {
        removed += prefix to lang
    }

    override fun dispose() = table.clear()
}

// Translation through the player.
//
// A plugin has the player, not the translator, so the strings it ships and the
// strings it takes away with it both go through here. The half that matters is
// the other one: a host with its own i18n system injects nothing, and none of
// this may throw when it does.
class TranslationSurfaceTest {

    private fun playerWith(translator: Translator?): ComposedPlayer =
        ComposedPlayer(backend = FakeMediaBackend(), translator = translator)

    @Test
    fun aKeyThatResolvedToItselfIsReportedAsMissingWhileStillAnsweringWithTheKey() = runTest {
        val misses: MutableList<Pair<String, Map<String, String>>> = mutableListOf()
        val translator = RecordingTranslator()
        val player = ComposedPlayer(
            backend = FakeMediaBackend(),
            translator = translator,
            onMissingTranslation = { key, vars -> misses += key to vars },
        )
        player.addTranslations(mapOf("en" to mapOf("plugin.chapters.next" to "Next chapter")))

        val translated: String = player.t("plugin.chapters.next")
        val untranslated: String = player.t("plugin.chapters.previous", mapOf("n" to "2"))

        // The visible answer does not change: a key on screen is the bug report,
        // and a blank label is not.
        assertEquals("Next chapter", translated)
        assertEquals("plugin.chapters.previous", untranslated)

        // Only the miss, and it carries the vars so a report says which call site.
        assertEquals(listOf("plugin.chapters.previous" to mapOf("n" to "2")), misses.toList())
    }

    @Test
    fun aPlayerWithNoTranslatorReportsEveryKeyAsMissing() = runTest {
        val misses: MutableList<String> = mutableListOf()
        val player = ComposedPlayer(
            backend = FakeMediaBackend(),
            onMissingTranslation = { key, _ -> misses += key },
        )

        assertEquals("plugin.chapters.next", player.t("plugin.chapters.next"))

        // From the outside "no translator" and "no entry" are one thing, a
        // string that never got translated, and a host wiring this up to find
        // untranslated keys needs to hear about both.
        assertEquals(listOf("plugin.chapters.next"), misses.toList())
    }

    @Test
    fun aPluginsStringsReachTheTranslatorAndComeBackTranslated() = runTest {
        val translator = RecordingTranslator()
        val player: ComposedPlayer = playerWith(translator)

        player.addTranslations(mapOf("en" to mapOf("plugin.chapters.next" to "Next chapter")))

        assertEquals(1, translator.added.size)
        assertEquals("Next chapter", player.t("plugin.chapters.next"))
    }

    @Test
    fun aPluginLeavingTakesItsStringsByPrefix() {
        val translator = RecordingTranslator()

        playerWith(translator).removeTranslations("plugin.chapters.")

        val expected: List<Pair<String, String?>> = listOf("plugin.chapters." to null)
        assertEquals(expected, translator.removed)
    }

    @Test
    fun switchingLanguageIsVisibleThroughThePlayer() = runTest {
        val player: ComposedPlayer = playerWith(RecordingTranslator())

        player.language("nl")

        assertEquals("nl", player.language())
    }

    @Test
    fun aSingleStringCanBeReadAndWrittenPerLanguage() {
        val player: ComposedPlayer = playerWith(RecordingTranslator())

        player.translation("nl", "player.play", "Afspelen")

        assertEquals("Afspelen", player.translation("nl", "player.play"))
        assertNull(player.translation("de", "player.play"), "a string leaked across languages")
    }

    @Test
    fun aPlayerWithNoTranslatorAnswersInsteadOfThrowing() = runTest {
        // The common case for a host that already runs i18next or its own
        // catalogue: it injects nothing, and a plugin calling any of this must
        // not take the player down.
        val player: ComposedPlayer = playerWith(null)

        player.addTranslations(mapOf("en" to mapOf("plugin.x.y" to "Y")))
        player.translation("en", "plugin.x.y", "Y")
        player.removeTranslations("plugin.x.")
        player.language("nl")

        assertEquals("plugin.x.y", player.t("plugin.x.y"), "an untranslated string should be its key")
        assertNull(player.translation("en", "plugin.x.y"))
    }

    @Test
    fun anUnconfiguredPlayerClaimsNoLanguageRatherThanEnglish() {
        // Answering "en" would have a UI pick text direction and number
        // formatting from a value that only means nobody chose.
        assertEquals("", playerWith(null).language())
    }
}
