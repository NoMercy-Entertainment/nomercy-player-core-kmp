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
import org.jetbrains.kotlin.psi.KtCallExpression

// removeLast() and removeFirst() compile on every target and crash on some
// Android devices.
//
// Kotlin has had list.removeLast() as its own extension for years. Java 21 then
// added a method of the same name to java.util.List through SequencedCollection,
// and on JVM target 21 the Java one wins overload resolution. The call site does
// not change, the build stays green, and the bytecode now names an interface
// method that only exists on newer Android runtimes.
//
// It cannot be gated on an API level. Two phones here both report API 34 and
// disagree: the one whose ART mainline module has been updated has the method,
// and the Android TV box that has never taken a mainline update does not. On
// that box every call died with NoSuchMethodError — 271 of 697 tests, and in a
// shipped build it would have been the entire event system, on exactly the
// devices least likely to be sitting on a developer's desk.
//
// removeAt(lastIndex) and removeAt(0) have no such twin and mean the same thing.
class NoSequencedCollectionApi(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoSequencedCollectionApi",
        severity = Severity.Defect,
        description = "Java 21's SequencedCollection methods shadow Kotlin's and are missing on some Android runtimes.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val name: String = expression.calleeExpression?.text ?: return
        if (name !in SHADOWED) return

        // Only the no-argument forms collide. A removeFirst(predicate) or
        // anything else taking arguments is a different function entirely.
        if (expression.valueArguments.isNotEmpty()) return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "$name() resolves to java.util.List on JVM 21 and is absent on older Android runtimes. " +
                    "Use ${REPLACEMENTS.getValue(name)} instead.",
            ),
        )
    }

    private companion object {
        val REPLACEMENTS: Map<String, String> = mapOf(
            "removeLast" to "removeAt(lastIndex)",
            "removeFirst" to "removeAt(0)",
        )

        val SHADOWED: Set<String> = REPLACEMENTS.keys
    }
}
