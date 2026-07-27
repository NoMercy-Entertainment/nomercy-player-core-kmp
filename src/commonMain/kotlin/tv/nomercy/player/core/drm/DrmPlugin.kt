// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest

// Playing content that is protected.
//
// It carries the configuration and it degrades. An engine that cannot run the
// scheme says so through an event rather than a throw, because a device that
// cannot decrypt one version of a film can very often play another, and a chrome
// that was handed an exception has nothing left to offer. A viewer should see
// "this version will not play here", not a crash.
//
// The licence exchanges themselves are not here yet and the plan says why: the
// server has key storage and encoder building blocks but no client key route and
// no licence endpoint. Building a request against a URL nobody serves would look
// finished and work never.
public open class DrmPlugin(
    private val config: DrmConfig,
    private val capability: DrmCapability = ClearOnly,
) : Plugin<DrmConfig>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "drm"

        // Two, matching the web plugin this mirrors.
        override val version: String = "2.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    override val options: DrmConfig get() = config

    // Answered from the engine at install time rather than at the first key,
    // so a chrome can choose a different version before anything has played
    // rather than after a viewer has watched a spinner.
    public open val isPlayable: Boolean get() = capability.supports(config.scheme)

    override fun use() {
        if (!isPlayable) {
            report(
                DrmErrorCodes.UNSUPPORTED_SCHEME,
                "this device cannot play ${config.scheme} content",
            )
            emit(DrmEvents.Unsupported, DrmUnsupported(config.scheme, DrmErrorCodes.UNSUPPORTED_SCHEME))
        }
    }
}
