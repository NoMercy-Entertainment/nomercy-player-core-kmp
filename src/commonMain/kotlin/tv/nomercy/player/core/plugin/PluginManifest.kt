// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.ports.Translations

// Everything the registry needs to know about a plugin before it runs any of
// its code: who it is, what it needs, what it replaces, what it renders.
//
// The web declares this as static fields. Kotlin has no inheritable statics and
// KMP reflection is too thin to go looking, so a plugin puts it on its
// companion — `companion object Manifest : PluginManifest` — and the registry
// reads it off the instance. No reflection anywhere, which is also what makes
// this survive iOS.
public interface PluginManifest {
    public val id: String
    public val version: String

    // The oldest core this plugin will run against. Null means it does not
    // care, which is the honest default for a plugin that only listens.
    public val minCoreVersion: String? get() = null

    public val requires: List<Requirement> get() = emptyList()

    // Ids of plugins this one supersedes. Registering it removes them, which is
    // how a consumer swaps a built-in for their own without forking.
    public val replaces: List<String> get() = emptyList()

    // Higher goes first when two plugins would otherwise tie.
    public val priority: Int get() = 0

    public val translations: Translations? get() = null

    // UI this plugin offers, declared up front so a chrome can lay out its
    // slots without instantiating anything.
    public val contributions: List<ChromeContribution> get() = emptyList()
}

// One entry in a plugin's requires list.
//
// It points at the other plugin's manifest rather than naming its id as a
// string, so renaming a plugin is a compile error here instead of a runtime
// "missing dependency" nobody sees until the feature is used.
public data class Requirement(
    val manifest: PluginManifest,
    // An optional requirement that is absent is not an error: the plugin loads
    // and does less. That is how a visualiser degrades when the audio graph is
    // not installed.
    val optional: Boolean = false,
    val minVersion: String? = null,
)

// Builds the wire name for one of a plugin's own events.
//
// Both sides go through this, so a plugin emitting its event and another plugin
// listening for it cannot disagree about the string — which is the failure a
// hand-written "plugin:lyrics:line" produces the first time someone types it
// slightly differently.
public fun <T> pluginEventKey(manifest: PluginManifest, name: String): EventKey<T> =
    EventKey("plugin:${manifest.id}:$name")
