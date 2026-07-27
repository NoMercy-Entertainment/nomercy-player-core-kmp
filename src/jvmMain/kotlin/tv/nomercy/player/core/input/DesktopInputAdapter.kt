// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

import java.awt.event.KeyEvent

// What a desktop keyboard sends.
//
// Only the keys the player has a name for. Everything else a keyboard can send
// is a letter or a symbol, and those travel as combos rather than through here,
// which is why this table is short and the desktop bindings are not.
//
// There are no colour buttons and no captions key on a keyboard. Binding those
// actions to letters is a decision for the desktop binding table, not something
// to fake here by mapping an unrelated key to a television button.
internal object DesktopInputAdapter : InputAdapter {

    override fun toPlayerKey(nativeKeyCode: Int): PlayerKey? = when (nativeKeyCode) {
        KeyEvent.VK_LEFT -> PlayerKey.Left
        KeyEvent.VK_RIGHT -> PlayerKey.Right
        KeyEvent.VK_UP -> PlayerKey.Up
        KeyEvent.VK_DOWN -> PlayerKey.Down
        KeyEvent.VK_ENTER -> PlayerKey.Center

        // The one key every desktop player in existence uses for play and pause,
        // and it is not a media key. A viewer who has to find the media key on a
        // laptop has already given up.
        KeyEvent.VK_SPACE -> PlayerKey.PlayPause

        KeyEvent.VK_ESCAPE -> PlayerKey.Back

        else -> null
    }
}

public actual fun defaultInputAdapter(): InputAdapter = DesktopInputAdapter
