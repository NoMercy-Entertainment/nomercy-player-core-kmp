// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

// Where the slider is, turned into how loud that should be.
//
// Loudness is heard logarithmically. Writing the slider's own position straight
// to an engine's gain puts almost the whole audible range in the bottom third of
// the strip: a viewer drags from silence to a third and hears nothing much
// happen, then the rest of the travel goes from quiet to full in a couple of
// centimetres.
//
// A square law spreads the change evenly enough that an equal step sounds like an
// equal step wherever it is taken, and it passes through both ends exactly —
// zero is silence and one is unity, with no artificial floor to work around.
//
//   0.1 -> 0.01   0.3 -> 0.09   0.5 -> 0.25   0.6 -> 0.36   1.0 -> 1.0
//
// Deliberately not the -60 dB law a mixing console uses. That shape suits fine
// gain-riding on a mix already at a sensible level; this control answers "how
// loud is this in the room", and a -60 dB range leaves the lower half of the
// strip inaudible.
public fun perceptualGain(position: Float): Float {
    val clamped: Float = position.coerceIn(0f, 1f)
    return clamped * clamped
}
