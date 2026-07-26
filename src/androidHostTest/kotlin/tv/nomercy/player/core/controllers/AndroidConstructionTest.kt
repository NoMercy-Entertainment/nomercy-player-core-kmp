// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.player.NetworkState
import tv.nomercy.player.core.player.VisibilityState
import tv.nomercy.player.core.ports.PlatformEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// Building a player on Android before anything has been installed.
//
// This is the case that broke: the platform default reached for
// PlatformEnvironment.requireContext(), which throws until a host installs one,
// so every player constructed in a test, a headless tool, or a composable that
// runs before the app's initialiser threw on construction.
//
// Android host tests are where it shows, because they are the only place that
// runs the Android actuals with nothing installed — which is also the situation
// an app is in for the first moments of its life.
class AndroidConstructionTest {

    @Test
    fun aPlayerCanBeBuiltBeforeAnyPlatformContextIsInstalled() {
        assertFalse(
            PlatformEnvironment.isInstalled(),
            "something installed a context, so this no longer exercises the case it exists for",
        )

        val player = ComposedPlayer(backend = FakeMediaBackend())

        assertEquals(NetworkState.ONLINE, player.networkState())
        assertEquals(VisibilityState.VISIBLE, player.visibilityState())
    }
}
