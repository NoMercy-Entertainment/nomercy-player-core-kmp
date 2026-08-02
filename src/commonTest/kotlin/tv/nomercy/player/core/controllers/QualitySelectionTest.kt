// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlin.test.Test
import kotlin.test.assertEquals
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.ports.QualityLevel
import tv.nomercy.player.core.ports.QualityMode
import tv.nomercy.player.testing.FakeVideoBackend

// Picking a quality, from the menu's point of view.
//
// The menu reads qualityMode() to draw its selection and listens for
// qualityState to know it changed. Neither worked: quality(level) forwarded to
// the engine and emitted nothing, and qualityMode() read the answer back OUT of
// the engine — which on an adaptive stream is always null, because libVLC's
// demuxer picks its own representation and publishes one track. So a viewer
// chose a rung and the menu forgot on the next recomposition.
class QualitySelectionTest {

    private fun player(): ComposedPlayer {
        val backend = FakeVideoBackend()
        return ComposedPlayer(backend, video = backend)
    }

    private fun rung(): QualityLevel = QualityLevel(
        height = 1080,
        bitrate = 5_000_000,
        codec = "avc1",
        width = 1920,
        label = "1080p",
    )

    @Test
    fun choosingARungAnnouncesIt() {
        val subject = player()
        val seen: MutableList<String> = mutableListOf()
        subject.on(CoreEvents.QualityState) { seen += it.state }

        subject.quality(rung())

        assertEquals(listOf("manual"), seen)
    }

    @Test
    fun theChoiceSurvivesBeingReadBack() {
        // The half that made the menu forget. An engine that adapts reports no
        // pinned level, and reading the mode back out of it turned every manual
        // pick into AUTO one recomposition later.
        val subject = player()

        subject.quality(rung())

        assertEquals(QualityMode.MANUAL, subject.qualityMode())
    }

    @Test
    fun goingBackToAutomaticAnnouncesThatToo() {
        val subject = player()
        val seen: MutableList<String> = mutableListOf()
        subject.quality(rung())
        subject.on(CoreEvents.QualityState) { seen += it.state }

        subject.quality(null)

        assertEquals(listOf("auto"), seen)
        assertEquals(QualityMode.AUTO, subject.qualityMode())
    }
}
