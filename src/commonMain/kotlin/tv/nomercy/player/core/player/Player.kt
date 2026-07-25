// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

import kotlinx.coroutines.flow.StateFlow
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.plugin.Plugin
import kotlin.reflect.KClass

// The player surface, as a declaration. The implementation is delegated
// controllers over a media backend and lands in later plans; this is the type
// everything else is written against in the meantime, and the thing a
// consumer's own player can implement.
//
// [E] brands the library's event map — Unit for plain core, a video or music
// marker in those libraries. Payload typing comes from the EventKey passed to
// on, never from E, so a core-typed listener works on a video player.
//
// The bare-noun getter/setter pairs (time() and time(seconds)) are the web
// contract's shape, not an accident. detekt's MethodOverloading rule is off in
// this repo for the same reason.
//
// The width is the contract's, so the two rules that measure width are
// suppressed here and nowhere else. Splitting this into transport, queue and
// event interfaces would let a consumer implement two thirds of a player, which
// is not a thing that exists.
@Suppress("ComplexInterface", "TooManyFunctions")
public interface Player<E> {

    public fun setup(config: PlayerConfig): Player<E>

    public fun play(opts: ActionOptions = ActionOptions())
    public fun pause(opts: ActionOptions = ActionOptions())
    public fun stop(opts: ActionOptions = ActionOptions())

    public fun time(): Double
    public fun time(seconds: Double)

    public fun next()
    public fun previous()

    public fun item(): PlaylistItem?
    public fun queue(): List<PlaylistItem>
    public fun index(): Int

    public fun <T> on(key: EventKey<T>, fn: (T) -> Unit): Subscription
    public fun <T> once(key: EventKey<T>, fn: (T) -> Unit): Subscription
    public fun <T> off(key: EventKey<T>, fn: (T) -> Unit)
    public fun on(name: String, fn: (Any?) -> Unit): Subscription

    public fun <P : Plugin<*>> addPlugin(plugin: P, opts: Any? = null): Player<E>
    public fun <P : Plugin<*>> getPlugin(type: KClass<P>): P?

    // state() is the snapshot for imperative callers; stateFlow is the same
    // value for anything that renders. They must never disagree.
    public fun state(): PlayerState
    public val stateFlow: StateFlow<PlayerState>

    public fun dispose()
}
