// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.detekt

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

// Where the advice applies.
//
// Every rule in this pack is about the boundary between a plugin and the player
// it is plugged into. Outside that boundary the same call is fine — core's own
// controllers reach the bus directly because they are the bus — so each rule
// asks these first and stays quiet everywhere else.

// A class extends Plugin when any supertype names it: bare Plugin, generic
// Plugin<Options>, or qualified core.Plugin. The referenced name is "Plugin" in
// all three, so one check covers them without type resolution.
internal fun KtClassOrObject.extendsPlugin(): Boolean =
    superTypeListEntries.any { entry ->
        (entry.typeReference?.typeElement as? KtUserType)?.referencedName == "Plugin"
    }

// The NEAREST enclosing class decides, so a helper class declared inside a
// plugin method is judged on its own supertypes rather than inheriting the
// outer plugin's boundary.
internal fun KtElement.inPluginSubclass(): Boolean {
    val enclosing: KtClassOrObject = getStrictParentOfType<KtClassOrObject>() ?: return false
    return enclosing.extendsPlugin()
}

internal fun KtExpression.isThisPlayer(): Boolean {
    val qualified: KtDotQualifiedExpression = this as? KtDotQualifiedExpression ?: return false
    return qualified.receiverExpression is KtThisExpression &&
        qualified.selectorExpression?.text == "player"
}

// The text before the dot, or null for a bare call. Rules use it to tell
// this.player.on(...) from a bare on(...).
internal fun KtCallExpression.receiverText(): String? {
    val parent: KtDotQualifiedExpression = this.parent as? KtDotQualifiedExpression ?: return null
    return if (parent.selectorExpression === this) parent.receiverExpression.text else null
}
