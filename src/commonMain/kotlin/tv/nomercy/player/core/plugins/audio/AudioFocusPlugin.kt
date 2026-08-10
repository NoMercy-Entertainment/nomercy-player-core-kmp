// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.nomercy.player.core.controllers.ComposedPlayer
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.PlaySource
import tv.nomercy.player.core.player.ActionOptions
import tv.nomercy.player.core.player.ActionSource
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.core.ports.AudioFocusPort
import tv.nomercy.player.core.ports.FocusChange
import tv.nomercy.player.core.ports.FocusLossKind
import tv.nomercy.player.core.ports.defaultAudioFocusPort

// Where [AudioFocusArbiter]'s decisions meet a real transport and a real OS.
//
// The same split as [tv.nomercy.player.core.plugin.MediaSessionPlugin] and
// [tv.nomercy.player.core.ports.SystemTransport]: the rules live in a class
// with no platform underneath it, and this is the seam that calls in from one
// side (the OS, through [AudioFocusPort]) and out the other (the player and a
// volume setter).
//
// [pause] and [resume] rather than a [tv.nomercy.player.core.plugin.TransportCommands]
// — that seam has no way to tag the call it makes, and this plugin needs to
// tell its own pause and resume apart from the viewer's the moment they land
// back on CoreEvents.Pause/Play, not a moment later. [audioFocusPlugin] below
// is the sanctioned way to build one: it tags both calls with
// [ActionSource.AUDIO_FOCUS] so nothing here has to guess.
public open class AudioFocusPlugin(
    private val pause: () -> Unit,
    private val resume: () -> Unit,

    // Ducking needs a gain, not a transport verb — TransportCommands has no
    // volume, deliberately: a lock screen has never been able to change it,
    // and giving this plugin the one thing lock screens cannot touch is worse
    // than one extra constructor parameter.
    private val duck: (Float) -> Unit = {},
    private val openPort: () -> AudioFocusPort = ::defaultAudioFocusPort,
    private val arbiter: AudioFocusArbiter = AudioFocusArbiter(),
) : Plugin<Unit>() {

    public companion object Manifest : PluginManifest {
        override val id: String = "audio-focus"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    private var port: AudioFocusPort? = null

    override fun use() {
        val opened: AudioFocusPort = openPort()
        port = opened

        on(CoreEvents.Play) { source: PlaySource ->
            // Claimed on every play, self-initiated resumes included: a grant
            // that hands this player the speaker back makes it the process's
            // active sound-maker again, and something else may have started
            // in the meantime. See ProcessPlaybackOwner for why claiming is
            // safe to repeat rather than only being the user branch's job.
            ProcessPlaybackOwner.claim(pause)

            if (source.source == ActionSource.AUDIO_FOCUS) return@on
            arbiter.onUserResumed()
            opened.request(::onFocusChange)
        }

        on(CoreEvents.Pause) { source: PlaySource ->
            if (source.source == ActionSource.AUDIO_FOCUS) return@on
            arbiter.onUserPaused()
        }

        // A deliberate stop releases both focus and process ownership outright
        // rather than waiting for a loss that may never come — nothing else is
        // about to ask for either if this app is the only thing that was using
        // the speaker.
        on(CoreEvents.Stop) {
            opened.abandon()
            ProcessPlaybackOwner.release(pause)
        }
        on(CoreEvents.Ended) { opened.abandon() }
    }

    override fun dispose() {
        port?.abandon()
        port = null
        ProcessPlaybackOwner.release(pause)
    }

    // Called by whichever platform component detected a headphone or
    // Bluetooth disconnect — Android's ACTION_AUDIO_BECOMING_NOISY broadcast,
    // for the one platform that has one. Not routed through [AudioFocusPort]:
    // becoming-noisy is a route change, not a grant or a loss, and the two
    // ports would have nothing in common beyond both ending in a pause.
    public fun handleBecomingNoisy() {
        apply(arbiter.onBecomingNoisy())
    }

    private fun onFocusChange(change: FocusChange) {
        val action: FocusAction = when (change) {
            is FocusChange.Lost -> arbiter.onFocusLost(change.kind).also {
                if (change.kind == FocusLossKind.PERMANENT) port?.abandon()
            }
            FocusChange.Gained -> arbiter.onFocusGained()
        }
        apply(action)
    }

    private fun apply(action: FocusAction) {
        when (action) {
            FocusAction.None -> Unit
            FocusAction.Pause -> pause()
            FocusAction.Resume -> resume()
            is FocusAction.SetDuckGain -> duck(action.gain)
        }
    }
}

// The sanctioned way to build one. Tags both calls with [ActionSource.AUDIO_FOCUS]
// itself, over the player's own [ComposedPlayer.pause]/[ComposedPlayer.play] —
// the same launch-on-scope shape [tv.nomercy.player.core.plugin.PlayerTransportCommands]
// uses, for the same threading reason: a focus callback arrives on whatever
// thread the OS felt like, and the player's surface suspends.
//
// A consumer building [AudioFocusPlugin] directly and tagging its own pause
// and resume works too — this exists because every other plugin factory in
// this position (connectMusic, for one) does the tagging so a caller cannot
// get it wrong, and this is the one plugin here where getting it wrong is
// silent: a focus grant that quietly never resumes anything.
public fun audioFocusPlugin(
    player: ComposedPlayer,
    scope: CoroutineScope,
    duck: (Float) -> Unit = {},
    openPort: () -> AudioFocusPort = ::defaultAudioFocusPort,
    arbiter: AudioFocusArbiter = AudioFocusArbiter(),
): AudioFocusPlugin {
    val options = ActionOptions(source = ActionSource.AUDIO_FOCUS)
    return AudioFocusPlugin(
        pause = { scope.launch { player.pause(options) } },
        resume = { scope.launch { player.play(options) } },
        duck = duck,
        openPort = openPort,
        arbiter = arbiter,
    )
}
