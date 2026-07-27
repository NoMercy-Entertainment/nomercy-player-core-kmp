// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Somewhere else the operating system can send this playback.
//
// On iOS that is AirPlay: the system moves the audio or the picture to a speaker
// or a television, and the player carries on believing it is playing because as
// far as it is concerned it is. Nothing else this library does looks like that.
// Casting is this device driving another one; this is the platform taking the
// output away and handing it back.
public interface ExternalPlayback {

    // True only where the platform has a route sender at all. Chrome reads this
    // to decide whether an AirPlay button exists, which is the difference
    // between a button that is missing and one that does nothing.
    public val isSupported: Boolean

    public val state: StateFlow<ExternalPlaybackState>

    // Whether playback may move at all. A film with a licence that forbids it
    // says no, and the platform stops offering the route rather than offering
    // one that then fails.
    public fun setAllowed(allowed: Boolean)

    // The system's own sheet, which is the only way a route can be chosen. A
    // library cannot draw this list and must not try: the platform owns both the
    // discovery and the consent.
    public fun showRoutePicker()

    public fun dispose()
}

public data class ExternalRoute(
    val id: String,
    val name: String,
    val kind: RouteKind,
)

// UNKNOWN is not an error. A route the platform names but this build does not
// recognise still plays, and drawing it as broken would be worse than drawing it
// plainly.
public enum class RouteKind { AIRPLAY, UNKNOWN }

// Unsupported is a state, not a failure.
//
// Most platforms have no route sender and never will, and that is the ordinary
// case rather than something going wrong. A chrome binds to this to hide the
// affordance; an error would make it draw a problem where there is none.
public sealed interface ExternalPlaybackState {

    public data object Unsupported : ExternalPlaybackState

    public data class Available(
        val active: Boolean,
        val activeRoute: ExternalRoute?,
    ) : ExternalPlaybackState
}

// A real object rather than a null.
//
// Every caller then holds a valid port, asks whether it is supported, and gets a
// safe answer to everything else. The alternative is a nullable field and a
// null check at each of the call sites, one of which will eventually be missed
// on the platform where it matters least and crash the one where it matters
// most.
public object UnsupportedExternalPlayback : ExternalPlayback {

    override val isSupported: Boolean = false

    override val state: StateFlow<ExternalPlaybackState> =
        MutableStateFlow(ExternalPlaybackState.Unsupported)

    override fun setAllowed(allowed: Boolean): Unit = Unit

    override fun showRoutePicker(): Unit = Unit

    override fun dispose(): Unit = Unit
}
