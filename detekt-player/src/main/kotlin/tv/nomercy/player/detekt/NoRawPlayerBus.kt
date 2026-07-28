
package tv.nomercy.player.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

// A plugin calling on/once/off/emit on the player it holds reaches the player's
// own bus. It works, and it gives up both guarantees the plugin base provides:
// the listener is never removed at teardown, and an emit lands on the global
// channel instead of plugin:<id>:, where another plugin listening for your event
// will not find it.
//
// "the player it holds" means a field or constructor parameter whose declared
// type is one, not one whose name looks like one. This rule watched for
// `this.player` for a while, which is the web base class's shape and a thing
// Kotlin's does not have: there is no `player` property to reach through, so
// what it guarded could not compile and every real instance of the leak went
// past it. The template's own plant is what said so.
//
// Advisory. @Suppress("NoRawPlayerBus") silences it and nothing changes at
// runtime — a consumer who means it is not blocked.
class NoRawPlayerBus(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "NoRawPlayerBus",
        severity = Severity.Warning,
        description = "on/once/off/emit through a held player skips scoping and auto-dispose; " +
            "the plugin base has the same four.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val method: String = expression.calleeExpression?.text ?: return
        if (method !in BUS_METHODS) return

        val receiver: String = expression.receiverText() ?: return
        val enclosing: KtClassOrObject = expression.getStrictParentOfType<KtClassOrObject>() ?: return
        if (!enclosing.extendsPlugin()) return
        if (!enclosing.reachesThePlayerThrough(receiver)) return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "$receiver.$method(...) inside a plugin skips scoping and auto-dispose. " +
                    "Use this.$method(...) instead.",
            ),
        )
    }

    // Both spellings of the same reach: a field or parameter typed as the
    // player, and this.<that field>. The second is what a plugin written in an
    // IDE ends up with once anything else in the method shadows the name.
    private fun KtClassOrObject.reachesThePlayerThrough(receiver: String): Boolean =
        holdsPlayerNamed(receiver) || holdsPlayerNamed(receiver.removePrefix("this."))

    private companion object {
        val BUS_METHODS: Set<String> = setOf("on", "once", "off", "emit")
    }
}
