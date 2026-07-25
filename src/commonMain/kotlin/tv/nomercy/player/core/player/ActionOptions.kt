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
}
