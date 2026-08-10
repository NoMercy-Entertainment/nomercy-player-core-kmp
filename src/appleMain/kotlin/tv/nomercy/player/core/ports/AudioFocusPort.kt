// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol

// AVAudioSessionInterruptionNotification, the shape both iOS and tvOS answer
// through — same observer pattern AVPlayerExternalPlayback.kt already proved
// for route changes, watching a different notification.
//
// iOS does not tell this app "you may duck" the way Android's
// AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK does — ducking on Apple platforms is a
// category option that affects how THIS app's audio ducks OTHERS, not a
// signal this app receives about how to behave when interrupted. So every
// interruption here maps to TRANSIENT, never TRANSIENT_CAN_DUCK, which is an
// honest read of what the platform actually announces rather than a case this
// port cannot really tell apart from the other.
//
// Deliberately does not touch AVAudioSession's category or activation state —
// AVPlayer already owns that when it starts. This class only listens.
@OptIn(ExperimentalForeignApi::class)
public actual fun defaultAudioFocusPort(): AudioFocusPort = AVAudioSessionFocusPort()

@OptIn(ExperimentalForeignApi::class)
public class AVAudioSessionFocusPort : AudioFocusPort {

    private var observer: NSObjectProtocol? = null

    override fun request(onChange: (FocusChange) -> Unit): FocusRequestResult {
        abandon()

        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { notification: NSNotification? -> notification?.let(::mapInterruption)?.let(onChange) }

        // The session itself decides whether this app may play at all — a
        // Bluetooth route mid-handshake, a higher-priority app already
        // active — and there is no synchronous way to ask it in advance the
        // way Android's requestAudioFocus() answers immediately. GRANTED is
        // the honest default: this app proceeds and finds out about a denial
        // the same way it finds out about a later loss, through the
        // interruption notification this call just subscribed to.
        return FocusRequestResult.GRANTED
    }

    override fun abandon() {
        val current: NSObjectProtocol = observer ?: return
        observer = null
        NSNotificationCenter.defaultCenter.removeObserver(current)
    }
}

// The notification's userInfo dictionary onto this library's FocusChange.
// Missing keys resolve to "began" and "no resume" — the two answers that
// leave a viewer with a paused player and a wrong assumption never leaves
// them with sound playing when the system disagrees.
// Null is a real answer, not a parsing failure: the interruption ended but
// the system is not inviting this app to resume on its own — a Siri prompt
// that stays up, a call the viewer is still on. Nothing needs telling; the
// pause this class already reported on the way in stands until the OS says
// otherwise, and an arbiter that only resumes a loss it caused has nothing to
// do with a gain that never arrives.
@OptIn(ExperimentalForeignApi::class)
private fun mapInterruption(notification: NSNotification): FocusChange? {
    val userInfo: Map<Any?, *>? = notification.userInfo
    val type: ULong =
        (userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
            ?: AVAudioSessionInterruptionTypeBegan

    if (type == AVAudioSessionInterruptionTypeBegan) {
        return FocusChange.Lost(FocusLossKind.TRANSIENT)
    }

    val options: ULong = (userInfo?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)?.unsignedLongValue ?: 0uL
    val shouldResume: Boolean = (options and AVAudioSessionInterruptionOptionShouldResume) != 0uL

    return if (shouldResume) FocusChange.Gained else null
}
