// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortAirPlay
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.currentRoute
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.externalPlaybackActive
import platform.AVFoundation.setAllowsExternalPlayback
import platform.AVFoundation.setUsesExternalPlaybackWhileExternalScreenIsActive
import platform.AVKit.AVRoutePickerView
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIButton
import platform.UIKit.UIControlEventTouchUpInside
import platform.darwin.NSObjectProtocol

// AirPlay, which on iOS is the system moving playback off this device.
//
// The player is not told and does not need to be: it keeps decoding and keeps
// reporting a position, and the sound comes out of a speaker in another room. So
// nothing here starts or stops anything. It grants permission, hands over the
// system's own picker, and watches for the moment the route changes.
@OptIn(ExperimentalForeignApi::class)
public class AVPlayerExternalPlayback(private val player: AVPlayer) : ExternalPlayback {

    override val isSupported: Boolean = true

    private val current: MutableStateFlow<ExternalPlaybackState> =
        MutableStateFlow(ExternalPlaybackState.Available(active = false, activeRoute = null))

    override val state: StateFlow<ExternalPlaybackState> = current

    // The system's AirPlay button, for a chrome to place where it wants it. A
    // library cannot draw the list of routes and must not try: the platform owns
    // both the discovery and the consent, and this view is the only sanctioned
    // way to ask for either.
    //
    // Left scoped to the audio session rather than bound to this player. The
    // binding is not in the Kotlin interop at all, and the session is the right
    // scope anyway for an application with one player.
    public val routePickerView: AVRoutePickerView = AVRoutePickerView()

    private var routeObserver: NSObjectProtocol? = null

    init {
        // Kept on so playback follows a screen that is already connected, rather
        // than staying on the handset while the television shows nothing.
        player.setUsesExternalPlaybackWhileExternalScreenIsActive(true)
        player.setAllowsExternalPlayback(true)

        // The route changing is the event, not a property on the player. It is
        // what the platform actually announces, it fires for every reason a
        // route can move including somebody walking out of range, and it needs
        // no observer object whose lifetime has to be managed by hand.
        routeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> republish() }

        republish()
    }

    override fun setAllowed(allowed: Boolean) {
        player.setAllowsExternalPlayback(allowed)
        republish()
    }

    // Taps the system button rather than presenting anything of ours. The sheet,
    // its contents and its dismissal all belong to the platform.
    override fun showRoutePicker() {
        val button: UIButton? = routePickerView.subviews.firstOrNull { it is UIButton } as? UIButton
        button?.sendActionsForControlEvents(UIControlEventTouchUpInside)
    }

    // Safe twice, because a chrome tearing down a screen and a backend releasing
    // its player both have a claim to call it and neither knows about the other.
    override fun dispose() {
        val observer: NSObjectProtocol = routeObserver ?: return
        routeObserver = null
        NSNotificationCenter.defaultCenter.removeObserver(observer)
    }

    private fun republish() {
        val active: Boolean = player.externalPlaybackActive
        current.value = ExternalPlaybackState.Available(
            active = active,
            activeRoute = if (active) currentRoute() else null,
        )
    }

    // Named from the audio session, which is where the platform reports what it
    // is playing through. Without a name a chrome can only say that playback
    // moved, and "playing on Kitchen" is the whole reassurance somebody wants
    // when the sound leaves their hand.
    private fun currentRoute(): ExternalRoute? {
        val output: AVAudioSessionPortDescription = AVAudioSession.sharedInstance()
            .currentRoute
            .outputs
            .filterIsInstance<AVAudioSessionPortDescription>()
            .firstOrNull()
            ?: return null

        return ExternalRoute(
            id = output.UID,
            name = output.portName,
            kind = if (output.portType == AVAudioSessionPortAirPlay) RouteKind.AIRPLAY else RouteKind.UNKNOWN,
        )
    }
}
