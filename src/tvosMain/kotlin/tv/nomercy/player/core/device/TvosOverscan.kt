// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext

// The margin tvOS lays its own interfaces out against.
//
// Apple both knows what the panel crops and refuses to say, and instead builds
// the allowance into the layout margins every system template uses. Matching
// that figure is what makes a player look like it belongs on the same screen as
// everything else, rather than being merely safe.
public actual fun platformOverscan(context: PlatformContext): SafeAreaInsets = SafeAreaInsets(
    left = TVOS_LAYOUT_MARGIN,
    top = TVOS_LAYOUT_MARGIN,
    right = TVOS_LAYOUT_MARGIN,
    bottom = TVOS_LAYOUT_MARGIN,
)

// Sixty points, which is what the platform uses for its own full-screen
// templates.
private const val TVOS_LAYOUT_MARGIN = 60f
