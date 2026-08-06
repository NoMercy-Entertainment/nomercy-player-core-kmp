// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

import tv.nomercy.player.core.plugin.Plugin

// The half of key handling that is the same everywhere.
//
// A television and a desktop disagree about what the arrow keys do and agree
// about everything else, so the difference belongs in a subclass rather than in
// a branch. What lives here is the table, the dispatch, and the fact that
// defaults are installed when the plugin is.
//
// Open rather than sealed, because a consumer with a remote nobody has seen
// before should be able to add to this rather than fork it.
public abstract class KeyHandlerPlugin<O : Any>(
    nowMs: () -> Long,
) : Plugin<O>() {

    protected val bindings: KeyBindingTable = KeyBindingTable(nowMs)

    // Installed at registration rather than at the first press, so a chrome can
    // ask what is bound in order to draw a help sheet before anybody has pressed
    // anything.
    override fun use() {
        addDefaults()
    }

    protected abstract fun addDefaults()

    // Whether the press was ours. A platform passes this back to the system when
    // it is false, which is what leaves the volume keys and the back button
    // working.
    public open fun handle(combo: KeyCombo): Boolean = bindings.handle(combo)

    public open fun handle(key: PlayerKey): Boolean = bindings.handle(key)

    // Rebinding, from outside the plugin.
    //
    // The table has had bind/replace/unbind all along and the plugin kept it
    // protected, so the whole surface was reachable only by subclassing. The
    // reference puts these on the PLUGIN because remapping a key is something a
    // consumer does — a settings screen offering "press a key for skip forward"
    // could not be built against this without writing a subclass first.
    //
    // The combo arrives as a string here, as it does there, so a binding read
    // out of stored preferences needs no vocabulary of its own.
    public open fun bind(combo: String, action: () -> Unit) {
        bindings.bind(parseCombo(combo), action = action)
    }

    public open fun unbind(combo: String) {
        bindings.unbind(parseCombo(combo))
    }

    // An alias for bind, and deliberately still here: the reference keeps it to
    // let a caller say "swapping, not adding", and the table underneath does
    // differ — replace clears the cooldown so the new action is not made to wait
    // out the old one's.
    public open fun replace(combo: String, action: () -> Unit) {
        bindings.replace(parseCombo(combo), action = action)
    }

    // A snapshot, so a chrome can draw a help sheet. Mutating what comes back
    // does not touch the live table, which is what the reference promises.
    public open fun bindings(): Map<String, () -> Unit> = bindings.snapshot()
}

// `shift+ArrowLeft` and `Shift+arrowleft` are the same binding.
//
// Modifiers are matched case-insensitively and re-emitted in the fixed order
// keyCombo() writes, because the table is keyed on that spelling and two
// spellings of one chord would be two entries. The KEY keeps its own case: the
// web compares it against KeyboardEvent.key, where `ArrowLeft` and `a` are
// spelled exactly so.
internal fun parseCombo(combo: String): KeyCombo {
    val parts: List<String> = combo.split('+').map { it.trim() }.filter { it.isNotEmpty() }
    val modifiers: Set<String> = parts.dropLast(1).mapTo(mutableSetOf()) { it.lowercase() }

    return keyCombo(
        key = parts.lastOrNull().orEmpty(),
        shift = "shift" in modifiers,
        ctrl = "ctrl" in modifiers,
        alt = "alt" in modifiers,
        meta = "meta" in modifiers,
    )
}
