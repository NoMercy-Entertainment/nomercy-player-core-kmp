// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

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
