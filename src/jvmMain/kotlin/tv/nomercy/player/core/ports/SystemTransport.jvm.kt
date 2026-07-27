// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The desktop's own transport, whichever desktop this is.
//
// One entry point choosing between three unrelated integrations, and today
// choosing none of them: Windows wants SystemMediaTransportControls through
// WinRT, Linux an MPRIS object on D-Bus, macOS the MediaPlayer framework iOS
// already uses from Kotlin/Native. Each is a native binding of its own and each
// lands in its own change.
//
// Until then a desktop player plays and does not appear on the system's own
// controls — which is what it does today, said here rather than left to be
// discovered. The dispatcher exists ahead of them because the choice is the part
// that has to be right on three operating systems while any test runs on one.
public actual fun defaultSystemTransport(): SystemTransport =
    desktopTransportFor(DesktopFamily.of(System.getProperty("os.name").orEmpty())) { family ->
        when (family) {
            // Each becomes a constructor call as it lands. Named rather than
            // collapsed into one branch, so what is missing is visible from the
            // place that will use it.
            DesktopFamily.WINDOWS -> null
            DesktopFamily.LINUX -> null
            DesktopFamily.MAC -> null
            DesktopFamily.UNKNOWN -> null
        }
    }
