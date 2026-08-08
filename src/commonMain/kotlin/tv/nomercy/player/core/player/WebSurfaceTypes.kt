// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

import kotlin.jvm.JvmInline

/**
 * How urgently a screen reader should announce something.
 *
 * `polite` waits for a pause; `assertive` interrupts. There is no third, and the
 * difference is not cosmetic — announcing a time update assertively talks over
 * whatever the person was listening to, several times a second.
 */
public enum class AriaLiveLevel(public val id: String) {
    POLITE("polite"),
    ASSERTIVE("assertive"),
}

/**
 * Why something the player was asked to do did not happen.
 *
 * Three reasons, and they are different failures: a listener said no, a delay
 * hook rejected, or a delay hook never answered. Collapsing them into "denied"
 * loses the one distinction a caller can act on — the first is a decision, the
 * third is a bug in somebody's handler.
 */
public enum class PreventedReason(public val id: String) {
    /** A listener called preventDefault. */
    LISTENER_PREVENTED("listener-prevented"),

    /** A delay hook rejected. */
    DELAY_REJECTED("delay-rejected"),

    /** A delay hook never settled in time. */
    DELAY_TIMEOUT("delay-timeout"),
}

/**
 * What a player instance is called.
 *
 * A string or a number on the web, so a host numbering its players and one
 * naming them both work. Kotlin has no untagged union, so it is a value class
 * over the string form and [of] takes either — a number formatted the same way
 * the web formats it, rather than two ids that look different for one player.
 */
@JvmInline
public value class PlayerConstructorId(public val value: String) {
    public companion object {
        public fun of(id: Int): PlayerConstructorId = PlayerConstructorId(id.toString())
    }
}

/**
 * A frequency band the equaliser controls, or the preamp.
 *
 * The web's type is `number | 'Pre'` and the preamp genuinely is not a
 * frequency: it moves every band at once, and a client iterating the bands to
 * draw a curve has to leave it out or draw a bar at 0 Hz.
 */
public sealed interface EqBandFrequency {

    /** One band, in hertz. */
    @JvmInline
    public value class Hertz(public val value: Int) : EqBandFrequency

    /** The preamp, which is not a band. */
    public data object Preamp : EqBandFrequency
}

/**
 * What the message plugin was asked to show.
 *
 * A bare string is the common case and stays one call. The full form carries a
 * duration, because a message a consumer wants on screen for eight seconds and
 * one it wants for one are the same call with a different number, not two APIs.
 */
public sealed interface MessageInput {

    public val text: String

    @JvmInline
    public value class Text(public override val text: String) : MessageInput

    public data class Timed(
        public override val text: String,
        public val durationMs: Long,
    ) : MessageInput
}

/** What a media list just did. */
public enum class MediaListEvent(public val id: String) {
    CHANGE("change"),
    APPEND("append"),
    PREPEND("prepend"),
    INSERT("insert"),
    REMOVE("remove"),
    MOVE("move"),
    CLEAR("clear"),
    SHUFFLE("shuffle"),
    SORT("sort"),
    ITEM("item"),
}

/** Whether a backend's loader is running or held. */
public enum class BackendLoaderState(public val id: String) {
    RUNNING("running"),
    PAUSED("paused"),
}
