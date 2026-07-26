// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.controllers.ComposedPlayer
import tv.nomercy.player.core.controllers.FakeMediaBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A factory that claims one extension, so what is under test is the registry's
// choice rather than any streaming.
private class ExtensionFactory(
    override val id: String,
    private val extension: String,
) : StreamFactory {
    override fun canPlay(url: String, contentType: String?): Boolean = url.endsWith(extension)

    override fun create(opts: StreamFactoryOptions): StreamSource =
        throw NotImplementedError("no source is needed to test which factory was chosen")
}

class StreamRegistryTest {

    private fun registry(): StreamRegistry = StreamRegistry()

    @Test
    fun anUnclaimedUrlIsTheOrdinaryCaseNotAFailure() {
        // Most urls are played by the backend directly and never want a factory
        // at all. Treating absence as an error would make every plain mp4 a
        // failure.
        assertNull(registry().resolve("https://server.test/movie.mp4"))
    }

    @Test
    fun aFactoryHandlesWhatItClaims() {
        val subject: StreamRegistry = registry()
        subject.register(ExtensionFactory("hls", ".m3u8"))

        assertEquals("hls", subject.resolve("https://server.test/watch/a.m3u8")?.id)
        assertNull(subject.resolve("https://server.test/watch/a.mpd"))
    }

    @Test
    fun theMostRecentlyRegisteredFactoryWins() {
        val subject: StreamRegistry = registry()
        subject.register(ExtensionFactory("built-in-hls", ".m3u8"))
        subject.register(ExtensionFactory("drm-hls", ".m3u8"))

        assertEquals("drm-hls", subject.resolve("a.m3u8")?.id)
    }

    @Test
    fun aBuiltInSeededAtLowestPriorityLosesToAnythingRegisteredLater() {
        val subject: StreamRegistry = registry()
        subject.register(ExtensionFactory("consumer", ".m3u8"))
        subject.register(ExtensionFactory("built-in", ".m3u8"), atLowestPriority = true)

        assertEquals("consumer", subject.resolve("a.m3u8")?.id)
    }

    @Test
    fun theListReadsInTheOrderResolveWouldPick() {
        // A consumer reading this to decide what to override should see the
        // winner at the top, not have to reverse it in their head.
        val subject: StreamRegistry = registry()
        subject.register(ExtensionFactory("first", ".m3u8"))
        subject.register(ExtensionFactory("second", ".m3u8"))

        assertEquals(listOf("second", "first"), subject.list())
        assertEquals(subject.list().first(), subject.resolve("a.m3u8")?.id)
    }

    @Test
    fun reRegisteringAnIdReplacesItRatherThanStackingUp() {
        val subject: StreamRegistry = registry()
        subject.register(ExtensionFactory("hls", ".m3u8"))
        subject.register(ExtensionFactory("hls", ".m3u8"))

        assertEquals(listOf("hls"), subject.list())
    }

    @Test
    fun unregisteringLetsTheNextFactoryAnswer() {
        val subject: StreamRegistry = registry()
        subject.register(ExtensionFactory("built-in", ".m3u8"))
        subject.register(ExtensionFactory("plugin", ".m3u8"))

        subject.unregister("plugin")

        assertEquals("built-in", subject.resolve("a.m3u8")?.id)
    }

    @Test
    fun registrationChainsSoAHostCanWireSeveralAtOnce() {
        val player = ComposedPlayer(backend = FakeMediaBackend())

        player
            .registerStream(ExtensionFactory("hls", ".m3u8"))
            .registerStream(ExtensionFactory("dash", ".mpd"))

        assertEquals(listOf("dash", "hls"), player.streams())
        assertTrue(player.getStreamFactory("hls") != null)
    }

    @Test
    fun unregisteringThroughThePlayerChainsToo() {
        val player = ComposedPlayer(backend = FakeMediaBackend())
        player.registerStream(ExtensionFactory("hls", ".m3u8"))

        player.unregisterStream("hls").unregisterStream("never-registered")

        assertEquals(emptyList(), player.streams())
    }
}
