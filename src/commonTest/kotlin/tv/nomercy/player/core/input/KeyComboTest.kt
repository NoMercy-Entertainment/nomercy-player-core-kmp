// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

import kotlin.test.Test
import kotlin.test.assertEquals

// Writing one press the same way every time.
//
// A binding table is a map, so two spellings of the same press are two entries
// and one of them never fires. Nothing about that failure looks like a spelling
// problem from the outside: a key simply does nothing.
class KeyComboTest {

    @Test
    fun theModifierOrderIsFixedRatherThanTheOrderTheyArrivedIn() {
        assertEquals(
            keyCombo("k", ctrl = true, shift = true),
            keyCombo("k", shift = true, ctrl = true),
        )
    }

    @Test
    fun theOrderIsTheOneTheWebReferenceWrites() {
        // Both sides share binding tables, so this is not an internal detail:
        // a table authored against one has to resolve on the other.
        // `canonicalKey` in the kit's key-handler pushes alt, then ctrl, then
        // shift. Meta has no counterpart there and trails the three.
        val all: KeyCombo = keyCombo("k", shift = true, ctrl = true, alt = true, meta = true)

        assertEquals("alt+ctrl+shift+meta+k", all.canonical)
    }

    @Test
    fun aPlainKeyCarriesNoDecoration() {
        assertEquals("ArrowLeft", keyCombo("ArrowLeft").canonical)
    }

    @Test
    fun everyRemoteKeyHasTheNameTheReferenceBindsAgainst() {
        // These are the ones that must mean the same thing on every device, and
        // a wrong name is not an error: it is a coloured button that does
        // nothing on one client and works on another.
        assertEquals("ColorF0Red", PlayerKey.ColorRed.asCombo().canonical)
        assertEquals("ArrowLeft", PlayerKey.Left.asCombo().canonical)
        assertEquals("MediaTrackNext", PlayerKey.MediaNext.asCombo().canonical)
        assertEquals("AudioVolumeMute", PlayerKey.VolumeMute.asCombo().canonical)
    }

    @Test
    fun noTwoKeysShareASpelling() {
        // A duplicate would make one of them unbindable, and which one depends
        // on the order the table was filled in.
        val spellings: List<String> = PlayerKey.entries.map { it.combo }

        assertEquals(spellings.size, spellings.toSet().size)
    }
}
