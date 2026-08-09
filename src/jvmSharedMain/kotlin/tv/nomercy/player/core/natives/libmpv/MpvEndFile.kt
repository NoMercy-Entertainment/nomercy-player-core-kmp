// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libmpv

import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * `mpv_event_end_file`: why mpv stopped playing something.
 *
 * The distinction this carries is the one a viewer feels. A file that reached
 * its end and a file that could not be opened both leave mpv idle with no
 * picture, and only [reason] separates them — [MpvEndFileReason.EOF] is the
 * credits rolling and [MpvEndFileReason.ERROR] is a spinner that never stops.
 */
@Structure.FieldOrder("reason", "error", "playlistEntryId", "playlistInsertId", "playlistInsertNumEntries")
public open class MpvEndFile(pointer: Pointer? = null) : Structure(pointer) {

    @JvmField public var reason: Int = 0

    /** An mpv error code, readable through `mpv_error_string`, when [reason] is ERROR. */
    @JvmField public var error: Int = 0

    @JvmField public var playlistEntryId: Long = 0

    @JvmField public var playlistInsertId: Long = 0

    @JvmField public var playlistInsertNumEntries: Int = 0

    public class ByReference(pointer: Pointer? = null) : MpvEndFile(pointer)
}

/** Why a file ended, as client.h numbers it. */
public object MpvEndFileReason {
    public const val EOF: Int = 0
    public const val STOP: Int = 2
    public const val QUIT: Int = 3
    public const val ERROR: Int = 4
    public const val REDIRECT: Int = 5
}
