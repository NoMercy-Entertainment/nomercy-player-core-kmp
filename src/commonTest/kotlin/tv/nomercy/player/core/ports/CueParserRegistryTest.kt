// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.cues.Cue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A parser that claims one extension and returns one cue, so what is under test
// is the registry's choice rather than any parsing.
private class ExtensionParser(
    override val id: String,
    private val extension: String,
    private val label: String = id,
) : CueParser<String> {
    override fun canParse(url: String, contentType: String?): Boolean = url.endsWith(extension)

    override fun parse(raw: String, baseUrl: String?): List<Cue<String>> =
        listOf(Cue(start = 0.0, end = 1.0, payload = label))
}

class CueParserRegistryTest {

    private fun registry(): CueParserRegistry = CueParserRegistry()

    @Test
    fun anEmptyRegistryResolvesNothingRatherThanGuessing() {
        assertNull(registry().resolve("subtitles.vtt"))
    }

    @Test
    fun aRegisteredParserHandlesWhatItClaims() {
        val subject: CueParserRegistry = registry()
        subject.register(ExtensionParser("vtt", ".vtt"))

        assertEquals("vtt", subject.resolve("https://server.test/a/subs.vtt")?.id)
        assertNull(
            subject.resolve("https://server.test/a/lyrics.lrc"),
            "a parser answered for a format it does not know",
        )
    }

    @Test
    fun theMostRecentlyRegisteredParserWins() {
        // The whole ordering rule, and what makes overriding a built-in just a
        // matter of registering one that does the same job.
        val subject: CueParserRegistry = registry()
        subject.register(ExtensionParser("vtt", ".vtt", label = "built-in"))
        subject.register(ExtensionParser("fillz:vtt", ".vtt", label = "consumer"))

        val chosen: CueParser<*>? = subject.resolve("subs.vtt")

        assertEquals("fillz:vtt", chosen?.id)
        assertEquals("consumer", chosen?.parse("")?.single()?.payload)
    }

    @Test
    fun aBuiltInSeededAtLowestPriorityLosesToEverythingRegisteredLater() {
        // Order of registration is the wrong lever for a built-in: it is
        // registered first by construction. Seeding it at the bottom is how it
        // stays overridable without a consumer knowing it was there.
        val subject: CueParserRegistry = registry()
        subject.register(ExtensionParser("consumer", ".vtt"))
        subject.register(ExtensionParser("built-in", ".vtt"), atLowestPriority = true)

        assertEquals("consumer", subject.resolve("subs.vtt")?.id)
    }

    @Test
    fun reRegisteringAnIdReplacesItRatherThanStackingUp() {
        // A plugin re-registering on every setup would otherwise grow the list
        // forever, and every stale copy would still be consulted.
        val subject: CueParserRegistry = registry()
        subject.register(ExtensionParser("vtt", ".vtt", label = "first"))
        subject.register(ExtensionParser("vtt", ".vtt", label = "second"))

        assertEquals(listOf("vtt"), subject.list())
        assertEquals("second", subject.resolve("subs.vtt")?.parse("")?.single()?.payload)
    }

    @Test
    fun unregisteringLetsTheNextParserAnswer() {
        // A plugin unloading must not take the format with it when something
        // else can still handle it.
        val subject: CueParserRegistry = registry()
        subject.register(ExtensionParser("built-in", ".vtt"))
        subject.register(ExtensionParser("plugin", ".vtt"))

        subject.unregister("plugin")

        assertEquals("built-in", subject.resolve("subs.vtt")?.id)
    }

    @Test
    fun unregisteringSomethingThatWasNeverThereIsNotAnError() {
        registry().unregister("never-registered")
    }

    @Test
    fun aParserCanBeFoundByIdWithoutAUrl() {
        // A consumer that wants the LRC parser to parse a string it already has
        // should not have to invent a filename to get at it.
        val subject: CueParserRegistry = registry()
        subject.register(ExtensionParser("lrc", ".lrc"))

        assertTrue(subject.findById("lrc") != null)
        assertNull(subject.findById("vtt"))
    }

    @Test
    fun contentTypeReachesTheParserThatAsksForIt() {
        // A server that serves subtitles from a path with no extension is
        // ordinary, and the content type is then the only signal there is.
        val subject: CueParserRegistry = registry()
        subject.register(
            object : CueParser<String> {
                override val id: String = "by-type"
                override fun canParse(url: String, contentType: String?): Boolean = contentType == "text/vtt"
                override fun parse(raw: String, baseUrl: String?): List<Cue<String>> = emptyList()
            },
        )

        assertEquals("by-type", subject.resolve("https://server.test/subtitle/42", "text/vtt")?.id)
        assertNull(subject.resolve("https://server.test/subtitle/42"))
    }

    @Test
    fun disposingClearsEverything() {
        val subject: CueParserRegistry = registry()
        subject.register(ExtensionParser("vtt", ".vtt"))

        subject.dispose()

        assertEquals(emptyList(), subject.list())
    }
}
