// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Which desktop this is, and what it gets.
//
// Tested by naming the operating system rather than by asking the machine,
// because a test runs on one and this has to be right on three. A dispatcher
// that only ever chose correctly for the developer's own laptop is the exact
// failure this is here to prevent.
class DesktopTransportDispatchTest {

    @Test
    fun everyNameAWindowsJvmReportsIsRecognised() {
        // The string differs by version and by JVM vendor, which is why the
        // match is on the family rather than on an exact name.
        assertEquals(DesktopFamily.WINDOWS, DesktopFamily.of("Windows 10"))
        assertEquals(DesktopFamily.WINDOWS, DesktopFamily.of("Windows Server 2022"))
        assertEquals(DesktopFamily.WINDOWS, DesktopFamily.of("windows 11"))
    }

    @Test
    fun macIsRecognisedByBothNamesItGoesBy() {
        assertEquals(DesktopFamily.MAC, DesktopFamily.of("Mac OS X"))
        assertEquals(DesktopFamily.MAC, DesktopFamily.of("Darwin"))
    }

    @Test
    fun theLinuxFamilyCoversMoreThanLinux() {
        // A player on FreeBSD is a real report, and MPRIS is what it has too —
        // it is a freedesktop specification rather than a Linux one.
        assertEquals(DesktopFamily.LINUX, DesktopFamily.of("Linux"))
        assertEquals(DesktopFamily.LINUX, DesktopFamily.of("FreeBSD"))
    }

    @Test
    fun darwinIsNotWindowsBecauseItContainsWin() {
        // It does, and the short match sent every Mac down the Windows branch —
        // a WinRT binding loaded on macOS. The bug survived on this developer's
        // machine because the answer there happened to be right.
        assertEquals(DesktopFamily.MAC, DesktopFamily.of("Darwin"))
    }

    @Test
    fun somethingUnrecognisedIsSaidToBeUnrecognised() {
        // Rather than guessed at. A wrong guess here is a native binding loaded
        // on an operating system that does not have it.
        assertEquals(DesktopFamily.UNKNOWN, DesktopFamily.of("Haiku"))
        assertEquals(DesktopFamily.UNKNOWN, DesktopFamily.of(""))
    }

    @Test
    fun eachFamilyIsHandedItsOwnTransport() {
        val asked: MutableList<DesktopFamily> = mutableListOf()

        for (family in DesktopFamily.entries) {
            desktopTransportFor(family) { chosen ->
                asked += chosen
                null
            }
        }

        assertEquals(DesktopFamily.entries.toList(), asked.toList(), "a family reached the wrong branch")
    }

    @Test
    fun aDesktopWithNoIntegrationYetGetsOneThatDoesNothing() {
        // Not null and not an exception. A desktop player whose system controls
        // are not built yet still plays; it simply does not appear on them.
        val transport: SystemTransport = desktopTransportFor(DesktopFamily.LINUX) { null }

        transport.setNowPlaying(NowPlaying(title = "anything"))
        transport.setPlaybackState(TransportPlaybackState.PLAYING, 0, 1.0)
        transport.release()
    }

    @Test
    fun aBuiltTransportIsTheOneHandedBack() {
        // The half that matters once any of the three lands: the dispatcher
        // must return what it built rather than falling through to the no-op.
        val built = NoSystemTransport()

        val transport: SystemTransport = desktopTransportFor(DesktopFamily.WINDOWS) { built }

        assertTrue(transport === built, "the dispatcher discarded the transport it built")
    }

    @Test
    fun thisMachineResolvesToSomethingUsable() {
        // Whatever this is running on. The factory reads the real os.name, and
        // a player must be constructible on every one of them.
        val transport: SystemTransport = defaultSystemTransport()

        transport.setPlaybackState(TransportPlaybackState.STOPPED, 0, 1.0)
    }
}
