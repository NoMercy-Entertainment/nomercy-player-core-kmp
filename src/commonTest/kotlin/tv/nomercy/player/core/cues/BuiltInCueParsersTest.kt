// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.cues

import tv.nomercy.player.core.controllers.ComposedPlayer
import tv.nomercy.player.core.events.CueEvent
import tv.nomercy.player.core.ports.CueParser
import tv.nomercy.player.core.ports.CueParserRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SUBTITLE_FILE = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\n<i>Hello</i> there\n"
private const val LYRICS_FILE = "[ar:Artist]\n[00:12.00]First line\n[00:15.50]Second line"
private const val SPRITE_FILE = "WEBVTT\n\n00:00:00.000 --> 00:00:10.000\nsheet.webp#xywh=0,0,320,180\n"
private const val SECONDS_TOLERANCE = 1e-6

// A parser for a format this library has never heard of, to prove a consumer
// still outranks what the kit seeded.
private class KaraokeParser : CueParser {
    override val id: String = "fillz:karaoke"

    override fun canParse(url: String, contentType: String?): Boolean = url.endsWith(".vtt")

    override fun parse(raw: String, baseUrl: String?): List<CueEvent> =
        listOf(CueEvent(startTime = 0.0, endTime = 1.0, text = "consumer"))
}

// The gap this closes: the parsers existed and no player knew about them, so
// every one of these resolved to null and nothing could read a subtitle or a
// lyric file on any platform.
class BuiltInCueParsersTest {

    @Test
    fun aPlayerReadsSubtitleVttWithoutAnyoneRegisteringAParser() {
        assertEquals("vtt", ComposedPlayer().resolveCueParser("https://server.test/a/subs.vtt")?.id)
    }

    @Test
    fun aPlayerReadsLrcWithoutAnyoneRegisteringAParser() {
        assertEquals("lrc", ComposedPlayer().resolveCueParser("https://server.test/a/song.lrc")?.id)
    }

    @Test
    fun aSpriteSheetGetsTheSpriteParserRatherThanTheSubtitleOne() {
        // Both are `.vtt`, and before the file arrives the url is the only thing
        // that tells them apart.
        val player = ComposedPlayer()

        assertEquals("sprite-vtt", player.resolveCueParser("https://server.test/a/movie.sprite.vtt")?.id)
        assertEquals("sprite-vtt", player.resolveCueParser("https://server.test/a/movie.sprites.vtt")?.id)
    }

    @Test
    fun aQueryStringDoesNotHideTheExtension() {
        // A signed playback url carries one, and every NoMercy subtitle url is
        // signed.
        val resolved: CueParser? = ComposedPlayer().resolveCueParser("https://server.test/subs.vtt?token=abc")

        assertEquals("vtt", resolved?.id)
    }

    @Test
    fun aContentTypeIsEnoughWhenThePathHasNoExtension() {
        val player = ComposedPlayer()

        assertEquals("vtt", player.resolveCueParser("https://server.test/subtitle/42", "text/vtt")?.id)
        assertEquals("lrc", player.resolveCueParser("https://server.test/lyrics/42", "text/lrc")?.id)
    }

    @Test
    fun aFormatNobodyRegisteredStillResolvesToNothing() {
        // Seeding three parsers must not turn into three parsers that answer for
        // anything: a wrong parser is worse than none, because it fails after
        // claiming the file.
        assertNull(ComposedPlayer().resolveCueParser("https://server.test/a/song.karaoke"))
    }

    @Test
    fun aConsumersParserOutranksTheBuiltInItOverlaps() {
        val player = ComposedPlayer()
        player.registerCueParser(KaraokeParser())

        assertEquals("fillz:karaoke", player.resolveCueParser("subs.vtt")?.id)
    }

    @Test
    fun unregisteringABuiltInByIdLetsTheFormatGo() {
        val player = ComposedPlayer()
        player.unregisterCueParser("lrc")

        assertNull(player.resolveCueParser("song.lrc"))
    }

    @Test
    fun theResolvedSubtitleParserActuallyReadsTheFile() {
        // Resolution alone proves nothing: an id that maps to a parser which
        // cannot parse leaves the consumer exactly where they started.
        val parser: CueParser? = ComposedPlayer().resolveCueParser("subs.vtt")
        assertNotNull(parser)

        val cues: List<CueEvent> = parser.parse(SUBTITLE_FILE)

        assertEquals(1, cues.size)
        assertEquals(1.0, cues.single().startTime, SECONDS_TOLERANCE)
        assertEquals(4.0, cues.single().endTime, SECONDS_TOLERANCE)
        assertEquals("Hello there", cues.single().text, "inline markup reached the viewer")
    }

    @Test
    fun theResolvedLyricsParserActuallyReadsTheFile() {
        val parser: CueParser? = ComposedPlayer().resolveCueParser("song.lrc")
        assertNotNull(parser)

        val cues: List<CueEvent> = parser.parse(LYRICS_FILE)

        assertEquals(listOf("First line", "Second line"), cues.map { it.text })
        assertEquals(12.0, cues.first().startTime, SECONDS_TOLERANCE)
    }

    @Test
    fun theResolvedSpriteParserNamesTheSheetRelativeToTheVtt() {
        val parser: CueParser? = ComposedPlayer().resolveCueParser("https://server.test/a/movie.sprite.vtt")
        assertNotNull(parser)

        val cues: List<CueEvent> = parser.parse(SPRITE_FILE, baseUrl = "https://server.test/a/movie.sprite.vtt")

        assertEquals("https://server.test/a/sheet.webp#xywh=0,0,320,180", cues.single().text)
    }

    @Test
    fun aBareRegistryCanBeSeededTheSameWay() {
        // The plugin default and the player field both go through this, so a
        // registry a host builds itself is not a second-class one.
        val ids: List<String> = CueParserRegistry().registerBuiltIns().list()

        assertTrue(ids.containsAll(listOf("lrc", "vtt", "sprite-vtt")), "seeded ids were $ids")
    }
}
