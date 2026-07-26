// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// What kind of thing the player is running on.
//
// Three, not a taxonomy. The player only ever asks this to answer questions it
// actually has: does the chrome need ten-foot spacing and D-pad focus, should
// quality prefer battery over smoothness, is there a keyboard. A tablet and a
// phone answer all three the same way, so they are one value.
public enum class FormFactor {
    TV,
    MOBILE,
    DESKTOP,
}

public enum class OperatingSystem {
    ANDROID,
    IOS,
    TVOS,
    MACOS,
    WINDOWS,
    LINUX,
    UNKNOWN,
}

// The device, as the platform reports it.
//
// The web has to guess this from a user-agent string that lies, which is why
// its detection is a list of regexes and a comment apologising for them. Native
// does not: every platform here can be asked directly, and the answer is the
// device's rather than a pattern that matched a substring of its name.
public data class Device(
    val formFactor: FormFactor,
    val os: OperatingSystem,
) {
    public val isTv: Boolean get() = formFactor == FormFactor.TV

    public val isMobile: Boolean get() = formFactor == FormFactor.MOBILE

    public val isDesktop: Boolean get() = formFactor == FormFactor.DESKTOP

    // Whether to spend less power at the cost of smoothness. True on the two
    // form factors that are either on a battery or on a decoder built to run
    // cool for hours; false on desktop, where neither is the binding constraint.
    public val prefersPowerEfficiency: Boolean get() = isTv || isMobile
}

// Asked once per platform. Nothing here changes while the process runs — a
// phone does not become a television — so the answer is computed on first use
// and kept.
public expect fun currentDevice(): Device
