// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The operating system's own gatekeeper for who is allowed to make sound.
//
// Android calls this audio focus; the concept exists in some form everywhere
// a device plays sound from more than one app at once. A platform without one
// — a JVM desktop app with no OS-level arbitration — answers every request
// with GRANTED and delivers no change afterwards, which is an honest
// description of what that platform actually does rather than a stub
// pretending otherwise.
public interface AudioFocusPort {

    // Ask the OS for permission to play. [onChange] fires later, on whatever
    // thread the platform delivers it on, for both directions — Android's own
    // AudioManager.OnAudioFocusChangeListener is one callback for loss AND
    // regain, and a port with two callbacks here would be inventing an
    // asymmetry the platform does not have.
    public fun request(onChange: (FocusChange) -> Unit): FocusRequestResult

    // Give it back. Called on a permanent loss (the OS already knows) and on
    // a deliberate stop, so the platform's own bookkeeping — Android's
    // AudioManager entry, in particular — does not outlive the playback it
    // was for.
    public fun abandon()
}

// What changed about this app's standing to make sound.
public sealed interface FocusChange {

    public data class Lost(val kind: FocusLossKind) : FocusChange

    // The OS is handing it back after a transient loss. Never follows a
    // PERMANENT loss — that one is not coming back on its own — so a caller
    // does not need to ask which kind was lost before deciding to resume.
    public data object Gained : FocusChange
}

// What kind of loss this is, because the three answers are different actions.
public enum class FocusLossKind {

    // Something else needs the speaker briefly — a notification sound, a
    // short prompt — and will give it back. The right answer is silence or a
    // quieter answer, not abandoning the session.
    TRANSIENT,

    // The same as [TRANSIENT], except the OS is telling this app it may keep
    // making sound quietly instead of stopping outright — a turn-by-turn
    // direction over music, not a call over it.
    TRANSIENT_CAN_DUCK,

    // Another app has taken over for good — a different player started, the
    // user picked something else. This is not coming back on its own.
    PERMANENT,
}

public enum class FocusRequestResult {
    GRANTED,
    DENIED,
}

// The platform that grants everything and never interrupts, for a target with
// no such gatekeeper of its own.
//
// A real object rather than null for the same reason [UnsupportedExternalPlayback]
// is: every caller holds a valid port and every call is safe, on the one
// platform where the answer to all of it is "nothing here would ever say no".
public object AlwaysGrantedAudioFocus : AudioFocusPort {
    override fun request(onChange: (FocusChange) -> Unit): FocusRequestResult = FocusRequestResult.GRANTED
    override fun abandon(): Unit = Unit
}

// The gatekeeper this platform actually has.
//
// Mirrors the defaultX() convention the other ports use: a consumer that had
// to name a platform type would need one import per target in code that is
// meant to be shared.
public expect fun defaultAudioFocusPort(): AudioFocusPort
