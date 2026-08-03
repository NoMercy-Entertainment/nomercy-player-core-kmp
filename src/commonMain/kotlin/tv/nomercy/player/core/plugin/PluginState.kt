// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

// What a plugin is, right now, as one value.
//
// For a debug overlay and for save-and-restore tooling, which are the two
// callers the reference names. PluginRegistry could already hand back the
// instances and nothing could ask one what it was doing, so a testbed plugin
// tree could draw a plugin and its toggle and nothing else.
//
// [runtime] is plugin-defined and untyped for the same reason PlayerError's
// context is: it carries whatever the plugin knows about itself — a fetched
// sprite sheet's size, a socket's state, how many cues are loaded — and typing
// it would mean a state class per plugin.
public data class PluginState<O : Any>(
    val id: String,
    val version: String,
    val enabled: Boolean,
    // Null when neither the author nor the consumer supplied any, which is the
    // ordinary case for a plugin that takes no options.
    val opts: O?,
    val runtime: Map<String, Any?> = emptyMap(),
)
