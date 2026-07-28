// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.testing

import tv.nomercy.player.core.media.PlaylistItem

// Something to play, with a url nobody will fetch.
//
// The default url is a .test domain on purpose: reserved by the RFC, resolvable
// by nothing, so a fake that accidentally reached the network fails loudly
// instead of quietly downloading somebody's homepage in a unit test.
public data class TestItem(
    override val id: String,
    override val url: String = "https://example.test/$id",
    override val title: String? = null,
) : PlaylistItem
