// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.plugin.fakes.FakePluginHost
import tv.nomercy.player.core.plugin.fakes.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val UNREACHABLE = "plugin:radio/unreachable"
private const val SLOW = "plugin:radio/slow"
private const val COSMETIC = "plugin:radio/cosmetic"

private class RadioPlugin(
    override val onError: Map<String, PluginRecoveryAction> = emptyMap(),
    private val implementBodies: Boolean = true,
) : Plugin<Unit>() {

    companion object Manifest : PluginManifest {
        override val id: String = "radio"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    var retries: Int = 0
    var fallbacks: Int = 0

    override fun retryLastOperation() {
        if (implementBodies) retries += 1 else super.retryLastOperation()
    }

    override fun activateFallback() {
        if (implementBodies) fallbacks += 1 else super.activateFallback()
    }

    fun fail(code: String) = report(code, "the station did not answer")
}

// A plugin that fails and does something about it.
//
// Without this the recovery map was declared on the reference and absent here,
// so a native plugin that failed kept failing the same way on every event that
// reached it.
class PluginRecoveryTest {

    private fun wire(plugin: Plugin<Unit>, host: FakePluginHost, scope: CoroutineScope) {
        plugin.initialize(host, null, LifecycleRegistry(scope))
    }

    @Test
    fun aCodeMappedToDisableTurnsThePluginOffAfterReportingWhy() = runTest {
        val host = FakePluginHost()
        val plugin = RadioPlugin(onError = mapOf(UNREACHABLE to PluginRecoveryAction.DISABLE))
        wire(plugin, host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.fail(UNREACHABLE)

        assertFalse(plugin.enabled(), "the plugin carried on after saying it could not")
        // Order matters: a consumer that heard it go quiet without first hearing
        // why would have no way to find out.
        assertEquals(1, host.reported.size)
        assertEquals(UNREACHABLE, host.reported.first().code)
    }

    @Test
    fun aCodeWithNoEntryIsReportedAndNothingElse() = runTest {
        // The behaviour every plugin had before the map existed, kept.
        val host = FakePluginHost()
        val plugin = RadioPlugin(onError = mapOf(UNREACHABLE to PluginRecoveryAction.DISABLE))
        wire(plugin, host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.fail(COSMETIC)

        assertTrue(plugin.enabled(), "an unmapped code disabled the plugin")
        assertEquals(1, host.reported.size)
    }

    @Test
    fun retryOnceAndFallbackReachTheBodiesThePluginSupplied() = runTest {
        val plugin = RadioPlugin(
            onError = mapOf(
                SLOW to PluginRecoveryAction.RETRY_ONCE,
                COSMETIC to PluginRecoveryAction.FALLBACK,
            ),
        )
        wire(plugin, FakePluginHost(), CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.fail(SLOW)
        plugin.fail(COSMETIC)

        assertEquals(1, plugin.retries)
        assertEquals(1, plugin.fallbacks)
    }

    @Test
    fun ignoreIsDistinctFromHavingNoEntryEvenThoughBothDoNothing() = runTest {
        // It exists so "we looked at this and it is fine" is something an author
        // can write down.
        val host = FakePluginHost()
        val plugin = RadioPlugin(onError = mapOf(COSMETIC to PluginRecoveryAction.IGNORE))
        wire(plugin, host, CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.fail(COSMETIC)

        assertTrue(plugin.enabled())
        assertEquals(0, plugin.retries)
        assertEquals(1, host.reported.size)
    }

    @Test
    fun aDeclaredRecoveryWithNoBodySaysSoRatherThanFailingQuietly() = runTest {
        // A recovery that cannot run is a configuration mistake, and one that
        // fails silently looks exactly like one that worked.
        val logger = RecordingLogger("[nmplayer]")
        val plugin = RadioPlugin(
            onError = mapOf(SLOW to PluginRecoveryAction.RETRY_ONCE),
            implementBodies = false,
        )
        wire(plugin, FakePluginHost(rootLogger = logger), CoroutineScope(StandardTestDispatcher(testScheduler)))

        plugin.fail(SLOW)

        assertTrue(
            logger.lines.any { it.contains("retry-once") && it.contains("radio") },
            "nothing said the declared retry had no body: ${logger.lines}",
        )
    }
}
