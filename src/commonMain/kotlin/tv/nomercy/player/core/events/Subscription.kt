// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

// A fun interface runs whatever body it is given, so idempotency is not
// enforced here. That guard belongs to EventEmitter, the single place that
// mints subscriptions (P03 Task 3).
public fun interface Subscription {
    public fun dispose()
}
