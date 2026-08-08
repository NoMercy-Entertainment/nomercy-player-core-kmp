// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.dsp

import kotlin.jvm.JvmInline

/**
 * A frequency band the equaliser controls, or the preamp.
 *
 * The web's type is `number | 'Pre'`, and the preamp genuinely is not a
 * frequency: it moves every band at once, so a client iterating the bands to
 * draw a curve has to leave it out or draw a bar at 0 Hz.
 */
public sealed interface EqBandFrequency {

    /** One band, in hertz. */
    @JvmInline
    public value class Hertz(public val value: Int) : EqBandFrequency

    /** The preamp, which is not a band. */
    public data object Preamp : EqBandFrequency
}
