// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

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
