// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy

// Key-value persistence, whatever the platform calls it.
//
// The three raw operations are suspend so one contract covers both a
// synchronous SharedPreferences and an async DataStore or remote store. A
// backend implements only those three; the JSON pair below is written once
// here rather than in every implementation.
//
// A plugin's storage is key-prefixed with its id before it reaches an
// implementation, so two plugins that both store "enabled" do not collide.
public interface Storage {
    public suspend fun get(key: String): String?
    public suspend fun set(key: String, value: String)
    public suspend fun remove(key: String)

    // Takes an explicit serializer because Kotlin has no structural
    // JSON.parse<T>: the type has to come from somewhere, and a reified cast
    // would just move the failure to first use. This is the sanctioned
    // divergence from the web signature.
    //
    // Unreadable stored JSON reads as absent rather than throwing. Storage is
    // the one place old data from a previous version turns up, and a player
    // that refuses to start because a preference blob changed shape is worse
    // than one that falls back to its default.
    public suspend fun <T> getJSON(key: String, deserializer: DeserializationStrategy<T>): T? {
        val raw: String = get(key) ?: return null
        return try {
            PlayerJson.instance.decodeFromString(deserializer, raw)
        } catch (invalid: SerializationException) {
            onStorageDecodeError?.invoke(key, invalid)
            null
        }
    }

    public suspend fun <T> setJSON(key: String, value: T, serializer: SerializationStrategy<T>) {
        set(key, PlayerJson.instance.encodeToString(serializer, value))
    }

    public companion object {
        // Set by a host that wants to know when stored data was unreadable.
        // Default is silence, because falling back to a default is the correct
        // behaviour and most hosts do not care why.
        public var onStorageDecodeError: ((key: String, error: SerializationException) -> Unit)? = null
    }
}

// A store namespaced to this player, so a host's own preferences and the
// player's never see each other's keys.
public expect fun defaultStorage(namespace: String): Storage
