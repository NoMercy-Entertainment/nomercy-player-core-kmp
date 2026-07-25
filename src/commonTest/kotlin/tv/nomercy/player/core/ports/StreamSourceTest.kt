// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeHlsSource : StreamSource {
    private var sourceState: StreamSourceState = StreamSourceState.IDLE

    override val kind: StreamKind = StreamKind.HLS

    override suspend fun attach() {
        sourceState = StreamSourceState.READY
    }

    override fun detach() {
        sourceState = StreamSourceState.IDLE
    }

    override fun destroy() {
        sourceState = StreamSourceState.IDLE
    }

    override fun state(): StreamSourceState = sourceState

    override fun levels(): List<StreamLevel> = listOf(
        StreamLevel(bitrate = 3_000_000, label = "1080p", height = 1080, index = 0),
        StreamLevel(bitrate = 800_000, label = "480p", height = 480, index = 1),
    )

    override fun on(event: String, fn: (Any?) -> Unit) = Unit
    override fun off(event: String, fn: (Any?) -> Unit) = Unit
}

private object FakeHlsFactory : StreamFactory {
    override val id: String = "hls"
    override fun canPlay(url: String, contentType: String?): Boolean =
        url.endsWith(".m3u8") || contentType == "application/vnd.apple.mpegurl"

    override fun create(opts: StreamFactoryOptions): StreamSource = FakeHlsSource()
}

class StreamSourceTest {

    @Test
    fun aFactoryClaimsOnlyTheUrlsItCanActuallyPlay() {
        assertTrue(FakeHlsFactory.canPlay("https://example.test/a.m3u8"))
        assertTrue(FakeHlsFactory.canPlay("https://example.test/a", "application/vnd.apple.mpegurl"))

        // A false yes takes the URL away from the backend that would have played
        // it, so claiming everything is worse than claiming nothing.
        assertFalse(FakeHlsFactory.canPlay("https://example.test/a.mp4"))
    }

    @Test
    fun aCreatedSourceAttachesAndReportsItsRenditions() = runTest {
        val source = FakeHlsFactory.create(StreamFactoryOptions(url = "https://example.test/a.m3u8"))
        assertEquals(StreamKind.HLS, source.kind)
        assertEquals(StreamSourceState.IDLE, source.state())

        source.attach()

        assertEquals(StreamSourceState.READY, source.state())
        assertEquals(2, source.levels().size)
    }

    @Test
    fun detachingLeavesTheSourceReusableAndDestroyingItDoesNot() = runTest {
        val source = FakeHlsFactory.create(StreamFactoryOptions(url = "https://example.test/a.m3u8"))
        source.attach()

        source.detach()
        assertEquals(StreamSourceState.IDLE, source.state())

        source.attach()
        assertEquals(StreamSourceState.READY, source.state())

        source.destroy()
        assertEquals(StreamSourceState.IDLE, source.state())
    }
}
