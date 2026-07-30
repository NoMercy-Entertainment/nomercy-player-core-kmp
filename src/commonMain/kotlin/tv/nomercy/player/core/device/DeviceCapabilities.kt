// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import tv.nomercy.player.core.ports.PlatformContext

// What the thing in front of the viewer can actually do.
//
// One contract, asked once, instead of the question being re-answered wherever
// it comes up. The web player decides this by reading the user agent string and
// the Android client merges three separate signals, and those two disagree about
// the same television — which is how a set-top box ends up with touch zones
// drawn for a finger that is not there.
public interface DeviceCapabilities {

    public val formFactor: FormFactor

    // A directional pad: a remote, or a gamepad. Not the same question as the
    // form factor, because a phone with a controller attached has one and a
    // set-top box someone drives from a phone app does not.
    public val hasDpad: Boolean

    public val hasTouch: Boolean

    public val hasPointer: Boolean

    // Whether the volume keys reach this application at all. On a television
    // they usually do not: the remote's volume talks to the panel or the
    // receiver, and an application that drew a volume bar in response to a key
    // it never receives has drawn a control that does nothing.
    public val hasHardwareVolumeKeys: Boolean

    /**
     * Whether the attached screen can actually show HDR.
     *
     * The one input hdrDecision needs and had nowhere to get. Absent it, HDR played
     * on an SDR panel unchallenged: nothing capped the ladder and nothing converted,
     * and a viewer with good bandwidth got the WORST picture because adaptation
     * climbs into the HDR rungs on its own.
     *
     * Conservative when unknown. SDR on an HDR panel is a correct-looking picture one
     * rung lower; HDR on an SDR panel is the washed-out one that was reported.
     */
    public val hasHdrDisplay: Boolean

    // Whether chrome should be driven by moving a highlight rather than by
    // touching where things are.
    //
    // Derived here rather than answered per platform, so four targets cannot
    // give four answers to one question. A television is the case that needs it:
    // there is no pointer, so every control has to be reachable by direction.
    public val prefersFocusNavigation: Boolean
        get() = formFactor == FormFactor.Tv
}

public enum class FormFactor { Phone, Tablet, Tv, Desktop }

// What this device is, asked of the platform it is running on.
public expect fun platformDeviceCapabilities(context: PlatformContext): DeviceCapabilities
