// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// One HTTP request, as much of it as a plugin is allowed to decide. The token,
// the refresh-and-retry and the base URL are the host's business, which is why
// there is no auth header here for a plugin to get wrong.
public data class FetchOptions(
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

public data class FetchResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)
