// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// What kind of thing the sound is coming out of.
//
// A chrome draws a different icon for each and, more usefully, a policy can act
// on them: pausing when headphones are unplugged is the behaviour every viewer
// expects and it needs to know a pair of headphones from a television.
public enum class AudioOutputKind {
    SPEAKER,
    HEADPHONES,
    BLUETOOTH,
    HDMI,
    USB,
    CAST,
    UNKNOWN,
}

// One place sound can go.
//
// [id] is the platform's own handle for it, opaque here. Names are not ids: two
// identical earbuds are two devices with one name, and a router keying on the
// name would send audio to whichever it found first.
public data class AudioOutput(
    val id: String,
    val name: String,
    val kind: AudioOutputKind = AudioOutputKind.UNKNOWN,
    val isDefault: Boolean = false,
)

// Where sound goes, and how to send it somewhere else.
//
// Nullable on Platform rather than a no-op implementation, because the honest
// answers differ: a desktop can enumerate and switch, iOS can enumerate and only
// suggest, and a browser needs a user gesture to even show the list. A chrome
// asking for a picker should be able to tell "no outputs" from "this platform
// does not let me ask".
public interface AudioOutputRouter {
    // Suspending because enumeration is a system call on every platform that
    // has one, and on some it prompts.
    public suspend fun outputs(): List<AudioOutput>

    public suspend fun current(): AudioOutput?

    // False when the platform refused, which is not an error worth throwing
    // over: iOS routes audio by policy and takes a preference as a suggestion,
    // so a caller has to be able to find out its choice did not take without
    // catching anything.
    public suspend fun select(id: String): Boolean
}
