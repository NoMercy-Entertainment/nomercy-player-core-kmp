// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.util.UUID

public actual fun defaultIdGenerator(): IdGenerator = object : IdGenerator {
    override fun next(): String = UUID.randomUUID().toString()
}
