// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

import tv.nomercy.player.core.ports.HdrOnSdrFallback

// What to do about the screen blanking during playback.
public enum class WakeLockPolicy(override val token: String) : TokenEnum {
    AUTO("auto"),
    ALWAYS("always"),
    NEVER("never");

    public companion object {
        public fun fromToken(token: String): WakeLockPolicy = entries.byToken(token, "WakeLockPolicy")
    }
}

// What to do when the network drops mid-playback. The default keeps playing
// what is already buffered: pausing on a two-second dropout is worse than the
// dropout.
public enum class OfflinePolicy(override val token: String) : TokenEnum {
    PAUSE("pause"),
    CONTINUE_BUFFERED("continue-buffered"),
    IGNORE("ignore");

    public companion object {
        public fun fromToken(token: String): OfflinePolicy = entries.byToken(token, "OfflinePolicy")
    }
}

// The tunable half of the player's configuration — values a host sets and the
// player reads. Mirrors the tunable subset of the web BasePlayerConfig, default
// for default.
//
// The swappable ports (storage, platform, media backend, translator, realtime
// channel, strategies) are added to this same class by the adapter-ports plan.
// Keeping them out until then means every field here is a plain value, so a
// config can be built, compared and serialised without constructing anything.
public data class PlayerConfig(
    val baseUrl: String? = null,
    val baseImageUrl: String? = null,
    val language: String? = null,
    val defaultVolume: Int = 100,
    val metricsIntervalMs: Long = 10_000,
    val progressIntervalMs: Long = 5_000,
    val inactivityMs: Long = 4_000,
    val beforeEventTimeoutMs: Long = 10_000,
    val pluginInitTimeoutMs: Long = 30_000,
    val itemEndingSoonThreshold: Double = 10.0,
    val preloadLeadSeconds: Double = 10.0,
    val crossfadeLeadSeconds: Double = 3.0,
    val crossfadeTailSeconds: Double = 3.0,
    val crossfadeEnabled: Boolean = false,
    val pauseWhenHidden: Boolean = false,
    val controls: Boolean = false,
    val wakeLock: WakeLockPolicy = WakeLockPolicy.AUTO,
    val onOffline: OfflinePolicy = OfflinePolicy.CONTINUE_BUFFERED,

    // What to do when HDR content meets an SDR screen and the engine cannot
    // convert it. Play by default: a washed-out picture is a picture, and a
    // library that refused by default would turn a colour problem into a black
    // screen for consumers who never thought about dynamic range at all.
    val hdrOnSdr: HdrOnSdrFallback = HdrOnSdrFallback.Play,
)
