// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// The one place player state changes. A concrete player owns a holder; a
// chrome collects its flow; nothing else may write.
//
// StateFlow conflates equal values, so an update that changes nothing emits
// nothing and a Compose or SwiftUI surface does not recompose for it. That is
// why PlayerState is a data class: equality has to be structural for the
// conflation to mean anything.
public class PlayerStateHolder(initial: PlayerState = PlayerState()) {

    private val mutable: MutableStateFlow<PlayerState> = MutableStateFlow(initial)

    public val stateFlow: StateFlow<PlayerState> = mutable.asStateFlow()

    public fun snapshot(): PlayerState = mutable.value

    // Atomic read-modify-write. A plain `mutable.value = transform(value)`
    // would drop one of two concurrent updates — a backend thread reporting
    // time while the UI thread sets volume is exactly that race.
    public fun update(transform: (PlayerState) -> PlayerState) {
        mutable.update(transform)
    }
}
