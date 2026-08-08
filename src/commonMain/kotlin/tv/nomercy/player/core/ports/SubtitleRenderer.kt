// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Turns subtitle markup into whatever this toolkit draws.
 *
 * The web returns a DOM fragment. There is no fragment here, so a renderer is
 * handed the markup and gives back its own drawable — which is the seam that
 * lets Compose, SwiftUI and libass each render the same cue their own way
 * without the cue pipeline knowing which one it has.
 */
public interface SubtitleRenderer<R> {
    public fun render(markup: String): R
}
