// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * A per-URL authorization value for media the ENGINE fetches.
 *
 * Separate from the header map because manifests and segments do not go through
 * our [Fetch] on any native engine — the engine downloads them itself, and on
 * several of them the only thing attachable is one header computed per URL.
 *
 * Null means send nothing for this URL, rather than send an empty header. Some
 * CDNs reject an empty Authorization outright, so the two are not the same
 * request.
 */
public fun interface AuthHeaderProvider {
    public fun authorizationFor(url: String): String?
}
