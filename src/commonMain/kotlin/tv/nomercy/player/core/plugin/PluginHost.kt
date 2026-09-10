// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.events.BeforeDispatchResult
import tv.nomercy.player.core.events.BeforeEvent
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.ports.AudioTrack
import tv.nomercy.player.core.ports.CueParser
import tv.nomercy.player.core.ports.FetchOptions
import tv.nomercy.player.core.ports.FetchResponse
import tv.nomercy.player.core.ports.Logger
import tv.nomercy.player.core.ports.RealtimeChannel
import tv.nomercy.player.core.ports.RealtimeFactoryOptions
import tv.nomercy.player.core.ports.Storage
import tv.nomercy.player.core.ports.SubtitleTrack

// The slice of the player a plugin can reach, and nothing else.
//
// A concrete player implements this. The plugin runtime depends on this and not
// on the player, which is why the whole runtime is testable against an
// in-memory fake with no backend, no controllers and no ports.
//
// A plugin never holds one of these. It gets the Plugin base's helpers, which
// scope every call to the plugin's own id and clean up after it. This interface
// is the contract between the runtime and whoever hosts it.
//
// Ten members crosses detekt's stock ComplexInterface threshold, and the fix
// is not to split this. Every member here is a distinct capability a plugin
// needs and the runtime must be able to fake in full (see FakePluginHost) —
// splitting into "PluginHost" plus "PluginCueHost" plus whatever comes next
// would mean a plugin author importing two or three interfaces to get the one
// thing this already gives them in one place, and a concrete player
// implementing two or three where one contract was the point. The width is the
// design, documented above; ComplexInterface flags width, not a mistake.
@Suppress("ComplexInterface")
public interface PluginHost {
    // Root, unscoped. The Plugin base narrows both to the plugin before a
    // plugin author ever sees them.
    public val rootLogger: Logger
    public val rootStorage: Storage

    public fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription
    public fun <T> emit(key: EventKey<T>, data: T)
    public suspend fun <T> dispatchBefore(key: EventKey<BeforeEvent<T>>, data: T): BeforeDispatchResult<T>

    // The token, the refresh-and-retry and the base URL live behind this, so a
    // plugin cannot get authentication wrong by not knowing about it.
    public suspend fun fetch(url: String, opts: FetchOptions): FetchResponse
    public fun websocket(url: String, opts: RealtimeFactoryOptions): RealtimeChannel

    // Takes an already-namespaced key: the Plugin base builds plugin.<id>.<key>.
    public fun t(namespacedKey: String, vars: Map<String, String> = emptyMap()): String

    public fun report(error: PlayerError)

    // The parser for a subtitle, lyric or sprite url — the host's own registry,
    // not a second one the plugin keeps to itself.
    //
    // A default rather than an addition to every existing implementer's
    // required surface: FakePluginHost and any other minimal test double keeps
    // compiling. LyricsPlugin used to default to a private CueParserRegistry
    // seeded with the built-ins, which read the two formats everybody ships but
    // could never see a format the HOST had registered — a consumer's own
    // parser was invisible to a plugin that never asked the host for one. The
    // web plugin asks `this.player.resolveCueParser(url)`; this is that seam.
    // Null here, not the built-ins: only a real host (ComposedPlayer) knows
    // what is actually registered, and a default that guessed would disagree
    // with it the moment a consumer registered their own.
    public fun resolveCueParser(url: String, contentType: String? = null): CueParser<*>? = null

    // What is playing right now, for a plugin that arrived after it started.
    //
    // A plugin only ever hears the item event, and a consumer that queues and
    // plays before installing a plugin never fires one within its hearing: the
    // item is already current. The web plugin reads `this.player.item()` for
    // exactly this and calls it seeding.
    //
    // A default rather than an addition to every implementer's required
    // surface, like resolveCueParser above: a minimal test double keeps
    // compiling, and null means "this host has no queue" rather than "nothing
    // is playing" — the two are indistinguishable to a plugin and it should
    // treat both the same way.
    public fun item(): PlaylistItem? = null

    // The currently-selected audio/subtitle track. Same default-null pattern
    // as item() — only a real host (ComposedPlayer) knows the actual selection.
    public fun audioTrack(): AudioTrack? = null
    public fun subtitle(): SubtitleTrack? = null
}
