// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlayerErrorEvent
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.Fetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// A server that answers with whatever the test says, so what is under test is
// the player's handling rather than any transport.
private class StubFetcher(private val status: Int, private val body: String) : Fetcher {
    var requested: String? = null
        private set

    override suspend fun fetch(url: String, opts: FetchOptions): FetchResponse {
        requested = url
        return FetchResponse(status = status, body = body)
    }
}

// One line per item, which is enough of a format to test a parser seam without
// pulling in a serializer.
private fun linesParser(raw: String): List<PlaylistItem> =
    raw.lines().filter { it.isNotBlank() }.map { TestItem(it.trim()) }

class LoadQueueTest {

    private fun playerFetching(status: Int, body: String): Pair<ComposedPlayer, StubFetcher> {
        val fetcher = StubFetcher(status, body)
        return ComposedPlayer(backend = FakeMediaBackend(), fetcher = fetcher) to fetcher
    }

    @Test
    fun aFetchedPlaylistBecomesTheQueue() = runTest {
        val (player, fetcher) = playerFetching(200, "a\nb\nc")

        player.loadQueue("https://server.test/playlist", ::linesParser)

        assertEquals(listOf("a", "b", "c"), player.queue().map { it.id })
        assertEquals("https://server.test/playlist", fetcher.requested)
    }

    @Test
    fun theResolvingAndReadyEventsBracketTheWork() = runTest {
        // Both were declared in the event registry with nothing emitting them.
        // A consumer showing a spinner while a playlist loads binds to the pair.
        val (player, _) = playerFetching(200, "a\nb")
        val order: MutableList<String> = mutableListOf()
        player.on(CoreEvents.PlaylistResolving) { order += "resolving:${it.url}" }
        player.on(CoreEvents.PlaylistReady) { order += "ready:${it.length.toInt()}" }

        player.loadQueue("https://server.test/p", ::linesParser)

        assertEquals(listOf("resolving:https://server.test/p", "ready:2"), order)
    }

    @Test
    fun aPlaylistThatNeverArrivedIsAFetchError() = runTest {
        val (player, _) = playerFetching(503, "")

        val failure: PlayerError = assertFailsWith { player.loadQueue("https://server.test/p", ::linesParser) }

        assertEquals(CoreErrorCodes.PLAYLIST_FETCH_ERROR, failure.code)
        assertTrue(failure.isHttp(5), "the status did not reach the error's context")
    }

    @Test
    fun aPlaylistThatArrivedUnreadableIsAParseError() = runTest {
        // A different problem needing a different answer: retrying will not
        // help, and a consumer showing "check your connection" would be wrong.
        val (player, _) = playerFetching(200, "not the format you were promised")

        val failure: PlayerError = assertFailsWith {
            player.loadQueue("https://server.test/p") { error("no closing bracket") }
        }

        assertEquals(CoreErrorCodes.PLAYLIST_PARSE_ERROR, failure.code)
    }

    @Test
    fun aFailureReachesBothTheSpecificListenerAndTheGeneralOne() = runTest {
        // A caller awaiting this playlist listens for playlistResolveError; a
        // consumer with one failure surface listens for error, and should not
        // have to subscribe to every specific failure to know something broke.
        val (player, _) = playerFetching(404, "")
        val specific: MutableList<PlayerErrorEvent> = mutableListOf()
        val general: MutableList<PlayerErrorEvent> = mutableListOf()
        player.on(CoreEvents.PlaylistResolveError) { specific += it }
        player.on(CoreEvents.Error) { general += it }

        assertFailsWith<PlayerError> { player.loadQueue("https://server.test/p", ::linesParser) }

        assertEquals(1, specific.size)
        assertEquals(specific, general)
        assertEquals(CoreErrorCodes.PLAYLIST_FETCH_ERROR, specific.single().code)
    }

    @Test
    fun aFailedLoadLeavesTheOldQueueAlone() = runTest {
        // Emptying the queue because a refresh failed would take away what the
        // viewer was already listening to.
        val (player, _) = playerFetching(500, "")
        player.queue(listOf(TestItem("already-playing")))

        assertFailsWith<PlayerError> { player.loadQueue("https://server.test/p", ::linesParser) }

        assertEquals(listOf("already-playing"), player.queue().map { it.id })
    }

    @Test
    fun playNowReplacesTheQueueAndStartsIt() = runTest {
        val player = ComposedPlayer(backend = FakeMediaBackend())
        player.queue(listOf(TestItem("old")))
        player.setup()

        player.playNow(listOf(TestItem("a"), TestItem("b")))

        assertEquals(listOf("a", "b"), player.queue().map { it.id })
        assertEquals("a", player.item()?.id)
    }

    @Test
    fun playNowCanStartSomewhereOtherThanTheTop() = runTest {
        // Picking track four of an album is the everyday case, and a caller
        // that already has the id should not have to find its index.
        val player = ComposedPlayer(backend = FakeMediaBackend())
        player.setup()

        player.playNow(listOf(TestItem("a"), TestItem("b"), TestItem("c")), startId = "c")

        assertEquals("c", player.item()?.id)
    }

    @Test
    fun playNowWithNothingToPlayChangesNothing() = runTest {
        // A filtered list that came back empty has nothing to play, and
        // clearing the queue would be a worse answer than doing nothing.
        val player = ComposedPlayer(backend = FakeMediaBackend())
        player.queue(listOf(TestItem("already-playing")))
        player.setup()

        player.playNow(emptyList())

        assertEquals(listOf("already-playing"), player.queue().map { it.id })
    }

    @Test
    fun aPlayerWithNoTransportSaysSoRatherThanFailingObscurely() = runTest {
        // A player built without a fetcher is a supported shape — the host has
        // its own HTTP stack — and asking it to load a playlist should name the
        // missing piece.
        val player = ComposedPlayer(backend = FakeMediaBackend())

        assertFailsWith<PlayerError> { player.loadQueue("https://server.test/p", ::linesParser) }
    }
}
