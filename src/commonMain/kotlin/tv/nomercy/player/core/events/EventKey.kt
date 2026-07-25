// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

/**
 * Typed event key. Carries the web `BaseEventMap` string [name] (the shared
 * wire + docs identity) AND the payload type [T], so `on(key) { it.field }` is
 * fully typed without Kotlin `keyof`. The Kotlin mirror of a `BaseEventMap`
 * entry (spec §4.1).
 */
public class EventKey<T>(public val name: String)
