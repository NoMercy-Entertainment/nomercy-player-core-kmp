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
import org.jetbrains.kotlin.psi.KtAnnotationEntry

// Suppressing UNCHECKED_CAST turns a compiler warning into a ClassCastException
// somewhere else, usually inside a listener where the stack trace points at the
// bus rather than at the cast.
//
// The library has exactly one, at the type-erasure boundary in EventEmitter,
// and it is documented there. If a plugin needs a second, the typed EventKey it
// is casting around is the thing to fix.
class NoUncheckedCast(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoUncheckedCast",
        severity = Severity.Warning,
        description = "A suppressed unchecked cast becomes a ClassCastException whose stack trace blames the bus.",
        debt = Debt.TEN_MINS,
    )

    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        if (annotationEntry.shortName?.asString() != "Suppress") return
        val mentionsUnchecked: Boolean = annotationEntry.valueArguments.any {
            it.getArgumentExpression()?.text?.contains("UNCHECKED_CAST") == true
        }
        if (!mentionsUnchecked) return

        report(
            CodeSmell(
                issue,
                Entity.from(annotationEntry),
                "An unchecked cast fails at the call site, not here. A typed EventKey carries the payload type " +
                    "so the cast is not needed.",
            ),
        )
    }
}
