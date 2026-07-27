// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

// One press, written the same way every time.
//
// A binding table is a map, so two spellings of the same press are two entries
// and one of them never fires. The modifiers therefore go in a fixed order
// whatever order they were given in, which is the whole job: a caller writing
// "ctrl+shift+k" and a keyboard reporting shift before ctrl have to arrive at
// the same string.
public data class KeyCombo(val canonical: String)

public fun PlayerKey.asCombo(): KeyCombo = KeyCombo(combo)

// The order is fixed rather than alphabetical because it is the order the web
// reference already writes, and a binding table shared between them has to
// agree on both sides.
public fun keyCombo(
    key: String,
    shift: Boolean = false,
    ctrl: Boolean = false,
    alt: Boolean = false,
    meta: Boolean = false,
): KeyCombo {
    val parts: MutableList<String> = mutableListOf()
    if (ctrl) parts += "ctrl"
    if (alt) parts += "alt"
    if (shift) parts += "shift"
    if (meta) parts += "meta"
    parts += key

    return KeyCombo(parts.joinToString("+"))
}
