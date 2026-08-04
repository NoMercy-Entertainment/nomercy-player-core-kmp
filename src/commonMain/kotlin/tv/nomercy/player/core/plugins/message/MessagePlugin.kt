// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.message

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.core.plugin.PluginOptionField

public data class MessageOptions(
    /** How long a transient message stays when the caller does not say. */
    val durationMs: Long = DEFAULT_MESSAGE_MS,
)

/** One thing to say, and how long to say it for. */
public data class Message(
    val text: String,
    val durationMs: Long? = null,
)

/**
 * What the player wants to tell somebody.
 *
 * The same `message` id the web plugin has, so a consumer moving code across
 * writes the same line:
 *
 *     player.addPlugin(MessagePlugin())
 *     player.getPlugin(MessagePlugin)?.show("Skipping intro")
 *
 * Two kinds, and they do not interfere. A **transient** toast replaces whatever
 * transient toast was showing and hides itself. A **persistent** message is
 * named, stays until it is removed by that name, and several can be up at once
 * — "recording", "offline", "casting to Living Room" are all persistent, and a
 * toast appearing must not wipe them.
 *
 * Publishes state and draws nothing. The web plugin owns a DOM node because a
 * browser gives it one; here the Compose and SwiftUI chromes each render
 * [toast] and [persistent] their own way, and both get the same answers.
 */
public open class MessagePlugin(
    opts: MessageOptions = MessageOptions(),
) : Plugin<MessageOptions>() {

    // Held rather than fixed, because an option a host can edit has to be an
    // option the plugin reads again afterwards.
    private var opts: MessageOptions = opts

    public companion object Manifest : PluginManifest {
        override val id: String = "message"

        // Two, matching the web plugin this mirrors.
        override val version: String = "2.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    override val options: MessageOptions get() = opts

    override fun optionFields(): List<PluginOptionField> = listOf(
        PluginOptionField.Number(
            key = "durationMs",
            label = "Transient message duration (ms)",
            value = opts.durationMs.toDouble(),
            min = MIN_MESSAGE_MS,
            max = MAX_MESSAGE_MS,
            step = MESSAGE_STEP_MS,
            apply = { chosen -> opts = opts.copy(durationMs = chosen.toLong()) },
        ),
    )

    private val transient: MutableStateFlow<String?> = MutableStateFlow(null)
    private val named: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

    private var hideJob: Job? = null
    private var queueJob: Job? = null

    /** The transient toast, or null when none is showing. */
    public val toast: StateFlow<String?> = transient.asStateFlow()

    /** The named messages, by id, in no particular order. */
    public val persistent: StateFlow<Map<String, String>> = named.asStateFlow()

    /**
     * Say something briefly.
     *
     * Replaces whatever transient message was showing rather than queueing
     * behind it, which is the web's behaviour and the right one: two
     * notifications about the same action are one notification and one piece of
     * stale text.
     */
    public fun show(text: String, ms: Long = opts.durationMs) {
        // Cancels the running queue too. A caller saying something directly has
        // just overtaken whatever sequence was playing, and letting the queue
        // continue would replace their message a second later.
        cancelQueue()
        showOne(text, ms)
    }

    /** Hide the transient message now, and cancel its timer. */
    public fun hide() {
        hideJob?.cancel()
        hideJob = null
        transient.value = null
    }

    /**
     * Say several things in turn, each for its own duration.
     *
     * Calling it again cancels the run in progress. Two sequences interleaving
     * would produce an order neither caller asked for.
     */
    public fun queue(messages: List<Message>) {
        cancelQueue()
        if (messages.isEmpty()) return

        step(messages, 0)
    }

    // One message, then a timer that starts the next.
    //
    // Chained timeouts rather than a coroutine with delays in it, because
    // `timeout` is owned by the plugin's lifecycle and a raw launch is not:
    // disposing a plugin mid-sequence would otherwise leave the rest of the
    // queue arriving on a screen that has moved on. The project's own detekt
    // rule says so, and it caught this.
    //
    // The text is set BEFORE the timer, so queue() puts something on screen
    // exactly as show() does. A first message that waited for a dispatcher
    // would be a blank moment nobody asked for.
    private fun step(messages: List<Message>, index: Int) {
        if (index !in messages.indices) {
            transient.value = null
            queueJob = null
            return
        }

        val message: Message = messages[index]
        transient.value = message.text
        queueJob = timeout(message.durationMs ?: opts.durationMs) { step(messages, index + 1) }
    }

    /**
     * Put a named message up until it is taken down.
     *
     * Same [id] twice replaces the text rather than stacking a second copy, so
     * a status that updates — a progress percentage, a device name — does not
     * leave a trail behind it.
     */
    public fun showPersistent(id: String, text: String) {
        named.value = named.value + (id to text)
    }

    public fun removePersistent(id: String) {
        named.value = named.value - id
    }

    /** Everything down: the toast, the queue and every named message. */
    public fun clear() {
        cancelQueue()
        hide()
        named.value = emptyMap()
    }

    override fun dispose() {
        clear()
    }

    private fun showOne(text: String, ms: Long) {
        hideJob?.cancel()
        transient.value = text

        // Zero or less means it stays until something replaces it. The web's
        // timer never fires for a non-positive delay either, and treating it as
        // "hide now" would make the message flash and vanish.
        if (ms <= 0) return

        hideJob = timeout(ms) { transient.value = null }
    }

    private fun cancelQueue() {
        queueJob?.cancel()
        queueJob = null
    }
}

/** The web's three seconds. */
public const val DEFAULT_MESSAGE_MS: Long = 3_000

// The range an editor offers. Half a second is the shortest a person reads a
// toast at all, and thirty is long enough that anything beyond it wants a
// persistent message instead.
private const val MIN_MESSAGE_MS: Double = 500.0
private const val MAX_MESSAGE_MS: Double = 30_000.0
private const val MESSAGE_STEP_MS: Double = 250.0
