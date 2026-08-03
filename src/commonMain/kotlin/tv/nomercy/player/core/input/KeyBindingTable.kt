// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

// What each press does, and when it is allowed to do it again.
//
// The cooldown is the part that earns its place. A remote held down repeats at
// whatever rate the platform chooses, and a seek bound without one jumps several
// minutes from a press somebody meant as one step. It is timestamp arithmetic
// rather than a coroutine, so a binding table has no lifecycle and nothing to
// dispose.
public class KeyBindingTable(private val nowMs: () -> Long) {

    private val bindings: MutableMap<String, Binding> = mutableMapOf()

    private val lastFiredMs: MutableMap<String, Long> = mutableMapOf()

    // Guarded because a binding that is wrong in one state is worse than one
    // that is missing: a left press that seeks while a menu is open moves the
    // film out from under whoever was reading the menu.
    public fun bind(
        combo: KeyCombo,
        cooldownMs: Long = 0,
        enabled: () -> Boolean = { true },
        action: () -> Unit,
    ) {
        bindings[combo.canonical] = Binding(cooldownMs, enabled, action)
    }

    public fun bind(
        key: PlayerKey,
        cooldownMs: Long = 0,
        enabled: () -> Boolean = { true },
        action: () -> Unit,
    ): Unit = bind(key.asCombo(), cooldownMs, enabled, action)

    // Replacing clears the cooldown with it. The new action has not run, so
    // making the viewer wait out the old one is a press that does nothing for
    // no reason anybody could explain.
    public fun replace(
        combo: KeyCombo,
        cooldownMs: Long = 0,
        enabled: () -> Boolean = { true },
        action: () -> Unit,
    ) {
        lastFiredMs.remove(combo.canonical)
        bind(combo, cooldownMs, enabled, action)
    }

    public fun unbind(combo: KeyCombo) {
        bindings.remove(combo.canonical)
        lastFiredMs.remove(combo.canonical)
    }

    public fun isBound(combo: KeyCombo): Boolean = bindings.containsKey(combo.canonical)

    // Every combo that would fire right now, in the order they were bound.
    //
    // For the one thing a binding table is for besides firing: drawing the
    // shortcut sheet. The reference's handler binds ? to open one and can,
    // because its table is readable; this one could only be asked about a combo
    // somebody already suspected, so a sheet would have had to restate all
    // fifty-three bindings and then drift from them.
    //
    // Guarded bindings are left out, and the first test written against this
    // found out why: the video handler binds Back behind a form-factor guard,
    // so a desktop sheet listing everything BOUND promised a key that does
    // nothing there. A shortcut a viewer presses and sees ignored is worse than
    // one that was never offered — they conclude the player is broken.
    //
    // Insertion order because that is the order the groups install them, so a
    // sheet reads playback, then navigation, then volume, the way the handler
    // is written.
    public fun bound(): List<KeyCombo> = bindings
        .filterValues { it.enabled() }
        .keys
        .map { KeyCombo(it) }

    // Answers whether the press was consumed, which is what a platform needs in
    // order to decide whether to pass it on. Claiming an unbound key is how a
    // television stops responding to its own back button.
    public fun handle(combo: KeyCombo): Boolean {
        val binding: Binding = bindings[combo.canonical] ?: return false

        val now: Long = nowMs()
        val last: Long? = lastFiredMs[combo.canonical]
        val stillCooling: Boolean = last != null && now - last < binding.cooldownMs

        if (!binding.enabled() || stillCooling) return false

        lastFiredMs[combo.canonical] = now
        binding.action()
        return true
    }

    public fun handle(key: PlayerKey): Boolean = handle(key.asCombo())

    private class Binding(
        val cooldownMs: Long,
        val enabled: () -> Boolean,
        val action: () -> Unit,
    )
}
