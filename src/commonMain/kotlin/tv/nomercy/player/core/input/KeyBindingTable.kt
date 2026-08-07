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
public class KeyBindingTable(
    /**
     * What a binding waits, when it does not say.
     *
     * The reference throttles at the PLUGIN level and defaults to 300ms, which
     * is what stops a held arrow key from seeking a film to its end in a
     * second. Every binding here defaulted to zero and nothing set otherwise,
     * so a held key repeated at the keyboard's own rate.
     */
    private val defaultCooldownMs: Long = DEFAULT_COOLDOWN_MS,
    // Last, so a caller writing the clock as a trailing lambda still binds it to
    // the clock. Added after it, every existing call site silently handed its
    // lambda to the cooldown instead — and a Long is not a function, so the
    // whole suite stopped compiling rather than quietly misbehaving.
    private val nowMs: () -> Long,
) {

    private val bindings: MutableMap<String, Binding> = mutableMapOf()

    private val lastFiredMs: MutableMap<String, Long> = mutableMapOf()

    // Guarded because a binding that is wrong in one state is worse than one
    // that is missing: a left press that seeks while a menu is open moves the
    // film out from under whoever was reading the menu.
    public fun bind(
        combo: KeyCombo,
        cooldownMs: Long = defaultCooldownMs,
        enabled: () -> Boolean = { true },
        action: () -> Unit,
    ) {
        bindings[combo.canonical] = Binding(cooldownMs, enabled, action)
    }

    public fun bind(
        key: PlayerKey,
        cooldownMs: Long = defaultCooldownMs,
        enabled: () -> Boolean = { true },
        action: () -> Unit,
    ): Unit = bind(key.asCombo(), cooldownMs, enabled, action)

    // Replacing clears the cooldown with it. The new action has not run, so
    // making the viewer wait out the old one is a press that does nothing for
    // no reason anybody could explain.
    public fun replace(
        combo: KeyCombo,
        cooldownMs: Long = defaultCooldownMs,
        enabled: () -> Boolean = { true },
        action: () -> Unit,
    ) {
        lastFiredMs.remove(combo.canonical)
        bind(combo, cooldownMs, enabled, action)
    }

    // What is bound, as a value a caller can hold. Each entry runs the binding
    // that is live WHEN IT IS CALLED rather than the one captured now, so a
    // snapshot taken before a rebind does not fire the old action; and removing
    // an entry from the copy does not unbind anything.
    public fun snapshot(): Map<String, () -> Unit> =
        bindings.keys.associateWith { key -> { bindings[key]?.action?.invoke() } }

    public fun unbind(combo: KeyCombo) {
        bindings.remove(combo.canonical)
        lastFiredMs.remove(combo.canonical)
    }

    public fun isBound(combo: KeyCombo): Boolean = bindings.containsKey(combo.canonical)

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

    public companion object {
        /** The reference's default throttle, in milliseconds. */
        public const val DEFAULT_COOLDOWN_MS: Long = 300
    }
}
