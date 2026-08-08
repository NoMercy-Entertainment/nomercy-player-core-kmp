// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.jvm.JvmInline

/**
 * Which engine something came from.
 *
 * A value class over the id rather than an enum: the set is open, because a
 * consumer shipping its own backend gets an id too, and an enum here would make
 * this library the only place a backend can be named.
 */
@JvmInline
public value class BackendId(public val value: String)
