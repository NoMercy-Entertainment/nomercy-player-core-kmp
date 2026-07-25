// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

// The typed key registry — Kotlin's answer to the web BaseEventMap. Every name
// string is the web key verbatim, because that string is the shared identity
// across the web trio, this library, the docs and the wire. The payload type
// rides on the key, so on(CoreEvents.Time) hands the listener a TimeUpdate with
// no cast and no event-map generic.
//
// SEED. The full registry is ~150 keys and lands once the value types those
// payloads need exist; the plan's appendix holds the authoritative name list
// and the generation path from the parity contract. What is here proves the
// four shapes the rest are built from: a typed payload, a Unit payload, a
// cancellable before-key, and a namespaced name.
//
// Only v2 names appear. The v1 aliases (current, finished, qualityLevels) are
// never registered — they are a compatibility layer the web trio owns.
public object CoreEvents {
    public val Play: EventKey<PlaySource> = EventKey("play")
    public val Pause: EventKey<PlaySource> = EventKey("pause")
    public val Time: EventKey<TimeUpdate> = EventKey("time")
    public val Item: EventKey<ItemChange> = EventKey("item")
    public val Ended: EventKey<Unit> = EventKey("ended")
    public val BeforePlay: EventKey<BeforeEvent<PlaySource>> = EventKey("beforePlay")

    // The property and the payload class deliberately share a spelling: Kotlin
    // resolves EventKey<StreamError> in the type namespace and the property in
    // the value namespace, so both read as the event they belong to.
    public val StreamError: EventKey<StreamError> = EventKey("stream:error")
}
