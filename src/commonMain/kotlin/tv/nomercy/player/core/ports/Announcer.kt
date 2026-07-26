// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// How loudly a screen reader should interrupt.
//
// POLITE waits for the current utterance to finish; ASSERTIVE cuts in. The
// difference is "now playing: episode two", which can wait, and "playback
// failed", which cannot.
public enum class AnnouncementLevel {
    POLITE,
    ASSERTIVE,
}

// Says something to a screen reader.
//
// A port rather than an event, for the same reason the translator is one: core
// has no view to hang a live region on and no business holding a platform
// accessibility handle, and a native-only event would be a name a consumer
// finds on one ecosystem and not the other.
//
// The chrome supplies this. On Android it is announceForAccessibility, on Apple
// UIAccessibility.post, on Compose a live-region semantics node. A player built
// without one says nothing, which is what the web player does when it has no
// container.
public fun interface Announcer {
    public fun announce(text: String, level: AnnouncementLevel)
}
