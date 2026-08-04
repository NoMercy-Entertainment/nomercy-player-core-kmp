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
import kotlin.test.assertTrue
import tv.nomercy.player.core.plugins.canvas.CanvasOptions
import tv.nomercy.player.core.plugins.canvas.CanvasPlugin
import tv.nomercy.player.core.plugins.canvas.CompositeMode
import tv.nomercy.player.core.plugins.message.MessageOptions
import tv.nomercy.player.core.plugins.message.MessagePlugin

// A declared field has to move the plugin, not just describe it.
//
// The testbed generates its options editor from this list rather than
// hand-listing controls per plugin, which is the whole reason it exists — a
// hand-listed editor drifts from the plugin the first time an option is added
// and nothing reports it. So the assertion is on what the plugin reads
// afterwards, never on the shape of the list.
class PluginOptionFieldTest {

    @Test
    fun applyingTheFrameCapChangesWhatTheLoopWillRead() {
        val plugin = CanvasPlugin<Unit>(CanvasOptions(fps = 60))

        val fps = plugin.optionFields()
            .filterIsInstance<PluginOptionField.Number>()
            .single { field -> field.key == "fps" }

        assertEquals(60.0, fps.value, "the field should report the plugin's current cap")

        fps.apply(24.0)

        assertEquals(24, plugin.options.fps, "the plugin kept its old cap after the edit")
    }

    @Test
    fun aChoiceOffersEveryModeAndSelectsTheOneItWasGiven() {
        val plugin = CanvasPlugin<Unit>(CanvasOptions(compositeMode = CompositeMode.CLEAR))

        val mode = plugin.optionFields()
            .filterIsInstance<PluginOptionField.Choice>()
            .single { field -> field.key == "compositeMode" }

        assertEquals(
            CompositeMode.entries.map { entry -> entry.name },
            mode.choices,
            "an editor offering fewer modes than exist is one a mode cannot be reached through",
        )

        val other: CompositeMode = CompositeMode.entries.first { entry -> entry != CompositeMode.CLEAR }
        mode.apply(other.name)

        assertEquals(other, plugin.options.compositeMode)
    }

    @Test
    fun applyingADurationChangesWhatTheNextMessageWillUse() {
        val plugin = MessagePlugin(MessageOptions(durationMs = 3_000))

        val duration = plugin.optionFields()
            .filterIsInstance<PluginOptionField.Number>()
            .single { field -> field.key == "durationMs" }

        duration.apply(1_500.0)

        assertEquals(1_500L, plugin.options.durationMs)
    }

    @Test
    fun aPluginWithNothingWorthEditingDeclaresNothing() {
        // Not an oversight, and worth pinning: a host draws the row and its
        // toggle either way, so an empty list is a plugin saying it has no
        // options rather than one that forgot to describe them.
        val plugin = object : Plugin<Unit>() {
            override val manifest: PluginManifest = object : PluginManifest {
                override val id: String = "optionless"
                override val version: String = "2.0.0"
            }
        }

        assertTrue(plugin.optionFields().isEmpty())
    }
}
