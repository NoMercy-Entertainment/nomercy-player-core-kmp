// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * Whether a backend's segment loader is running or held.
 *
 * Distinct from whether the player is paused: a loader is stopped while the app
 * is backgrounded or the buffer is full, and the player may still be playing out
 * of what is already downloaded.
 */
public enum class BackendLoaderState(public val id: String) {
    RUNNING("running"),
    PAUSED("paused"),
}
