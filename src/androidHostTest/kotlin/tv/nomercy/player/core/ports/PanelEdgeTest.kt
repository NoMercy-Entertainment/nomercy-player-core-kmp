// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals

// A 4K box often draws its UI at 1080p, so the UI surface is not the panel.
class PanelEdgeTest {

    @Test
    fun aPanelWiderThanTheUiSurfaceIsMeasuredByThePanel() {
        assertEquals(3840, panelEdgePx(uiWidth = 1920, uiHeight = 1080, panelWidth = 3840, panelHeight = 2160))
    }

    @Test
    fun aMissingPanelModeFallsBackToTheUiSurface() {
        assertEquals(1920, panelEdgePx(uiWidth = 1920, uiHeight = 1080, panelWidth = 0, panelHeight = 0))
    }

    @Test
    fun aPortraitPhoneUsesItsLongerEdge() {
        assertEquals(2400, panelEdgePx(uiWidth = 1080, uiHeight = 2400, panelWidth = 1080, panelHeight = 2400))
    }
}
