// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

// The real gatekeeper, built from the context the library was installed with.
//
// Falls back to granting everything rather than throwing when no context has
// been installed — the same choice SystemTransport.android.kt makes for the
// same reason: a host JVM test or a process using core without the Android
// initializer is not a reason for a player to refuse to start.
public actual fun defaultAudioFocusPort(): AudioFocusPort =
    if (PlatformEnvironment.isInstalled()) {
        AudioManagerFocusPort(PlatformEnvironment.requireContext().androidContext)
    } else {
        AlwaysGrantedAudioFocus
    }

// One AudioFocusRequest per grant, rebuilt on every [request] rather than kept
// across losses.
//
// AudioFocusRequest.Builder wants the listener at construction, and the
// listener this class hands it captures the specific onChange this request
// was made for — so a second request from the same port after a loss gets a
// fresh request built around the caller's current callback rather than one
// still closing over whichever listener happened to be active when the
// object was first built.
public class AudioManagerFocusPort(context: Context) : AudioFocusPort {

    private val manager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var current: AudioFocusRequest? = null

    override fun request(onChange: (FocusChange) -> Unit): FocusRequestResult {
        // Already holding a request — every resume (including the one after
        // an ordinary seek, AudioFocusPlugin.Play calls this unconditionally)
        // otherwise built and issued a BRAND NEW AudioFocusRequest while the
        // previous one was still active. Android fires AUDIOFOCUS_LOSS on the
        // OLD request's listener when a new one from the SAME app supersedes
        // it — and that listener is this port's own onFocusChange, the same
        // function regardless of which request triggered it — so every
        // resume paused itself via a self-inflicted spurious loss a few tens
        // of milliseconds later, then re-granted, then the arbiter resumed:
        // a flap the viewer read as "seeking left it paused" or "focus keeps
        // breaking." Confirmed live, real device, 2026-08-12 (trace showed
        // Pause source=audio-focus firing ~40ms after every Play, on a
        // device with no other app requesting anything). No-op re-grant is
        // exactly what a caller that already holds focus wants.
        if (current != null) return FocusRequestResult.GRANTED

        val attributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val built: AudioFocusRequest = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> onChange(mapFocusChange(change)) }
            .build()

        val outcome: Int = manager.requestAudioFocus(built)
        // Only remembered once actually granted. A DENIED request delivers no
        // focus-change callback at all — nothing will ever call [abandon] for
        // it — so recording it here the same as a grant would make the guard
        // above believe this port holds focus forever after one denial (a
        // call briefly taking exclusive focus, for one), short-circuiting
        // every later [request] to a false GRANTED without ever asking the
        // OS again for the rest of the session.
        if (outcome == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) current = built
        return if (outcome == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            FocusRequestResult.GRANTED
        } else {
            FocusRequestResult.DENIED
        }
    }

    override fun abandon() {
        val built: AudioFocusRequest = current ?: return
        current = null
        manager.abandonAudioFocusRequest(built)
    }
}

// Android's own vocabulary onto this library's. AUDIOFOCUS_LOSS_TRANSIENT and
// its duck-capable sibling map onto FocusLossKind's own two transient cases
// directly — the OS already drew the distinction this library needs, and a
// third mapping in between would only be a place for the two to drift.
private fun mapFocusChange(change: Int): FocusChange = when (change) {
    AudioManager.AUDIOFOCUS_GAIN -> FocusChange.Gained
    AudioManager.AUDIOFOCUS_LOSS -> FocusChange.Lost(FocusLossKind.PERMANENT)
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.Lost(FocusLossKind.TRANSIENT)
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.Lost(FocusLossKind.TRANSIENT_CAN_DUCK)
    // Everything else — AUDIOFOCUS_REQUEST_FAILED and any future constant
    // this library does not yet know about — is treated as a permanent loss
    // rather than ignored. A viewer hearing nothing is a bug; a viewer hearing
    // a pause they did not expect is a smaller one to have caused by an
    // unmapped value.
    else -> FocusChange.Lost(FocusLossKind.PERMANENT)
}
