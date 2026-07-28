// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.conformance

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import tv.nomercy.player.conformance.Scenario
import tv.nomercy.player.conformance.ScenarioAction
import tv.nomercy.player.conformance.ScenarioResult
import tv.nomercy.player.conformance.firstUnmatched
import tv.nomercy.player.conformance.scenarioItems
import tv.nomercy.player.core.controllers.ComposedPlayer
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.ShuffleState
import java.io.File

// The same scenarios the web harness runs, against the native player.
//
// One file, two runners, one answer. That is the whole point of the exercise:
// a scenario that passes on web and fails here is a real divergence, not two
// suites disagreeing about what to measure.
//
// Everything here mirrors the rules in the web harness's NATIVE-HANDOFF, and
// the reasons are the same. The one that matters most: capture subscribes to
// every before-event by name as well as to the firehose, because the
// before-dispatch invokes listeners directly and never goes through emit(), so
// the whole cancellable seam is invisible to a firehose observer.

// Every before-event name the registry knows. Read from CoreEvents rather than
// hard-coded so a before-event added to the contract is captured without anyone
// remembering to add it here.
fun beforeEventNames(): List<String> =
    CoreEvents.all.map { it.name }.filter { it.startsWith("before") }.sorted()

class NativeCapture(player: ComposedPlayer) {
    private val order: MutableList<String> = mutableListOf()
    private val payloads: MutableList<Any?> = mutableListOf()

    init {
        player.context.emitter.onAll { name, payload -> record(name, payload) }
        // The firehose is fed by emit() alone, and the before-dispatch does not
        // go through it. Without these the entire cancellable seam is invisible.
        for (name in beforeEventNames()) {
            player.context.emitter.on(EventKey<Any?>(name)) { order.add(name) }
        }
    }

    // The payload is not compared — scenarios assert on order, not contents —
    // but taking it keeps the door open for a scenario that needs to.
    private fun record(name: String, payload: Any?) {
        order.add(name)
        payloads.add(payload)
    }

    fun seen(): List<String> = order.toList()
}

// Does `expected` appear inside `observed`, in order?
//
// A subsequence, like the web runner. A scenario says what must happen and in
// what order; it does not say nothing else may happen, because what else
// happens legitimately differs between engines and mediums.
private fun playlistOf(scenario: Scenario): List<PlaylistItem> =
    scenarioItems(scenario).map { TestItem(id = it.id, url = it.url, title = it.title) }

private fun JsonElement.asDouble(): Double = (this as JsonPrimitive).content.toDouble()

private fun JsonElement.asInt(): Int = (this as JsonPrimitive).content.toDouble().toInt()

private fun JsonElement.asBoolean(): Boolean = (this as JsonPrimitive).content.toBooleanStrict()

@Suppress("CyclomaticComplexMethod")
private suspend fun applyMethod(player: ComposedPlayer, action: ScenarioAction) {
    val args = action.args
    when (action.method) {
        "play" -> player.play()
        "pause" -> player.pause()
        "stop" -> player.stop()
        "next" -> player.next()
        "previous" -> player.previous()
        "time" -> player.time(args[0].asDouble())
        "volume" -> player.volume(args[0].asInt())
        "mute" -> if (args.isEmpty() || args[0].asBoolean()) player.mute() else player.unmute()
        "playbackRate" -> player.playbackRate(args[0].asDouble())
        "shuffle" -> player.shuffleState(ShuffleState.ON)
        else -> throw IllegalArgumentException("scenario calls ${action.method}(), which this player does not have")
    }
}

// Drives the fake engine, standing in for the web harness's ScenarioBackend
// script() seam.
private fun applyBackend(backend: FakeMediaBackend, action: ScenarioAction) {
    backend.fire(action.backend ?: return)
}

// A prevention is its own step because the listener has to be wired before the
// action it cancels.
private suspend fun applyAction(
    player: ComposedPlayer,
    backend: FakeMediaBackend,
    scenario: Scenario,
    action: ScenarioAction,
) {
    when {
        action.method == "queue" -> player.queue(playlistOf(scenario))
        action.preventVia != null -> player.context.emitter.on(EventKey<Any?>(action.preventVia)) { event ->
            (event as? BeforeEvent<*>)?.preventDefault()
        }
        action.backend != null -> applyBackend(backend, action)
        else -> applyMethod(player, action)
    }
}

suspend fun runScenario(scenario: Scenario): ScenarioResult {
    val backend = FakeMediaBackend()
    val player = ComposedPlayer(backend)
    player.setup()
    player.ready().await()
    if (scenario.playlist.isNotEmpty()) player.queue(playlistOf(scenario))

    val capture = NativeCapture(player)

    try {
        for (action in scenario.actions) applyAction(player, backend, scenario, action)
    } catch (failure: IllegalArgumentException) {
        return ScenarioResult(scenario.id, ok = false, scenario.expect, emptyList(), failure.message)
    }

    val observed = capture.seen()
    val failedAt = firstUnmatched(scenario.expect, observed)
    return ScenarioResult(
        id = scenario.id,
        ok = failedAt == -1,
        expected = scenario.expect,
        observed = observed,
        reason = if (failedAt == -1) {
            null
        } else {
            "expected \"${scenario.expect[failedAt]}\" after " +
                (scenario.expect.take(failedAt).joinToString(" -> ").ifEmpty { "the start" }) +
                ", and it never arrived in that order"
        },
    )
}
