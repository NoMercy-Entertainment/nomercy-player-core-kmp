// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

// Turning what the platform sent into something the player has a name for.
//
// Each platform has its own numbers and its own spellings, and the translation
// is the only part of key handling that cannot be shared. Keeping it behind one
// function is what makes the binding table platform-agnostic: everything above
// this line deals in PlayerKey, and nothing above it has ever seen a key code.
public interface InputAdapter {

    // Null for a key the player has no name for, which is most of them. That is
    // not a failure: it is how the press stays with the platform, and it is what
    // leaves the system's own shortcuts working.
    public fun toPlayerKey(nativeKeyCode: Int): PlayerKey?
}

// A press that has no PlayerKey and is still worth binding.
//
// Desktop keyboards are the reason. A letter, a bracket or a question mark is a
// perfectly good binding and there are far too many of them to name, so they
// travel as combos and skip the enum entirely.
public fun namedKey(
    name: String,
    shift: Boolean = false,
    ctrl: Boolean = false,
    alt: Boolean = false,
    meta: Boolean = false,
): KeyCombo = keyCombo(name, shift = shift, ctrl = ctrl, alt = alt, meta = meta)

public expect fun defaultInputAdapter(): InputAdapter
