// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

// Where the playhead went and who moved it.
public data class SeekPosition(val time: Double, val source: String? = null)

public data class VolumeChange(val level: Int)

public data class MuteChange(val muted: Boolean)

// Why an action did not happen. The reason is one of PreventReason, so a
// listener can tell a plugin's veto from a timeout without parsing a message.
public data class PreventedAction(val reason: String?, val source: String? = null)
