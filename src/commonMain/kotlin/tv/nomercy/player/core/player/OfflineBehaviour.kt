// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

/**
 * What a player does when the network drops.
 *
 * [CONTINUE_BUFFERED] is the one that matters on a train: there are often thirty
 * seconds already downloaded, and pausing the moment the signal goes throws them
 * away for nothing.
 */
public enum class OfflineBehaviour(public val id: String) {
    PAUSE("pause"),
    CONTINUE_BUFFERED("continue-buffered"),
    IGNORE("ignore"),
}
