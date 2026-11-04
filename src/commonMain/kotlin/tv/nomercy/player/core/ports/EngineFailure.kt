// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * What an engine reports with [CanonicalBackendEvent.ERROR] when it knows the
 * HTTP status the server answered.
 *
 * Reads as its [code] everywhere the payload is printed, so a listener that
 * treats the payload as the code string keeps working. [httpStatus] is what
 * next() reads to skip, refresh or raise a fatal.
 */
public data class EngineFailure(
    val code: String,
    val httpStatus: Int,
) {
    override fun toString(): String = code
}
