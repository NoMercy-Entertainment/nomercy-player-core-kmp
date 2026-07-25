// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.media.PlaylistItem
import kotlin.random.Random

// How a queue gets reordered when shuffle turns on.
//
// A strategy writes the permutation and nothing else. Keeping the playing item
// under the cursor after the reorder is the queue controller's job, because
// that is bookkeeping rather than a choice about ordering — and a consumer
// swapping in weighted or grouped shuffle should not have to reimplement it.
public interface ShuffleStrategy {
    public fun <T : PlaylistItem> order(items: List<T>, currentIndex: Int): List<T>
}

// Uniform Fisher-Yates. Every permutation is equally likely, which the naive
// "sort by random" version is not.
//
// The Random is injected rather than read from a global, so a test can seed it
// and assert an exact order. That is the whole reason this is testable at all.
public class FisherYatesShuffle(private val random: Random = Random.Default) : ShuffleStrategy {
    override fun <T : PlaylistItem> order(items: List<T>, currentIndex: Int): List<T> {
        val result: MutableList<T> = items.toMutableList()
        for (index in result.lastIndex downTo 1) {
            val swap: Int = random.nextInt(index + 1)
            val held: T = result[index]
            result[index] = result[swap]
            result[swap] = held
        }
        return result
    }
}
