// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

import tv.nomercy.player.core.plugin.Plugin

/**
 * Where key presses are picked up.
 *
 * The reference's `scope`, which is `'document' | 'container' | HTMLElement`
 * and defaults to `'document'` — global, firing whether or not anything in the
 * player has focus.
 *
 * The port had no counterpart and behaved as `Container` always, because the
 * only delivery it had was an `onKeyEvent` on the chrome's focusable. That is
 * why clicking any control outside the player — a route button, a playlist row
 * — stopped every shortcut working: focus moved and the keys had nowhere to
 * arrive. On the web the same click changes nothing, because the listener is on
 * the document.
 */
public enum class KeyHandlerScope {
    /** Anywhere in the window. The reference's `'document'`, and the default. */
    Window,

    /** Only while the player or one of its children holds focus. */
    Container,
}

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
    /**
     * Where presses are picked up. [KeyHandlerScope.Window] by default, which is
     * what the reference defaults to; a consumer embedding the player beside its
     * own keyboard UI passes [KeyHandlerScope.Container].
     */
    public val scope: KeyHandlerScope = KeyHandlerScope.Window,
    /**
     * Whether the default bindings are installed at all.
     *
     * The reference's `extend`: true keeps them and merges a consumer's on top,
     * false clears them so a fully custom set can be built. Without it a
     * consumer wanting its own scheme had to unbind the defaults one at a time,
     * by name, and stay in step with every default the library later added.
     */
    private val extend: Boolean = true,
    /**
     * Minimum milliseconds between consecutive fires. The reference's
     * `cooldownMs`, default 300, zero to disable.
     */
    cooldownMs: Long = KeyBindingTable.DEFAULT_COOLDOWN_MS,
    /**
     * Called before any binding fires; false suppresses the press.
     *
     * The reference's `when`, for the states where shortcuts must not run at
     * all — a modal, a chat overlay, a text field. Without it a consumer had to
     * disable the whole plugin and re-enable it, which loses every binding it
     * had rebound.
     */
    private val gate: ((KeyCombo) -> Boolean)? = null,
    /**
     * Silently ignore the hardware media keys.
     *
     * The reference's `disableMediaControls`, for a page where the OS or
     * another player should own them.
     */
    private val disableMediaControls: Boolean = false,
) : Plugin<O>() {

    protected val bindings: KeyBindingTable = KeyBindingTable(cooldownMs, nowMs)

    // Installed at registration rather than at the first press, so a chrome can
    // ask what is bound in order to draw a help sheet before anybody has pressed
    // anything.
    override fun use() {
        if (extend) addDefaults()
    }

    protected abstract fun addDefaults()

    // Whether the press was ours. A platform passes this back to the system when
    // it is false, which is what leaves the volume keys and the back button
    // working.
    public open fun handle(combo: KeyCombo): Boolean {
        // The gate first, then the media-key filter, then the table. Both
        // answer false so the press goes back to the platform rather than being
        // swallowed — a shortcut suppressed while a modal is open must still let
        // the modal have the key.
        if (gate?.invoke(combo) == false) return false
        if (disableMediaControls && combo.canonical in MEDIA_KEYS) return false

        return bindings.handle(combo)
    }

    public open fun handle(key: PlayerKey): Boolean = handle(key.asCombo())

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

    private companion object {
        // The W3C hardware media keys the reference names, and only those. A
        // list rather than a prefix test because `MediaPlay` and `MediaPlayer`
        // are not the same thing.
        val MEDIA_KEYS: Set<String> = setOf(
            "MediaPlay",
            "MediaPause",
            "MediaPlayPause",
            "MediaStop",
            "MediaRewind",
            "MediaFastForward",
            "MediaTrackNext",
            "MediaTrackPrevious",
        )
    }
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
