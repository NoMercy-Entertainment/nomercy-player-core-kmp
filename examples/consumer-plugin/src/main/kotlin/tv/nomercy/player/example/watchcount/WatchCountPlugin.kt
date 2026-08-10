// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.example.watchcount

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest

// The anti-hollow proof for P31: this file imports nothing but the public
// `tv.nomercy.player.core.*` surface, is authored the same way `templates/plugin`'s
// `HelloPlugin` demonstrates, and is built by a Gradle project that has never
// seen the library's source tree — only its published (or composite-substituted)
// jar. If this compiles and its test passes, the shipped surface is enough to
// write a real plugin on.
public class WatchCountPlugin : Plugin<Unit>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "watch-count"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    private var count: Int = 0

    override fun use() {
        on(CoreEvents.Play) {
            count += 1
            emit(WatchCountEvents.Changed, count)
        }
    }
}

public object WatchCountEvents {
    public val Changed: EventKey<Int> = EventKey("plugin:watch-count:changed")
}
