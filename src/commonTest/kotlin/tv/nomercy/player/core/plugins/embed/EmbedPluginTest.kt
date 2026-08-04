// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.embed

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.events.TimeUpdate
import tv.nomercy.player.core.events.VolumeChange
import tv.nomercy.player.core.plugin.TransportCommands
import tv.nomercy.player.core.plugin.VolumeCommands
import tv.nomercy.player.core.ports.EmbedMessage
import tv.nomercy.player.core.ports.EmbedTransport
import tv.nomercy.player.testing.FakePlayer
import tv.nomercy.player.testing.testPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbedPluginTest {

    private val host = "https://nomercy.tv"

    @Test
    fun aCommandFromAnAllowedHostReachesThePlayer() = runTest {
        val pipe = FakeTransport()
        val commands = RecordingCommands()
        val plugin = embedPlugin(pipe, commands, listOf(host))

        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->
            pipe.deliver(host, """{"type":"nm:command","action":"play"}""")
            pipe.deliver(host, """{"type":"nm:command","action":"seek","time":42}""")
            pipe.deliver(host, """{"type":"nm:command","action":"volume","level":30}""")

            assertEquals(listOf("play", "seekTo:42000", "volume:30"), commands.calls)
        }
    }

    // An embed that accepts commands from anywhere hands its playback to any
    // page that can reach it. Empty refuses everybody, which is the default.
    @Test
    fun aCommandFromAnUnlistedOriginIsIgnored() = runTest {
        val pipe = FakeTransport()
        val commands = RecordingCommands()
        val plugin = embedPlugin(pipe, commands, listOf(host))

        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->
            pipe.deliver("https://not-us.example", """{"type":"nm:command","action":"play"}""")

            assertEquals(emptyList(), commands.calls)
        }
    }

    @Test
    fun theDefaultAllowlistRefusesEverything() = runTest {
        val pipe = FakeTransport()
        val commands = RecordingCommands()
        val plugin = embedPlugin(pipe, commands, origins = emptyList())

        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->
            pipe.deliver(host, """{"type":"nm:command","action":"play"}""")

            assertEquals(emptyList(), commands.calls)
        }
    }

    @Test
    fun playerEventsGoOutInTheEnvelopeTheHostAlreadyKnows() = runTest {
        val pipe = FakeTransport()
        val commands = RecordingCommands()
        val plugin = embedPlugin(pipe, commands, listOf(host))

        testPlugin(plugin, FakePlayer(scope = this)) { player, _ ->
            player.emit(CoreEvents.Time, TimeUpdate(time = 12.5, duration = 240.0, percentage = 5.2))
            player.emit(CoreEvents.Volume, VolumeChange(level = 80))

            assertEquals(
                listOf(
                    """{"type":"nm:event","name":"time","data":{"time":12.5}}""",
                    """{"type":"nm:event","name":"volume","data":{"level":80}}""",
                ),
                pipe.sent,
            )
        }
    }

    // A host is not part of this process and its messages are not the player's
    // to trust. A throw here lands on the transport's thread and takes the pipe
    // down over one bad message.
    @Test
    fun anUnreadableMessageIsDroppedRatherThanThrown() = runTest {
        val pipe = FakeTransport()
        val commands = RecordingCommands()
        val plugin = embedPlugin(pipe, commands, listOf(host))

        testPlugin(plugin, FakePlayer(scope = this)) { _, _ ->
            pipe.deliver(host, "not json at all")
            pipe.deliver(host, """{"type":"nm:command","action":"fly"}""")
            pipe.deliver(host, """{"type":"nm:command","action":"pause"}""")

            assertEquals(listOf("pause"), commands.calls)
        }
    }

    // With nothing on the other end the plugin is inert, which is the web
    // plugin's behaviour outside a browsing context. Playback is untouched.
    @Test
    fun noTransportMeansTheBridgeIsSimplyNotThere() = runTest {
        val commands = RecordingCommands()
        val plugin = EmbedPlugin(commands, commands)

        testPlugin(plugin, FakePlayer(scope = this)) { player, _ ->
            player.emit(CoreEvents.Time, TimeUpdate(time = 1.0, duration = 2.0, percentage = 50.0))

            assertTrue(!plugin.embedded())
        }
    }

    private fun embedPlugin(
        pipe: EmbedTransport,
        commands: RecordingCommands,
        origins: List<String>,
    ): EmbedPlugin = EmbedPlugin(commands, commands, pipe, EmbedOptions(allowedOrigins = origins))
}

private class FakeTransport : EmbedTransport {
    val sent: MutableList<String> = mutableListOf()

    private var listener: ((EmbedMessage) -> Unit)? = null

    override fun send(payload: String) {
        sent += payload
    }

    override fun receive(fn: (EmbedMessage) -> Unit): Subscription {
        listener = fn
        return Subscription { listener = null }
    }

    fun deliver(origin: String, payload: String) {
        listener?.invoke(EmbedMessage(origin, payload))
    }
}

private class RecordingCommands : TransportCommands, VolumeCommands {
    val calls: MutableList<String> = mutableListOf()

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo:$positionMs"
    }

    override fun next() {
        calls += "next"
    }

    override fun previous() {
        calls += "previous"
    }

    override fun volume(level: Int) {
        calls += "volume:$level"
    }

    override fun mute() {
        calls += "mute"
    }

    override fun unmute() {
        calls += "unmute"
    }
}
