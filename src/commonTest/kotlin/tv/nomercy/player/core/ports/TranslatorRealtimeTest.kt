// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.testing.FakeRealtimeChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeTranslator(private var current: String = "en") : Translator {
    private val table = mutableMapOf<String, MutableMap<String, String>>()

    override fun t(key: String, vars: Map<String, String>): String {
        var text: String = table[current]?.get(key) ?: key
        vars.forEach { (name, value) -> text = text.replace("{$name}", value) }
        return text
    }

    override fun language(): String = current

    override suspend fun language(lang: String) {
        current = lang
    }

    override fun addTranslations(bundle: Translations) {
        bundle.forEach { (lang, entries) -> table.getOrPut(lang) { mutableMapOf() }.putAll(entries) }
    }

    override fun translation(lang: String, key: String): String? = table[lang]?.get(key)

    override fun translation(lang: String, key: String, value: String) {
        table.getOrPut(lang) { mutableMapOf() }[key] = value
    }

    override fun removeTranslations(prefix: String, lang: String?) {
        val targets: Collection<MutableMap<String, String>> =
            lang?.let { listOfNotNull(table[it]) } ?: table.values
        targets.forEach { entries -> entries.keys.filter { it.startsWith(prefix) }.forEach(entries::remove) }
    }

    override fun dispose() {
        table.clear()
    }
}

class TranslatorRealtimeTest {

    @Test
    fun placeholdersAreFilledFromTheVarsMap() {
        val translator = FakeTranslator()
        translator.addTranslations(mapOf("en" to mapOf("greeting" to "Hi {name}")))

        assertEquals("Hi Sam", translator.t("greeting", mapOf("name" to "Sam")))
    }

    @Test
    fun aMissingKeyComesBackAsTheKey() {
        // A visible "player.play" in the UI is a bug report; a blank button is
        // a mystery.
        assertEquals("player.play", FakeTranslator().t("player.play"))
    }

    @Test
    fun switchingLanguageChangesWhatTIsResolvedAgainst() = runTest {
        val translator = FakeTranslator()
        translator.addTranslations(
            mapOf(
                "en" to mapOf("play" to "Play"),
                "nl" to mapOf("play" to "Afspelen"),
            ),
        )
        assertEquals("Play", translator.t("play"))

        translator.language("nl")

        assertEquals("Afspelen", translator.t("play"))
        assertEquals("nl", translator.language())
    }

    @Test
    fun removingByPrefixTakesAPluginsStringsWithIt() {
        val translator = FakeTranslator()
        translator.addTranslations(
            mapOf("en" to mapOf("plugin.lyrics.title" to "Lyrics", "play" to "Play")),
        )

        translator.removeTranslations("plugin.lyrics.")

        assertNull(translator.translation("en", "plugin.lyrics.title"))
        assertEquals("Play", translator.translation("en", "play"))
    }

    @Test
    fun aChannelSendsReceivesAndReportsItsState() {
        val channel = FakeRealtimeChannel(RealtimeState.CONNECTING)
        val received = mutableListOf<Any?>()
        channel.on(RealtimeEvent.MESSAGE) { received.add(it) }
        assertEquals(RealtimeState.CONNECTING, channel.readyState)

        channel.open()
        channel.send("ping")
        channel.deliver(RealtimeEvent.MESSAGE, "pong")

        assertEquals(RealtimeState.OPEN, channel.readyState)
        assertEquals(listOf("ping"), channel.sentText)
        assertEquals(listOf<Any?>("pong"), received)
    }

    @Test
    fun binaryAndTextShareTheSameChannelAndStayApart() {
        val channel = FakeRealtimeChannel(RealtimeState.CONNECTING)
        channel.open()

        channel.send("text")
        channel.send("bytes".encodeToByteArray())

        assertEquals(listOf("text"), channel.sentText)
        assertEquals(listOf("bytes"), channel.sentBytes.map { it.decodeToString() })
    }

    @Test
    fun closingReportsClosedAndTellsItsListeners() {
        val channel = FakeRealtimeChannel(RealtimeState.CONNECTING)
        var closeReason: Any? = null
        channel.on(RealtimeEvent.CLOSE) { closeReason = it }
        channel.open()

        channel.close(code = 1000, reason = "done")

        assertEquals(RealtimeState.CLOSED, channel.readyState)
        assertEquals("done", closeReason)
    }
}
