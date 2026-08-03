// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import kotlinx.coroutines.CoroutineScope
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.PlayerError
import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.player.PlayerPhase

private class Registration(
    val plugin: Plugin<*>,
    val lifecycle: LifecycleRegistry,
)

// One chrome contribution and the plugin that offers it, so a surface knows
// who to ask for the content.
public data class ContributionBinding(
    val pluginId: String,
    val contribution: ChromeContribution,
)

// Owns plugins: what may register, in what order they run, and what happens
// when one goes away.
//
// Validation runs before any of a plugin's code does, so a plugin whose
// dependency is missing never gets to half-initialise. Every rejection carries
// a coded error a consumer can switch on rather than a message they have to
// pattern-match.
public class PluginRegistry(
    private val host: PluginHost,
    private val coreVersion: String,
    private val scope: CoroutineScope,
) {
    private val registrations: MutableList<Registration> = mutableListOf()
    private var disposed: Boolean = false

    public fun plugins(): List<Plugin<*>> = registrations.map { it.plugin }

    public fun getById(id: String): Plugin<*>? = registrations.firstOrNull { it.plugin.id == id }?.plugin

    // The enabled plugins, highest priority first, registration order breaking
    // ties.
    //
    // The order is the point. A before-dispatch chain runs these in sequence,
    // and a plugin that must see an event before another one says so with its
    // priority rather than by being registered earlier — which is the consumer's
    // choice, not the author's.
    public fun enabledPlugins(): List<Plugin<*>> =
        registrations.withIndex()
            .filter { it.value.plugin.enabled() }
            .sortedWith(compareByDescending<IndexedValue<Registration>> { it.value.plugin.manifest.priority }
                .thenBy { it.index })
            .map { it.value.plugin }

    public fun isDisposed(): Boolean = disposed

    // What the registered plugins have to say about this call.
    //
    // Asked per guarded mutation rather than merged into a lookup at
    // registration, which the reference does: a merged table has to be rebuilt
    // whenever a plugin is added, removed, enabled or disabled, and the one
    // that is not rebuilt is how a removed plugin keeps advising. Walking the
    // enabled list costs a pass over a handful of plugins on a call that was
    // already dispatching an event.
    //
    // Disabled plugins say nothing. A viewer who turned a plugin off should not
    // keep getting its warnings.
    public fun advisories(
        method: String,
        phase: PlayerPhase,
        dispatchStack: List<String>,
    ): List<PluginAdvisoryNotice> =
        enabledPlugins().flatMap { plugin ->
            plugin.advisories
                .filter { it.matches(method, phase, dispatchStack) }
                .map { advisory ->
                    PluginAdvisoryNotice(
                        pluginId = plugin.id,
                        method = method,
                        // Stamped here rather than by the plugin, so an advisory
                        // cannot claim to come from a plugin that did not
                        // declare it.
                        code = "plugin:${plugin.id}/${advisory.reason}",
                        message = advisory.message,
                        severity = advisory.severity,
                    )
                }
        }

    // What a chrome should render in one region, already ordered.
    //
    // A contribution's own order decides first; manifest priority breaks ties,
    // higher first; registration order breaks what is left, so the answer is
    // stable rather than dependent on map iteration. A contribution that
    // replaces the slot sorts to the front, which is where a chrome looks to
    // decide whether to draw its own default at all.
    public fun contributions(slot: ChromeSlot): List<ContributionBinding> =
        registrations
            .flatMapIndexed { index: Int, registration: Registration ->
                registration.plugin.manifest.contributions
                    .filter { it.slot == slot }
                    .map { Triple(index, registration.plugin.manifest, it) }
            }
            .sortedWith(
                compareByDescending<Triple<Int, PluginManifest, ChromeContribution>> { it.third.replaces }
                    .thenBy { it.third.order }
                    .thenByDescending { it.second.priority }
                    .thenBy { it.first },
            )
            .map { ContributionBinding(it.second.id, it.third) }

    // Validate, wire, run use(), announce. Throws a coded PlayerError rather
    // than registering something broken.
    public fun <O : Any> register(plugin: Plugin<O>, opts: O? = null): PluginRegistry {
        val manifest: PluginManifest = plugin.manifest
        val id: String = manifest.id
        if (disposed) {
            throw registryError(
                PluginErrorCodes.USE_AFTER_DISPOSE,
                "register(\"$id\") was called after the registry was disposed.",
                mapOf("id" to id),
            )
        }

        // Swaps happen before validation so replacing a plugin does not trip
        // the duplicate-id check against the thing being replaced.
        for (replacedId in manifest.replaces) {
            if (registrations.any { it.plugin.id == replacedId }) remove(replacedId)
        }

        validate(manifest)

        val lifecycle = LifecycleRegistry(scope)
        plugin.initialize(host, opts, lifecycle)
        plugin.use()
        registrations.add(Registration(plugin, lifecycle))
        host.emit(
            EventKey<Map<String, Any?>>("plugin:installed"),
            mapOf("id" to id, "version" to manifest.version),
        )
        return this
    }

    // Removing a plugin something else requires is refused: the dependent is
    // already running and would keep calling into a disposed plugin.
    public fun remove(id: String) {
        val index: Int = registrations.indexOfFirst { it.plugin.id == id }
        if (index < 0) return

        val dependents: List<String> = registrations
            .filter { entry -> entry.plugin.manifest.requires.any { !it.optional && it.manifest.id == id } }
            .map { it.plugin.id }
        if (dependents.isNotEmpty()) {
            throw registryError(
                PluginErrorCodes.HAS_DEPENDENTS,
                "Plugin \"$id\" cannot be removed: ${dependents.joinToString()} still require it.",
                mapOf("id" to id, "dependents" to dependents),
            )
        }

        teardown(registrations[index])
        registrations.removeAt(index)
        host.emit(EventKey<Map<String, Any?>>("plugin:disposed"), mapOf("id" to id))
    }

    // Reverse registration order, because a dependency is always registered
    // before whatever needs it: going backwards tears down dependents first.
    // One plugin's failing dispose() is reported and the rest still run.
    public fun dispose() {
        if (disposed) return
        disposed = true
        for (index in registrations.indices.reversed()) {
            teardown(registrations[index])
        }
        registrations.clear()
    }

    private fun validate(manifest: PluginManifest) {
        checkNotAlreadyRegistered(manifest.id)
        checkRequirements(manifest)
        checkCoreVersion(manifest)
    }

    private fun checkNotAlreadyRegistered(id: String) {
        if (registrations.any { it.plugin.id == id }) {
            throw registryError(
                PluginErrorCodes.DUPLICATE_ID,
                "Plugin \"$id\" is already registered. Declare `replaces` to opt in to a same-id swap.",
                mapOf("id" to id),
            )
        }
    }

    private fun checkRequirements(manifest: PluginManifest) {
        for (requirement in manifest.requires) {
            val installed: Registration? = registrations
                .firstOrNull { it.plugin.id == requirement.manifest.id }
            if (installed == null) {
                requireDependency(manifest.id, requirement)
            } else {
                requireVersion(manifest.id, requirement, installed.plugin.manifest.version)
            }
        }
    }

    private fun checkCoreVersion(manifest: PluginManifest) {
        val minCoreVersion: String = manifest.minCoreVersion ?: return
        if (compareSemver(coreVersion, minCoreVersion) >= 0) return
        throw registryError(
            PluginErrorCodes.INCOMPATIBLE_CORE_VERSION,
            "Plugin \"${manifest.id}\" requires core >= $minCoreVersion but $coreVersion is running.",
            mapOf(
                "id" to manifest.id,
                "requiredCoreVersion" to minCoreVersion,
                "coreVersion" to coreVersion,
            ),
        )
    }

    // The plugin's own dispose() may throw; the lifecycle teardown after it
    // must happen regardless, or the plugin's listeners and timers outlive it.
    // And the teardown itself may throw, which must not stop the plugins behind
    // this one in the loop from being torn down at all.
    @Suppress("TooGenericExceptionCaught")
    private fun teardown(registration: Registration) {
        if (registration.lifecycle.isDisposed()) return
        try {
            registration.plugin.dispose()
        } catch (cause: Throwable) {
            host.report(
                registryError(
                    PluginErrorCodes.DISPOSE_FAILED,
                    "Plugin \"${registration.plugin.id}\" threw from dispose().",
                    mapOf("id" to registration.plugin.id),
                    Severity.WARNING,
                    cause,
                ),
            )
        }
        try {
            registration.lifecycle.dispose()
        } catch (cause: Throwable) {
            host.report(
                registryError(
                    CoreErrorCodes.CLEANUP_FAILED,
                    "Tearing down \"${registration.plugin.id}\" threw; the remaining plugins were still disposed.",
                    mapOf("id" to registration.plugin.id),
                    Severity.WARNING,
                    cause,
                ),
            )
        }
    }
}

