// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.ports.ResolvedUrl
import tv.nomercy.player.core.ports.UrlCategory
import tv.nomercy.player.core.ports.UrlResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Url resolution through the player, where the bases live.
class UrlSurfaceTest {

    private val mediaBase = "https://server.test/api/v1"
    private val imageBase = "https://image.tmdb.org/t/p/w500"

    private suspend fun configured(): ComposedPlayer =
        ComposedPlayer(backend = FakeMediaBackend()).also {
            it.setup(PlayerConfig(baseUrl = mediaBase, baseImageUrl = imageBase))
        }

    @Test
    fun mediaJoinsToTheServerAndArtworkToTheImageHost() = runTest {
        // They are usually different hosts: posters come from a CDN, media from
        // the server the viewer is signed in to.
        val player: ComposedPlayer = configured()

        assertEquals(
            "https://server.test/api/v1/watch/abc.m3u8",
            player.resolveUrl("/watch/abc.m3u8").href,
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            player.resolveUrl("/poster.jpg", UrlCategory.POSTER).href,
        )
    }

    @Test
    fun artworkFallsBackToTheMediaBaseWhenNoImageHostIsConfigured() = runTest {
        // A self-hosted server that serves its own artwork is the common case,
        // and it should not have to set the same url twice.
        val player = ComposedPlayer(backend = FakeMediaBackend())
        player.setup(PlayerConfig(baseUrl = mediaBase))

        assertEquals(
            "https://server.test/api/v1/poster.jpg",
            player.resolveUrl("/poster.jpg", UrlCategory.CAST).href,
        )
    }

    @Test
    fun theBaseCanChangeWithoutRebuildingThePlayer() = runTest {
        // A self-hosted server moves: a tunnel comes up, a LAN address replaces
        // a public one. Tearing the player down to follow it would interrupt
        // playback for a change that does not affect the current stream.
        val player: ComposedPlayer = configured()

        player.baseUrl("https://192.168.2.60:7626")

        assertEquals("https://192.168.2.60:7626", player.baseUrl())
        assertEquals(
            "https://192.168.2.60:7626/watch/abc.m3u8",
            player.resolveUrl("/watch/abc.m3u8").href,
        )
    }

    @Test
    fun aPlayerThatWasNeverSetUpHasNoBase() {
        assertNull(ComposedPlayer(backend = FakeMediaBackend()).baseUrl())
    }

    @Test
    fun aHostResolverTakesOverAndCanStillAskForTheDefault() = runTest {
        // The common shape: sign the media urls, leave everything else alone.
        val player: ComposedPlayer = configured()
        player.urlResolver(
            UrlResolver { url, context ->
                val base: ResolvedUrl = context.defaultResolve(url)
                if (context.category == UrlCategory.MEDIA) {
                    base.copy(href = base.href + "?token=signed")
                } else {
                    base
                }
            },
        )

        assertEquals(
            "https://server.test/api/v1/watch/abc.m3u8?token=signed",
            player.resolveUrl("/watch/abc.m3u8").href,
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            player.resolveUrl("/poster.jpg", UrlCategory.POSTER).href,
        )
    }

    @Test
    fun theResolverIsToldWhichBaseAppliesToTheCategoryItWasAsked() = runTest {
        // Without this a resolver signing artwork would join it to the media
        // base and produce a url on the wrong host.
        val player: ComposedPlayer = configured()
        val seen: MutableList<String?> = mutableListOf()
        player.urlResolver(
            UrlResolver { url, context ->
                seen += context.baseUrl
                context.defaultResolve(url)
            },
        )

        player.resolveUrl("/a.m3u8")
        player.resolveUrl("/b.jpg", UrlCategory.POSTER)

        val expected: List<String?> = listOf(mediaBase, imageBase)
        assertEquals(expected, seen)
    }

    @Test
    fun removingTheResolverGoesBackToTheBuiltInOne() = runTest {
        val player: ComposedPlayer = configured()
        player.urlResolver(UrlResolver { _, _ -> ResolvedUrl(raw = "x", href = "https://replaced.test/x") })

        player.urlResolver(null)

        assertNull(player.urlResolver())
        assertEquals("https://server.test/api/v1/a.m3u8", player.resolveUrl("/a.m3u8").href)
    }
}
