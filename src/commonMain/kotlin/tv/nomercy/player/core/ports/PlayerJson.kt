// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.serialization.json.Json

// One JSON codec for the whole port layer.
//
// Lenient about unknown keys on purpose: stored data outlives the version that
// wrote it, and a field added in a later release must not make an older blob
// unreadable — nor the reverse.
public object PlayerJson {
    public val instance: Json = Json { ignoreUnknownKeys = true }
}
