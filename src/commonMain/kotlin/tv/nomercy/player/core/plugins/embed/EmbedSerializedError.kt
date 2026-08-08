// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.embed

import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.Severity

/**
 * A player error, flattened for a host that cannot receive an exception.
 *
 * [context] is a map of STRINGS rather than the error's own payload, because
 * this crosses to another origin: an object graph carrying engine handles or a
 * file path is a leak, and one that cannot be serialised silently arrives as an
 * empty object on the far side.
 */
public data class EmbedSerializedError(
    val code: String,
    val severity: Severity,
    val scope: ErrorScope,
    val message: String? = null,
    val suggestion: String? = null,
    val context: Map<String, String> = emptyMap(),
)
