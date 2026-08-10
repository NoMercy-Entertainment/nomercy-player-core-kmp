// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.fakes

import tv.nomercy.player.core.ports.AudioFocusPort
import tv.nomercy.player.core.ports.FocusChange
import tv.nomercy.player.core.ports.FocusRequestResult

// A gatekeeper a test drives by hand, rather than one an OS drives on its own
// schedule. [simulate] is the only way a change ever arrives, which is what
// lets a test say "the OS just took focus away" as one line.
class FakeAudioFocusPort(private val grant: FocusRequestResult = FocusRequestResult.GRANTED) : AudioFocusPort {

    var requested: Boolean = false
        private set

    var abandoned: Boolean = false
        private set

    private var listener: ((FocusChange) -> Unit)? = null

    override fun request(onChange: (FocusChange) -> Unit): FocusRequestResult {
        requested = true
        abandoned = false
        listener = onChange
        return grant
    }

    override fun abandon() {
        abandoned = true
        listener = null
    }

    fun simulate(change: FocusChange) {
        listener?.invoke(change)
    }
}
