// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

// Which mutations announce themselves before they happen.
//
// A sealed type where the reference has `false | 'all' | string[] | undefined`,
// which is the same four answers with the undefined one named. Naming it
// matters here: "the consumer said nothing" and "the consumer said none" are
// different instructions and a nullable boolean cannot hold both.
public sealed interface MutationGuards {

    // Never fire. The fast path, for a consumer that has no guards and does not
    // want the dispatch.
    public data object None : MutationGuards

    // Always fire, hot mutations included. What a devtools overlay turns on.
    public data object All : MutationGuards

    // The default: everything except the hot ones.
    public data object Default : MutationGuards

    // Everything except the hot ones, plus the hot ones named here. The
    // reference's string array, and it ADDS rather than replaces: a consumer
    // naming "time" wants the position guard on top of the ordinary ones, not
    // instead of them.
    public data class Including(val hot: Set<String>) : MutationGuards
}

// The mutations that fire too often to guard unless somebody asks.
//
// Verbatim from the reference, and the omissions are the interesting part: it
// used to list volume and playbackRate and no longer does, because both have
// their own always-on before-events that fire whatever this is set to. Seeking
// has beforeSeek for the same reason. So a consumer intercepting those does not
// have to opt into the generic guard surface, and what is left here is genuinely
// per-tick: the position write, the bandwidth estimate and the metric record.
public val HOT_MUTATIONS: Set<String> = setOf("time", "bandwidth", "recordMetric")

// Whether [method] should announce itself under this setting.
public fun MutationGuards.guards(method: String): Boolean = when (this) {
    MutationGuards.None -> false
    MutationGuards.All -> true
    MutationGuards.Default -> method !in HOT_MUTATIONS
    is MutationGuards.Including -> method !in HOT_MUTATIONS || method in hot
}
