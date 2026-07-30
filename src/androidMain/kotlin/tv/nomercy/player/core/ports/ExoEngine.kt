// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.exoplayer.ExoPlayer

// A built engine and the factory that built its renderers.
//
// The factory is kept because it stays useful after build(): it owns the codec
// adapter factory, and the HDR tone-map request is set there rather than on the
// player. Media3 verifies the calling thread on every player setter, so a request
// that had to go through the engine could only be made from the main thread — and
// this one is decided on a tracks change.
internal class ExoEngine(
    val player: ExoPlayer,
    val renderers: AudioPassthroughRenderersFactory,
)
