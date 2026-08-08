// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * What a media list just did.
 *
 * Ten distinct verbs rather than one `change`, because a view that redraws the
 * whole queue on every mutation loses its scroll position and its focus — and on
 * a television, losing focus mid-queue puts the D-pad somewhere nobody chose.
 * [CHANGE] is still there for a listener that genuinely wants all of them.
 */
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
