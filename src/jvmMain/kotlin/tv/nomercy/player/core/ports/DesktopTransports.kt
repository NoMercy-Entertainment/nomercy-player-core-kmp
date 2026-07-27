// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Which desktop this is, as far as a system transport is concerned.
//
// Three operating systems with three unrelated answers: Windows has
// SystemMediaTransportControls, Linux has an MPRIS object on D-Bus, macOS has
// the same MediaPlayer framework iOS does. Nothing is shared between them but
// the question.
public enum class DesktopFamily {
    WINDOWS,
    LINUX,
    MAC,
    UNKNOWN,
    ;

    public companion object {

        // From the name the JVM reports. Matched loosely on purpose: the exact
        // string differs across JVMs and Windows versions, and the family is all
        // that is being asked.
        public fun of(osName: String): DesktopFamily {
            val name: String = osName.lowercase()
            return when {
                // "windows", not "win". Darwin contains win, so the short match
                // sent every Mac down the Windows branch — which would have
                // meant loading a WinRT binding on macOS. A test naming the
                // operating systems rather than asking the machine is what
                // found it; on this developer's box the answer was right.
                name.contains("windows") -> WINDOWS
                name.contains("mac") || name.contains("darwin") -> MAC
                name.contains("nux") || name.contains("nix") || name.contains("bsd") -> LINUX
                else -> UNKNOWN
            }
        }
    }
}

// The transport for a given desktop, or none where there is not one yet.
//
// Separated from the factory so the choice can be tested without the machine
// running the test being the answer. A CI runner is one operating system and
// this has to be right on three.
internal fun desktopTransportFor(
    family: DesktopFamily,
    build: (DesktopFamily) -> SystemTransport?,
): SystemTransport = build(family) ?: NoSystemTransport()
