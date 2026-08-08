// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.keys

/**
 * A combo string to what it does, keyed the way the web keys it.
 *
 * A map rather than a sealed set of actions, because a consumer binds its OWN
 * behaviour — opening a chat overlay, marking a chapter — and a closed action
 * list would make every one of those impossible without a change to this
 * library.
 */
public typealias KeyBindings<P> = Map<String, (P) -> Unit>

/**
 * Where a key handler listens.
 *
 * The web's third case is an HTMLElement and has no counterpart here: a native
 * host attaches its own focus scope through the toolkit. [CONTAINER] is that
 * case — keys fire only when the player has focus — and there is no third.
 */
public enum class KeyScope(public val id: String) {
    /** Anywhere, whether or not the player has focus. */
    DOCUMENT("document"),

    /** Only while the player or something inside it holds focus. */
    CONTAINER("container"),
}

/**
 * How the key handler behaves.
 *
 * [extend] is the difference between adding a shortcut and replacing the set.
 * True installs the defaults and merges [bindings] over them; false clears them
 * first. Both are needed and neither is a safe assumption: a consumer adding one
 * key does not want space to stop working, and one building a custom scheme does
 * not want space bound behind its back.
 */
public data class KeyHandlerOptions<P>(
    /** Where the listener is attached. */
    val scope: KeyScope = KeyScope.DOCUMENT,

    /** Bindings merged over the defaults. Same combo here wins. */
    val bindings: KeyBindings<P> = emptyMap(),

    /** Keep the default bindings, or start from nothing. */
    val extend: Boolean = true,

    /**
     * Consulted before any binding fires. Return false to suppress key handling
     * entirely — a modal, a chat overlay, a text field.
     */
    val whenAllowed: ((KeyPress) -> Boolean)? = null,

    /**
     * Least time between consecutive fires, in milliseconds.
     *
     * A held arrow key repeats at the OS rate, which turns one press into a
     * seek per frame. Zero disables the throttle for a consumer that wants that.
     */
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,

    /**
     * Ignore the hardware media keys.
     *
     * For a page or screen where the OS, or another player, should own play and
     * pause. Off by default: the media keys work.
     */
    val disableMediaControls: Boolean = false,
) {
    public companion object {
        public const val DEFAULT_COOLDOWN_MS: Long = 300L
    }
}

/**
 * The parts of a key press a gate can decide on.
 *
 * Not the platform's event object. A KeyboardEvent, a KeyEvent and an NSEvent
 * agree on this much and on almost nothing else, and a predicate written against
 * one of them is a predicate that only compiles on one platform.
 */
public data class KeyPress(
    /** The key's name, as the web spells it: `ArrowLeft`, `Space`, `m`. */
    val key: String,
    val alt: Boolean = false,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val shift: Boolean = false,
    /** True when the key is repeating because it is held down. */
    val repeat: Boolean = false,
)
