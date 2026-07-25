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
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

// A one-letter name is a name you have to hold in your head, and this codebase
// is read by people who find that expensive. Loop counters and coordinates are
// the exception because there the letter IS the convention.
class NoSingleLetterIdent(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoSingleLetterIdent",
        severity = Severity.Warning,
        description = "A one-letter name has to be held in your head; loop counters and coordinates are the exception.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        flagIfSingleLetter(property)
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        // A lambda parameter is often the whole expression's subject and naming
        // it costs more than it explains; loop variables are covered below.
        if (parameter.isLoopParameter) return
        flagIfSingleLetter(parameter)
    }

    private fun flagIfSingleLetter(declaration: KtNamedDeclaration) {
        val name: String = declaration.name ?: return
        if (name.length != 1) return
        if (name in ALLOWED) return

        report(
            CodeSmell(
                issue,
                Entity.from(declaration),
                "'$name' says nothing about what it holds. Name it after the thing.",
            ),
        )
    }

    private companion object {
        // Counters and coordinates, where the letter is the convention and a
        // longer name would read worse. Underscore is Kotlin for deliberately
        // unused, which is the opposite of the problem this rule is about.
        val ALLOWED: Set<String> = setOf("_", "i", "j", "k", "n", "x", "y", "z", "t")
    }
}
