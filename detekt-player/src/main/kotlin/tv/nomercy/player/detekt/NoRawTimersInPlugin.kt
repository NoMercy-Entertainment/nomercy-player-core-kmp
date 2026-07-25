
package tv.nomercy.player.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression

// A coroutine a plugin starts itself outlives the plugin. The lifecycle
// registry exists so that cannot happen: everything it starts is cancelled when
// the plugin goes away, and there is no teardown code to forget.
//
// The plugin base offers timeout, interval, frame and listen for exactly these.
class NoRawTimersInPlugin(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoRawTimersInPlugin",
        severity = Severity.Warning,
        description = "A coroutine a plugin launches itself outlives the plugin; " +
            "timeout/interval/frame/listen are cancelled for you.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee: String = expression.calleeExpression?.text ?: return
        val replacement: String = SUGGESTIONS[callee] ?: return
        if (!expression.inPluginSubclass()) return
        // this.timeout(...) and this.launch(...) on the base are the sanctioned
        // forms; only an unscoped call is the problem.
        if (expression.receiverText() == "this") return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "$callee(...) inside a plugin is not cancelled when the plugin is disposed. Use $replacement.",
            ),
        )
    }

    private companion object {
        val SUGGESTIONS: Map<String, String> = mapOf(
            "launch" to "this.timeout/interval/frame, or this.listen for a flow",
            "delay" to "this.timeout(ms) { }",
            "fixedRateTimer" to "this.interval(ms) { }",
            "Timer" to "this.interval(ms) { }",
        )
    }
}
