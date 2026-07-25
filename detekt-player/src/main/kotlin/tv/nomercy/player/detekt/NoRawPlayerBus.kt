
package tv.nomercy.player.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression

// this.player.on(...) inside a plugin reaches the player's own bus. It works,
// and it gives up both guarantees the plugin base provides: the listener is
// never removed at teardown, and an emit lands on the global channel instead of
// plugin:<id>:, where another plugin listening for your event will not find it.
//
// Advisory. @Suppress("NoRawPlayerBus") silences it and nothing changes at
// runtime — a consumer who means it is not blocked.
class NoRawPlayerBus(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoRawPlayerBus",
        severity = Severity.Warning,
        description = "this.player.on/once/off/emit inside a plugin skips scoping and auto-dispose; " +
            "the plugin base has the same four.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val method: String = expression.calleeExpression?.text ?: return
        if (method !in BUS_METHODS) return
        if (expression.receiverText() != "this.player") return
        if (!expression.inPluginSubclass()) return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "this.player.$method(...) inside a plugin skips scoping and auto-dispose. " +
                    "Use this.$method(...) instead.",
            ),
        )
    }

    private companion object {
        val BUS_METHODS: Set<String> = setOf("on", "once", "off", "emit")
    }
}
