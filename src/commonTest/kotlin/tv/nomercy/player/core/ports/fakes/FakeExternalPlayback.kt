// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.nomercy.player.core.ports.ExternalPlayback
import tv.nomercy.player.core.ports.ExternalPlaybackState
import tv.nomercy.player.core.ports.ExternalRoute

// A platform that does have a route sender, driven by the test.
//
// The route arriving and leaving is the whole behaviour worth testing above this
// port, and on a real device it happens when somebody walks up to a speaker.
internal class FakeExternalPlayback(
    override val isSupported: Boolean = true,
) : ExternalPlayback {

    private val current = MutableStateFlow<ExternalPlaybackState>(
        if (isSupported) ExternalPlaybackState.Available(active = false, activeRoute = null)
        else ExternalPlaybackState.Unsupported,
    )

    override val state: StateFlow<ExternalPlaybackState> = current

    var allowed: Boolean = true
        private set

    var pickerShown: Int = 0
        private set

    var disposals: Int = 0
        private set

    override fun setAllowed(allowed: Boolean) {
        this.allowed = allowed
    }

    override fun showRoutePicker() {
        pickerShown += 1
    }

    override fun dispose() {
        disposals += 1
    }

    // Somebody chose a speaker, or walked out of range of one.
    fun routeChanged(route: ExternalRoute?) {
        current.value = ExternalPlaybackState.Available(active = route != null, activeRoute = route)
    }
}
