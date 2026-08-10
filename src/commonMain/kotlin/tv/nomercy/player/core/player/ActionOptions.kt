// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Who asked for a transport action, and how it should behave. Passed to play,
// pause, stop and friends, and carried through to the before* event so a
// listener can tell a viewer's tap from a remote handoff from an autoplay
// chain — and refuse one without refusing the others.
//
// source is an open String rather than an enum: a plugin may name itself as
// the source, and the web contract already carries arbitrary strings here.
public data class ActionOptions(
    val source: String? = null,
    val silent: Boolean = false,
    val autoplay: Boolean = false,
)

// The sources core itself uses. Not exhaustive by design.
public object ActionSource {
    public const val USER: String = "user"
    public const val REMOTE: String = "remote"
    public const val PLUGIN: String = "plugin"

    // The library acting on its own behalf, for the transport calls no viewer
    // made: the pause before a cast handoff, an engine that stopped itself, and
    // the reference's pause-when-hidden and offline policies. The web spells it
    // exactly this way, so a listener filtering on it works on both.
    public const val PLATFORM: String = "platform"

    // AudioFocusPlugin's own pause and resume, tagged so its own CoreEvents.Pause
    // and CoreEvents.Play never read back as the viewer's — the distinction
    // "never resume a player the user paused" is built on. Native-only: the
    // reference has no audio-focus concept for this to answer to.
    public const val AUDIO_FOCUS: String = "audio-focus"
}
