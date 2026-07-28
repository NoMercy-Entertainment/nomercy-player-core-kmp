// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.template

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest

// What a plugin does, in the smallest thing that still does all of it.
//
// Copy this directory, rename the package and the id, and replace the body.
// Everything around the body is the part worth keeping: the manifest the
// registry validates against, an event registry of your own, options a consumer
// can override, and a body that only calls the base class.
//
// The four things that make it a plugin rather than a listener:
//
//   1. A manifest as the companion. The registry reads it before any of your
//      code runs, so a missing dependency fails before a half-initialised
//      plugin exists.
//   2. Your own EventKey registry. A consumer subscribes to HelloEvents.Greeted
//      rather than to a string, and the compiler catches a rename.
//   3. this.on and this.emit, never player.on. The listener unsubscribes when
//      your plugin goes away and the emit lands under plugin:hello:, where a
//      plugin listening for your event will actually find it.
//   4. t(), so your strings are the viewer's language and namespaced under
//      plugin.hello. where they cannot shadow a core one.
public class HelloPlugin : Plugin<HelloOptions>() {

    // The companion IS the manifest, so the id lives in one place and the class
    // cannot disagree with it.
    public companion object Manifest : PluginManifest {
        override val id: String = "hello"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    // The author's defaults. A consumer's options at registration win, and
    // resolvedOptions is where that has already been decided.
    override val options: HelloOptions get() = HelloOptions()

    override fun use() {
        // this.on, not player.on. Two guarantees come with it: this listener is
        // removed when the plugin goes away, and it stops firing while the
        // plugin is disabled without being unregistered.
        on(CoreEvents.Play) {
            val greeting: String = resolvedOptions?.greeting ?: t("greeting")
            // this.emit, not player.emit. The key goes out as
            // plugin:hello:greeted rather than on the global channel.
            emit(HelloEvents.Greeted, Greeting(greeting))
        }
    }
}

// Your plugin's events, as keys rather than strings.
//
// Namespaced at declaration so a consumer can subscribe with
// player.on(HelloEvents.Greeted) and get the payload type with it. A string
// would work and would survive a rename silently.
public object HelloEvents {
    public val Greeted: EventKey<Greeting> = EventKey("plugin:hello:greeted")
}

// A payload rather than a bare string, because an event's shape is the thing
// consumers write against and widening a String later is a breaking change.
public data class Greeting(val text: String)

// What a consumer can change without editing your source.
//
// A data class with defaults: the author's are the ones on the class, and the
// registration's are the ones the consumer passed. Both reach the plugin
// through resolvedOptions.
public data class HelloOptions(
    val greeting: String? = null,
)
