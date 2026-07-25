// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

// Outcome of a dispatchBefore call. Read [data], not the value that was passed
// in: a listener may have reshaped it. [reason] is one of PreventReason
// whenever [prevented] is true, and null otherwise.
public data class BeforeDispatchResult<T>(
    val prevented: Boolean,
    val data: T,
    val reason: String?,
)

// String-identical to the web PreventedReason union. These values travel to
// the prevented-event payloads that consumers switch on, so they are contract,
// not implementation detail.
public object PreventReason {
    public const val ListenerPrevented: String = "listener-prevented"
    public const val DelayRejected: String = "delay-rejected"
    public const val DelayTimeout: String = "delay-timeout"
}
