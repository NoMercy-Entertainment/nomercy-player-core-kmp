// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

// Apple sends presses as focus movement and menu commands rather than as key
// codes, so there is no table to write here.
//
// A tvOS host translates what its own remote reports and calls the binding table
// with a PlayerKey directly. This exists so the seam has one shape on every
// platform and a caller never has to ask whether the adapter is there.
internal object NoInputAdapter : InputAdapter {

    override fun toPlayerKey(nativeKeyCode: Int): PlayerKey? = null
}

public actual fun defaultInputAdapter(): InputAdapter = NoInputAdapter
