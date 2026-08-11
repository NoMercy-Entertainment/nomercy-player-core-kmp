// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

// A press arrives on this receiver as a DOM KeyboardEvent, not a native key
// code int — Compose Multiplatform's own wasmJs input handling already turns
// those into key events at the UI layer (see CastReceiverRoot / CastNavHost),
// so there is no native code here to translate, same as Apple's NoInputAdapter.
internal object NoInputAdapter : InputAdapter {
    override fun toPlayerKey(nativeKeyCode: Int): PlayerKey? = null
}

public actual fun defaultInputAdapter(): InputAdapter = NoInputAdapter
