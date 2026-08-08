// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.cast

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.plugin.fakes.FakePluginHost
import tv.nomercy.player.core.plugin.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cast sender, driven without a television in the room.
 *
 * Which is the point of [CastSession] being a port: the disconnect-and-resume
 * path is the one that decides whether a viewer whose receiver drops off the
 * network keeps watching or stares at a stopped player, and it is unreachable
 * from a test that needs real hardware.
 */
class CastSenderPluginTest {

    private class FakeSession : CastSession {
        var connected: Boolean = false
        var loaded: CastMediaInfo? = null
        var loadedAt: Double = 0.0
        var loads: Int = 0
        val commands: MutableList<String> = mutableListOf()
        private var listener: ((CastSenderEvents) -> Unit)? = null

        override fun isConnected(): Boolean = connected
        override fun deviceName(): String? = "Living room"
        override suspend fun connect() { connected = true }
        override suspend fun disconnect() { connected = false }

        override suspend fun load(media: CastMediaInfo, positionSeconds: Double) {
            loaded = media
            loadedAt = positionSeconds
            loads += 1
        }

        override suspend fun play() { commands += "play" }
        override suspend fun pause() { commands += "pause" }
        override suspend fun stop() { commands += "stop" }
        override suspend fun seekTo(positionSeconds: Double) { commands += "seek:$positionSeconds" }
        override suspend fun setVolume(level: Double) { commands += "volume:$level" }
        override suspend fun setMuted(muted: Boolean) { commands += "muted:$muted" }

        override fun observe(listener: (CastSenderEvents) -> Unit): () -> Unit {
            this.listener = listener
            return { this.listener = null }
        }

        fun report(event: CastSenderEvents) { listener?.invoke(event) }
    }

    private class RecordingTransport : tv.nomercy.player.core.plugin.TransportCommands {
        val calls: MutableList<String> = mutableListOf()
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
    }

    private class RecordingVolume : tv.nomercy.player.core.plugin.VolumeCommands {
        val calls: MutableList<String> = mutableListOf()
        override fun volume(level: Int) { calls += "volume:$level" }
        override fun mute() { calls += "mute" }
        override fun unmute() { calls += "unmute" }
    }

    // Registered the way the player registers one: a plugin reaching for its
    // logger before initialize() throws, which is the whole point of that guard.
    private fun TestScope.plugin(
        session: CastSession?,
        transport: RecordingTransport = RecordingTransport(),
        volume: RecordingVolume = RecordingVolume(),
        opts: CastSenderOptions = CastSenderOptions(),
    ): CastSenderPlugin = CastSenderPlugin(transport, volume, session, opts).also { built ->
        // backgroundScope, because a LifecycleRegistry holds a supervisor job
        // that outlives the body — on the test's own scope runTest waits a
        // minute for it and then fails every case with a timeout.
        built.initialize(FakePluginHost(), opts, LifecycleRegistry(backgroundScope))
    }

    private val item = CastMediaInfo(contentId = "ep-1", contentType = "")

    // While nothing is connected the commands are the local player's. A plugin
    // that forwarded to a receiver that is not there is a pause button that
    // pauses nothing.
    @Test
    fun commandsGoToTheLocalPlayerWhileNothingIsConnected() = runTest {
        val transport = RecordingTransport()
        val volume = RecordingVolume()
        val sender = plugin(FakeSession(), transport, volume)

        sender.play()
        sender.seekTo(positionSeconds = 30.0)
        sender.setVolume(40)

        assertEquals(listOf("play", "seek:30000"), transport.calls)
        assertEquals(listOf("volume:40"), volume.calls)
    }

    @Test
    fun commandsGoToTheReceiverOnceItIsConnected() = runTest {
        val session = FakeSession()
        val transport = RecordingTransport()
        val sender = plugin(session, transport)
        sender.use()

        session.report(CastSenderEvents.Connected("Living room"))
        sender.play()
        sender.setVolume(50)

        assertTrue(sender.isConnected())
        assertEquals(listOf("play", "volume:0.5"), session.commands)
        assertTrue(transport.calls.isEmpty(), "the local player was told as well: ${transport.calls}")
    }

    /**
     * The whole reason the receiver's position is kept.
     *
     * The local player has been paused since the session started, so its own
     * clock reads whatever the film was at when casting began — usually an hour
     * wrong. Resuming has to seek to where the RECEIVER was.
     */
    @Test
    fun disconnectingResumesLocallyAtTheReceiversPosition() = runTest {
        val session = FakeSession()
        val transport = RecordingTransport()
        val sender = plugin(session, transport, opts = CastSenderOptions(resumeLocalOnDisconnect = true))
        sender.use()

        session.report(CastSenderEvents.Connected("Living room"))
        session.report(CastSenderEvents.RemoteState(1284.0, CastPlaybackState.PLAYING))
        session.report(CastSenderEvents.Disconnected)

        assertFalse(sender.isConnected())
        assertEquals(listOf("seek:1284000", "play"), transport.calls)
    }

    @Test
    fun disconnectingLeavesPlaybackAloneWhenResumingWasNotAskedFor() = runTest {
        val session = FakeSession()
        val transport = RecordingTransport()
        val sender = plugin(session, transport)
        sender.use()

        session.report(CastSenderEvents.Connected("Living room"))
        session.report(CastSenderEvents.RemoteState(600.0, CastPlaybackState.PLAYING))
        session.report(CastSenderEvents.Disconnected)

        assertTrue(transport.calls.isEmpty(), "playback was resumed unasked: ${transport.calls}")
    }

    /**
     * A receiver told to load what it is already playing starts it again from
     * the beginning, which a viewer sees as the film jumping to the top.
     */
    @Test
    fun castingTheSameItemTwiceDoesNotReloadTheReceiver() = runTest {
        val session = FakeSession()
        val sender = plugin(session)
        sender.use()

        sender.cast(item, positionSeconds = 12.0)
        sender.cast(item, positionSeconds = 400.0)

        assertEquals(1, session.loads)
        assertEquals(12.0, session.loadedAt)
    }

    // An item that names no content type would reach the receiver as a stream
    // it does not recognise, and the failure arrives as a blank television.
    @Test
    fun anItemWithNoContentTypeGetsTheConfiguredDefault() = runTest {
        val session = FakeSession()
        val sender = plugin(session, opts = CastSenderOptions(defaultContentType = "video/webm"))
        sender.use()

        sender.cast(item, positionSeconds = 0.0)

        assertEquals("video/webm", session.loaded?.contentType)
        assertEquals(CastStreamType.BUFFERED, session.loaded?.streamType)
    }

    @Test
    fun aLiveItemIsSentAsALiveStream() = runTest {
        val session = FakeSession()
        val sender = plugin(session, opts = CastSenderOptions(live = true))
        sender.use()

        sender.cast(item, positionSeconds = 0.0)

        assertEquals(CastStreamType.LIVE, session.loaded?.streamType)
    }

    // Playback is never blocked by a casting scheme that could not run.
    @Test
    fun withNoSessionTheLocalPlayerStillTakesEveryCommand() = runTest {
        val transport = RecordingTransport()
        val sender = plugin(session = null, transport = transport)

        sender.play()
        sender.pause()

        assertFalse(sender.isConnected())
        assertEquals(listOf("play", "pause"), transport.calls)
    }
}
