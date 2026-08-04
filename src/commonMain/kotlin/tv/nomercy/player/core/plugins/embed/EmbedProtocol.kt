// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.embed

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tv.nomercy.player.core.events.PlayerErrorEvent

// The envelope the host page already knows.
//
// Two type tags, one in each direction, exactly as the web plugin sends them.
// A host that works against the web player works against this one unchanged,
// which is the whole reason to keep a wire format rather than design one.
public const val EMBED_COMMAND_TYPE: String = "nm:command"

public const val EMBED_EVENT_TYPE: String = "nm:event"

// What the host may ask for.
//
// An enum rather than free strings, so an unknown action is one branch in one
// place — the plugin logs it — instead of a silent no-op at the bottom of a
// when.
public enum class EmbedAction(public val wire: String) {
    PLAY("play"),
    PAUSE("pause"),
    STOP("stop"),
    SEEK("seek"),
    VOLUME("volume"),
    MUTE("mute"),
    UNMUTE("unmute"),
    NEXT("next"),
    PREVIOUS("previous"),
    ;

    public companion object {
        public fun of(wire: String?): EmbedAction? = entries.firstOrNull { it.wire == wire }
    }
}

// What the player tells the host about.
//
// The web's seven defaults plus `error`, which it forwards only when a consumer
// asks for it — an embed that reports nothing when playback fails leaves the
// host page showing a spinner forever.
public enum class EmbedEventName(public val wire: String) {
    READY("ready"),
    PLAY("play"),
    PAUSE("pause"),
    ENDED("ended"),
    TIME("time"),
    VOLUME("volume"),
    MUTE("mute"),
    ERROR("error"),
}

// One command off the wire, already told apart from noise.
public data class EmbedCommand(
    val action: EmbedAction,
    val body: JsonObject,
) {
    public fun number(field: String): Double? = body[field]?.let(::asDouble)
}

internal fun embedEvent(name: EmbedEventName, data: JsonObject): JsonObject = buildJsonObject {
    put("type", EMBED_EVENT_TYPE)
    put("name", name.wire)
    put("data", data)
}

private fun asDouble(element: JsonElement): Double? = (element as? JsonPrimitive)?.content?.toDoubleOrNull()

internal fun embedSource(value: String?): JsonObject = buildJsonObject {
    value?.let { put("source", it) }
}

// A player error flattened to what survives a wire.
//
// The live error carries a cause chain and a context map of arbitrary values,
// neither of which a host page can read. The web plugin has the same function
// for the same reason — its structured clone throws on exactly those fields —
// so the host sees the same six keys from either player.
internal fun embedError(event: PlayerErrorEvent): JsonObject = buildJsonObject {
    put("code", event.code)
    put("message", event.message)
    put("severity", event.severity.name.lowercase())
    put(
        "scope",
        buildJsonObject {
            put("kind", event.scope.kind.name.lowercase())
            event.scope.id?.let { put("id", it) }
        },
    )
    event.suggestion?.let { put("suggestion", it) }
}

/** The web's seven, which is what a host gets when it asks for nothing. */
public val DEFAULT_EMBED_EVENTS: List<EmbedEventName> = listOf(
    EmbedEventName.READY,
    EmbedEventName.PLAY,
    EmbedEventName.PAUSE,
    EmbedEventName.ENDED,
    EmbedEventName.TIME,
    EmbedEventName.VOLUME,
    EmbedEventName.MUTE,
)
