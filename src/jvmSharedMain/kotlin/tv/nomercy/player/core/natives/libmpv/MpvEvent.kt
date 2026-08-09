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
 * `mpv_event`, the header of everything mpv pushes.
 *
 * The backend polls properties for state and that is the right shape for state:
 * it cannot wedge, and it produces the same spine as the Android and Apple
 * timers. What a poll cannot produce is a REASON. A stream that fails to open
 * leaves every property exactly as it was before the load — duration zero,
 * eof-reached false, no picture — which is indistinguishable from a stream that
 * is merely slow, and the player above shows a spinner until someone closes it.
 *
 * mpv states the reason once, here, and nowhere else.
 *
 * [Structure.ByReference] because `mpv_wait_event` returns a pointer to storage
 * mpv owns; nothing here frees it and nothing may hold it past the next call.
 */
@Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
public open class MpvEvent(pointer: Pointer? = null) : Structure(pointer) {

    @JvmField public var eventId: Int = 0

    /** Negative on failure, and only meaningful for the reply events. */
    @JvmField public var error: Int = 0

    @JvmField public var replyUserdata: Long = 0

    /** The event's own struct, whose type is decided by [eventId]. */
    @JvmField public var data: Pointer? = null

    public class ByReference(pointer: Pointer? = null) : MpvEvent(pointer)
}

/**
 * Event ids as client.h numbers them.
 *
 * Only the ones a player acts on. mpv has twenty-odd and naming them all here
 * would be a list kept in sync with a header for the sake of completeness, when
 * the unhandled ones are already handled correctly — by being ignored.
 */
public object MpvEventId {
    public const val NONE: Int = 0
    public const val SHUTDOWN: Int = 1
    public const val START_FILE: Int = 6
    public const val END_FILE: Int = 7
    public const val FILE_LOADED: Int = 8
}
