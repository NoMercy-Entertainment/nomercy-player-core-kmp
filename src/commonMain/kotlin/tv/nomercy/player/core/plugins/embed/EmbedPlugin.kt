// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.embed

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.core.plugin.TransportCommands
import tv.nomercy.player.core.plugin.VolumeCommands
import tv.nomercy.player.core.ports.EmbedMessage
import tv.nomercy.player.core.ports.EmbedTransport

public data class EmbedOptions(
    // Who may send commands. Empty refuses everybody, which is the web plugin's
    // default and the right one: an embed that accepts commands from anywhere
    // hands its playback to any page that can reach it. "*" opts out.
    val allowedOrigins: List<String> = emptyList(),

    val forwardEvents: List<EmbedEventName> = DEFAULT_EMBED_EVENTS,
)

/**
 * The bridge between an embedded player and whatever is hosting it.
 *
 * The same `embed` id the web plugin has, and the same protocol on the wire:
 * `nm:command` in, `nm:event` out. A host page written against the web player's
 * embed works against this one without a line changing, which is the point of
 * keeping the envelope rather than designing a nicer one.
 *
 * What differs is the pipe. The web plugin owns `window.postMessage` because a
 * browser is the only place it runs; here the pipe is an [EmbedTransport] the
 * consumer supplies — a WebView bridge, a receiver's socket, a channel to
 * another process. With no transport the plugin is inert and playback is
 * untouched, which is the web plugin's behaviour outside a browsing context.
 *
 * Inbound commands are checked against [EmbedOptions.allowedOrigins] before
 * anything is dispatched. An empty list refuses everything.
 */
public open class EmbedPlugin(
    private val commands: TransportCommands,
    private val volume: VolumeCommands,
    private val transport: EmbedTransport? = null,
    private val opts: EmbedOptions = EmbedOptions(),
) : Plugin<EmbedOptions>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "embed"

        // Two, matching the web plugin this mirrors.
        override val version: String = "2.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    override val options: EmbedOptions get() = opts

    private val json: Json = Json { ignoreUnknownKeys = true }

    private var origins: List<String> = opts.allowedOrigins

    private var inbound: Subscription? = null

    /** Whether a host is actually on the other end. */
    public fun embedded(): Boolean = transport != null

    override fun use() {
        val pipe: EmbedTransport = transport ?: return

        inbound = pipe.receive { message: EmbedMessage -> onMessage(message) }

        forwardTransport()
        forwardPlayback()
    }

    override fun dispose() {
        inbound?.dispose()
        inbound = null
    }

    /**
     * Who may send commands, right now.
     *
     * Readable and writable at runtime because the host an embed trusts is
     * often not known when the player is built — a receiver learns its sender's
     * origin from the session it just joined.
     */
    public fun allowedOrigins(): List<String> = origins

    public fun allowedOrigins(value: List<String>) {
        origins = value
    }

    /** Put one event on the wire. Does nothing with no transport attached. */
    public fun sendToHost(name: EmbedEventName, data: JsonObject) {
        val pipe: EmbedTransport = transport ?: return
        pipe.send(embedEvent(name, data).toString())
    }

    /**
     * Whether this origin may send commands.
     *
     * Open so a consumer can widen it — a wildcard subdomain, a signed token —
     * without giving up the check entirely by listing "*".
     */
    protected open fun isOriginAllowed(origin: String): Boolean = when {
        origins.isEmpty() -> false
        origins.contains(WILDCARD_ORIGIN) -> true
        else -> origins.contains(origin)
    }

    /**
     * Do what the host asked.
     *
     * Open so a consumer can add commands of its own; call through for the ones
     * the protocol already names.
     */
    @Suppress("CyclomaticComplexMethod")
    protected open fun handleCommand(command: EmbedCommand) {
        when (command.action) {
            EmbedAction.PLAY -> commands.play()
            EmbedAction.PAUSE -> commands.pause()
            EmbedAction.STOP -> commands.stop()
            EmbedAction.SEEK -> command.number("time")?.let { commands.seekTo((it * MS_PER_SECOND).toLong()) }
            EmbedAction.VOLUME -> command.number("level")?.let { volume.volume(it.toInt()) }
            EmbedAction.MUTE -> volume.mute()
            EmbedAction.UNMUTE -> volume.unmute()
            EmbedAction.NEXT -> commands.next()
            EmbedAction.PREVIOUS -> commands.previous()
        }
    }

    private fun onMessage(message: EmbedMessage) {
        if (!isOriginAllowed(message.origin)) return

        val body: JsonObject = parse(message.payload) ?: return
        if (body["type"]?.jsonPrimitive?.contentOrNull != EMBED_COMMAND_TYPE) return

        when (val action: EmbedAction? = EmbedAction.of(body["action"]?.jsonPrimitive?.contentOrNull)) {
            null -> logger.warn("embed: unknown command ${message.payload}")
            else -> handleCommand(EmbedCommand(action, body))
        }
    }

    // A host is not part of this process and its messages are not this
    // player's to trust. Malformed JSON is dropped with a line in the log
    // rather than thrown, because a throw here lands on the transport's
    // thread and takes down a pipe over one bad message.
    private fun parse(payload: String): JsonObject? =
        runCatching { json.parseToJsonElement(payload).jsonObject }
            .onFailure { cause: Throwable ->
                if (cause !is SerializationException && cause !is IllegalArgumentException) throw cause
                logger.warn("embed: unreadable message $payload")
            }
            .getOrNull()

    private fun forwardTransport() {
        forwarded(EmbedEventName.READY) { on(CoreEvents.Ready) { sendToHost(EmbedEventName.READY, EMPTY_BODY) } }
        forwarded(EmbedEventName.ENDED) { on(CoreEvents.Ended) { sendToHost(EmbedEventName.ENDED, EMPTY_BODY) } }
        forwarded(EmbedEventName.PLAY) {
            on(CoreEvents.Play) { event -> sendToHost(EmbedEventName.PLAY, embedSource(event.source)) }
        }
        forwarded(EmbedEventName.PAUSE) {
            on(CoreEvents.Pause) { event -> sendToHost(EmbedEventName.PAUSE, embedSource(event.source)) }
        }
    }

    private fun forwardPlayback() {
        forwarded(EmbedEventName.TIME) {
            on(CoreEvents.Time) { event ->
                sendToHost(EmbedEventName.TIME, buildJsonObject { put("time", event.time) })
            }
        }
        forwarded(EmbedEventName.VOLUME) {
            on(CoreEvents.Volume) { event ->
                sendToHost(EmbedEventName.VOLUME, buildJsonObject { put("level", event.level) })
            }
        }
        forwarded(EmbedEventName.MUTE) {
            on(CoreEvents.Mute) { event ->
                sendToHost(EmbedEventName.MUTE, buildJsonObject { put("muted", event.muted) })
            }
        }
        forwarded(EmbedEventName.ERROR) {
            on(CoreEvents.Error) { event -> sendToHost(EmbedEventName.ERROR, embedError(event)) }
        }
    }

    private fun forwarded(name: EmbedEventName, subscribe: () -> Unit) {
        if (opts.forwardEvents.contains(name)) subscribe()
    }
}

private const val WILDCARD_ORIGIN = "*"

private const val MS_PER_SECOND = 1_000.0

private val EMPTY_BODY: JsonObject = JsonObject(emptyMap())
