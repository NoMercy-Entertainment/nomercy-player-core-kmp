// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Playing the next track before the current one finishes.
//
// Kept separate from MediaBackend because most engines cannot do it and
// pretending otherwise would mean every video backend writing no-ops. An engine
// that can says so through [supportsCrossfade], and the transition strategy
// falls back to a hard cut when it cannot.
public interface TransitionBackend {
    public fun supportsCrossfade(): Boolean

    public suspend fun loadSecondary(url: String)

    // Decoding the first frames before the fade starts is what stops a
    // crossfade beginning with silence.
    public suspend fun primeSecondary(seekMs: Long = 0L)

    public suspend fun crossfade(durationMs: Long, curve: CrossfadeCurve)

    public fun disposeSecondary()

    public fun secondaryGain(): Float
    public fun secondaryGain(value: Float)
}
