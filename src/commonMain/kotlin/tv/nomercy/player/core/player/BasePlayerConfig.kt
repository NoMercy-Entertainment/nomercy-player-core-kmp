// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

import tv.nomercy.player.core.plugin.PluginSpec
import tv.nomercy.player.core.ports.AuthConfig
import tv.nomercy.player.core.ports.CueParser
import tv.nomercy.player.core.ports.LogLevel
import tv.nomercy.player.core.ports.Logger
import tv.nomercy.player.core.ports.Storage
import tv.nomercy.player.core.ports.TranslationLoader

/**
 * What a player can be handed at construction.
 *
 * Everything a host supplies rather than everything the player has: a logger, a
 * store, a translator, the ports. Kept as one type because setup takes one
 * object, and because a consumer reads this to learn what it is allowed to
 * replace.
 *
 * [pauseWhenHidden] defaults to false and that is deliberate for a media
 * player: audio continuing while the app is backgrounded is the whole point of
 * a music player, and a video player that stops when the screen locks loses the
 * listener who was only listening.
 */
public data class BasePlayerConfig(
    val logLevel: LogLevel = LogLevel.INFO,
    val logger: Logger? = null,

    /** Prefix for relative media URLs. */
    val baseUrl: String? = null,

    /** 0..1. */
    val defaultVolume: Double? = null,

    val auth: AuthConfig? = null,

    val language: String? = null,
    val translations: Map<String, Map<String, String>> = emptyMap(),
    val loadTranslations: TranslationLoader? = null,
    val onMissingTranslation: ((key: String, language: String) -> String)? = null,

    val storage: Storage? = null,

    /** Extra cue parsers, on top of the built-in ones. */
    val cueParsers: List<CueParser<*>> = emptyList(),

    /** How often playback metrics are sampled. */
    val metricsIntervalMs: Long? = null,

    /** How often progress is reported to whoever is recording it. */
    val progressIntervalMs: Long? = null,

    /** Pause when the app goes to the background. */
    val pauseWhenHidden: Boolean = false,

    /** Whether the built-in chrome is shown at all. */
    val controls: Boolean = true,

    /** How long the chrome stays up with no input. */
    val inactivityMs: Long? = null,

    /** What to do when the network goes away. */
    val onOffline: OfflineBehaviour? = null,

    val plugins: List<PluginSpec> = emptyList(),
)
