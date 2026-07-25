// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtProperty

// A plugin without a manifest has no id, so the registry cannot name it in an
// error, another plugin cannot require it, and its storage keys and events have
// nothing to be namespaced by. The compiler will say the member is missing;
// this says why it matters and what to write.
class PluginManifestRequired(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "PluginManifestRequired",
        severity = Severity.Warning,
        description = "A plugin needs a manifest: it is the id everything else — storage, events, errors, requires — hangs off.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (!klass.extendsPlugin()) return
        // An abstract intermediate is a base for other plugins and is allowed to
        // leave the manifest to whoever concretes it.
        if (klass.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return

        val declaresManifest: Boolean = klass.body?.declarations
            ?.filterIsInstance<KtProperty>()
            ?.any { it.name == "manifest" } == true
        if (declaresManifest) return

        report(
            CodeSmell(
                issue,
                Entity.from(klass),
                "${klass.name ?: "This plugin"} has no manifest. Add " +
                    "'companion object Manifest : PluginManifest { override val id = ...; override val version = ... }' " +
                    "and 'override val manifest get() = Manifest'.",
            ),
        )
    }
}
