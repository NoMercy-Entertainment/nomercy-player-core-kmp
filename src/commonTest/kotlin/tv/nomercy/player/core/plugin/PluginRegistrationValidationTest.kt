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
import tv.nomercy.player.core.errors.ErrorCode
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.plugin.fakes.FakePluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class BasePlugin : Plugin<Unit>() {
    companion object Manifest : PluginManifest {
        override val id: String = "base"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest
}

private class SecondBase : Plugin<Unit>() {
    override val manifest: PluginManifest = object : PluginManifest {
        override val id: String = "base"
        override val version: String = "9.9.9"
    }
}

private class Replacer : Plugin<Unit>() {
    override val manifest: PluginManifest = object : PluginManifest {
        override val id: String = "replacer"
        override val version: String = "1.0.0"
        override val replaces: List<String> = listOf("base")
    }
}

private class NeedsBase(minVersion: String? = null, optional: Boolean = false) : Plugin<Unit>() {
    override val manifest: PluginManifest = object : PluginManifest {
        override val id: String = "needs-base"
        override val version: String = "1.0.0"
        override val requires: List<Requirement> =
            listOf(Requirement(BasePlugin.Manifest, optional = optional, minVersion = minVersion))
    }
}

private class NeedsFutureCore : Plugin<Unit>() {
    override val manifest: PluginManifest = object : PluginManifest {
        override val id: String = "future"
        override val version: String = "1.0.0"
        override val minCoreVersion: String = "3.0.0"
    }
}

class PluginRegistrationValidationTest {

    private fun registry(scope: CoroutineScope, core: String = "2.0.0") =
        PluginRegistry(FakePluginHost(), coreVersion = core, scope = scope)

    @Test
    fun aSecondPluginWithTheSameIdIsRejected() = runTest {
        val registry = registry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(BasePlugin())

        val error = assertFailsWith<PlayerError> { registry.register(SecondBase()) }

        assertEquals(PluginErrorCodes.DUPLICATE_ID, error.code)
        assertEquals("base", error.context["id"])
    }

    @Test
    fun declaringReplacesSwapsThePeerOutInstead() = runTest {
        val registry = registry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(BasePlugin())

        registry.register(Replacer())

        assertNull(registry.getById("base"))
        assertNotNull(registry.getById("replacer"))
    }

    @Test
    fun aMissingRequiredDependencyIsRejectedBeforeAnyPluginCodeRuns() = runTest {
        val registry = registry(CoroutineScope(StandardTestDispatcher(testScheduler)))

        val error = assertFailsWith<PlayerError> { registry.register(NeedsBase()) }

        assertEquals(PluginErrorCodes.MISSING_DEP, error.code)
        assertNull(registry.getById("needs-base"))
    }

    @Test
    fun aMissingOptionalDependencyLetsThePluginLoadAnyway() = runTest {
        val registry = registry(CoroutineScope(StandardTestDispatcher(testScheduler)))

        registry.register(NeedsBase(optional = true))

        assertNotNull(registry.getById("needs-base"))
    }

    @Test
    fun aDependencyBelowTheRequiredVersionIsRejected() = runTest {
        val registry = registry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(BasePlugin())

        val error = assertFailsWith<PlayerError> { registry.register(NeedsBase(minVersion = "2.0.0")) }

        assertEquals(PluginErrorCodes.VERSION_MISMATCH, error.code)
        assertEquals("1.0.0", error.context["installedVersion"])
        assertEquals("2.0.0", error.context["requiredVersion"])
    }

    @Test
    fun aSatisfiedVersionRequirementRegisters() = runTest {
        val registry = registry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(BasePlugin())

        registry.register(NeedsBase(minVersion = "1.0.0"))

        assertNotNull(registry.getById("needs-base"))
    }

    @Test
    fun aPluginThatNeedsANewerCoreIsRejected() = runTest {
        val registry = registry(CoroutineScope(StandardTestDispatcher(testScheduler)), core = "2.0.0")

        val error = assertFailsWith<PlayerError> { registry.register(NeedsFutureCore()) }

        assertEquals(PluginErrorCodes.INCOMPATIBLE_CORE_VERSION, error.code)
    }

    @Test
    fun removingAPluginSomethingElseRequiresIsRefused() = runTest {
        val registry = registry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(BasePlugin())
        registry.register(NeedsBase())

        val error = assertFailsWith<PlayerError> { registry.remove("base") }

        // The dependent is already running and would keep calling into a
        // disposed plugin.
        assertEquals(PluginErrorCodes.HAS_DEPENDENTS, error.code)
        assertNotNull(registry.getById("base"))
    }

    @Test
    fun anOptionalDependentDoesNotPinItsDependency() = runTest {
        val registry = registry(CoroutineScope(StandardTestDispatcher(testScheduler)))
        registry.register(BasePlugin())
        registry.register(NeedsBase(optional = true))

        registry.remove("base")

        assertNull(registry.getById("base"))
    }

    @Test
    fun everyRegistrationCodeParsesAsAnErrorCode() = runTest {
        val codes = listOf(
            PluginErrorCodes.MISSING_DEP,
            PluginErrorCodes.DUPLICATE_ID,
            PluginErrorCodes.VERSION_MISMATCH,
            PluginErrorCodes.INCOMPATIBLE_CORE_VERSION,
            PluginErrorCodes.DISPOSE_FAILED,
            PluginErrorCodes.HAS_DEPENDENTS,
            PluginErrorCodes.INIT_TIMEOUT,
            PluginErrorCodes.STATE_UNINITIALIZED,
            PluginErrorCodes.USE_AFTER_DISPOSE,
        )

        codes.forEach { ErrorCode.parse(it) }

        // Eight plugin faults; use-after-dispose is a lifecycle fault and sits
        // under a different namespace on purpose.
        assertEquals(8, codes.count { ErrorCode.parse(it).category == "plugin" })
        assertEquals(1, codes.count { ErrorCode.parse(it).category == "lifecycle" })
    }
}
