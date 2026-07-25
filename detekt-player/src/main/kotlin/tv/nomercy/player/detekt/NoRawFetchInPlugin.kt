
package tv.nomercy.player.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression

// A plugin that builds its own HTTP client sends unauthenticated requests to a
// server that signs its urls, and gets a 401 it then has to handle itself. The
// host's fetch already carries the token, the base url and the refresh-and-retry.
class NoRawFetchInPlugin(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoRawFetchInPlugin",
        severity = Severity.Warning,
        description = "A plugin's own HTTP client misses the token, the base url and the retry; this.fetch has them.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee: String = expression.calleeExpression?.text ?: return
        if (callee !in TRANSPORTS) return
        if (!expression.inPluginSubclass()) return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "$callee(...) inside a plugin bypasses the host's auth and retry. Use this.fetch(url, opts).",
            ),
        )
    }

    private companion object {
        val TRANSPORTS: Set<String> = setOf("HttpClient", "openConnection", "URLConnection", "readText")
    }
}