// An optional dependency that is absent is not a failure: the plugin loads
// and does less, which is how a visualiser degrades without an audio graph.
private fun requireDependency(id: String, requirement: Requirement) {
    if (requirement.optional) return
    throw registryError(
        PluginErrorCodes.MISSING_DEP,
        "Plugin \"$id\" requires \"${requirement.manifest.id}\" but it is not registered.",
        mapOf("id" to id, "requires" to requirement.manifest.id),
    )
}

private fun requireVersion(id: String, requirement: Requirement, installedVersion: String) {
    val minVersion: String = requirement.minVersion ?: return
    if (compareSemver(installedVersion, minVersion) >= 0) return
    throw registryError(
        PluginErrorCodes.VERSION_MISMATCH,
        "Plugin \"$id\" requires \"${requirement.manifest.id}\" >= $minVersion " +
            "but $installedVersion is registered.",
        mapOf(
            "id" to id,
            "requires" to requirement.manifest.id,
            "requiredVersion" to minVersion,
            "installedVersion" to installedVersion,
        ),
    )
}

private fun registryError(
    code: String,
    message: String,
    context: Map<String, Any?>,
    severity: Severity = Severity.ERROR,
    cause: Throwable? = null,
): PlayerError = PlayerError(
    code = code,
    scope = ErrorScope.core(),
    severity = severity,
    message = message,
    cause = cause,
    context = context,
)
