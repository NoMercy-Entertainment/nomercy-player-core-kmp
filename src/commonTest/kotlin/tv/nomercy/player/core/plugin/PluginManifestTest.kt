// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginManifestTest {

    private object MinimalManifest : PluginManifest {
        override val id: String = "minimal"
        override val version: String = "1.0.0"
    }

    private object ScrubberContribution : ChromeContribution {
        override val slot: ChromeSlot = ChromeSlot.Scrubber
        override val replaces: Boolean = true
    }

    private object ChromeManifest : PluginManifest {
        override val id: String = "chrome"
        override val version: String = "1.0.0"
        override val contributions: List<ChromeContribution> = listOf(ScrubberContribution)
    }

    @Test
    fun aManifestNeedsOnlyAnIdAndAVersion() {
        // Everything else defaults, so the smallest possible plugin declares two
        // lines rather than nine.
        assertTrue(MinimalManifest.requires.isEmpty())
        assertTrue(MinimalManifest.replaces.isEmpty())
        assertTrue(MinimalManifest.contributions.isEmpty())
        assertEquals(0, MinimalManifest.priority)
        assertEquals(null, MinimalManifest.minCoreVersion)
        assertEquals(null, MinimalManifest.translations)
    }

    @Test
    fun aPluginEventKeyIsNamespacedByTheManifestId() {
        val key = pluginEventKey<Int>(MinimalManifest, "ping")

        assertEquals("plugin:minimal:ping", key.name)
    }

    @Test
    fun bothSidesOfACrossPluginEventDeriveTheSameName() {
        // The whole point: an owner emitting and a stranger listening build the
        // string the same way instead of typing it twice.
        val owner = pluginEventKey<Int>(MinimalManifest, "line")
        val subscriber = pluginEventKey<Int>(MinimalManifest, "line")

        assertEquals(owner.name, subscriber.name)
    }

    @Test
    fun aRequirementPointsAtTheOtherManifestNotItsIdString() {
        val requirement = Requirement(MinimalManifest, minVersion = "1.0.0")

        assertEquals("minimal", requirement.manifest.id)
        assertFalse(requirement.optional)
        assertEquals("1.0.0", requirement.minVersion)
    }

    @Test
    fun aContributionDeclaresItsSlotAndWhetherItTakesItOver() {
        val contribution = ChromeManifest.contributions.single()

        assertEquals(ChromeSlot.Scrubber, contribution.slot)
        assertTrue(contribution.replaces)
        assertEquals(0, contribution.order)
    }
}
