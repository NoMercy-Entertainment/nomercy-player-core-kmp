// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.detekt

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTypeReference
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
// a receiver call from a bare on(...).
internal fun KtCallExpression.receiverText(): String? {
    val parent: KtDotQualifiedExpression = this.parent as? KtDotQualifiedExpression ?: return null
    return if (parent.selectorExpression === this) parent.receiverExpression.text else null
}

// Whether [name] is something this plugin holds that IS the player.
//
// The web base class exposes `this.player`, and this pack was ported watching
// for that. Kotlin's base does not have it: there is no `player` property to
// reach through, so the rule was guarding a shape that cannot compile. What a
// Kotlin plugin does instead is take the player as a constructor argument or
// keep it in a field, and call the bus on that — which leaks exactly the same
// way and was passing silently.
//
// Matched on the DECLARED TYPE, not on the name. `host`, `owner`, `bus` and
// `nmPlayer` are all names somebody has used; a rule keyed on any of them is a
// rule that misses the next one. The type is the stable thing.
internal fun KtClassOrObject.holdsPlayerNamed(name: String): Boolean {
    val declaredTypes: List<String> = playerLikeDeclarations()
    return declaredTypes.contains(name)
}

// Every property and constructor parameter of this class whose declared type
// names the player, the plugin host or the event bus, by the identifier it is
// reachable through.
private fun KtClassOrObject.playerLikeDeclarations(): List<String> {
    val fromParameters: List<String> = (this as? KtClass)?.primaryConstructorParameters.orEmpty()
        .filter { it.typeReference.namesPlayerLike() }
        .mapNotNull { it.name }

    val fromProperties: List<String> = declarations
        .filterIsInstance<KtProperty>()
        .filter { it.typeReference.namesPlayerLike() }
        .mapNotNull { it.name }

    return fromParameters + fromProperties
}

private fun KtTypeReference?.namesPlayerLike(): Boolean {
    val named: String = (this?.typeElement as? KtUserType)?.referencedName ?: return false
    return named in PLAYER_LIKE_TYPES
}

// The types that are the bus, or own it. A plugin holding one of these and
// calling on/emit through it has reached around its own base class.
//
// FakePlayer is here because the shipped stand-in is a real PluginHost, so a
// plugin written against it in a test leaks in exactly the way the harness then
// reports — and being told at the call site beats being told at teardown.
private val PLAYER_LIKE_TYPES: Set<String> = setOf(
    "Player",
    "ComposedPlayer",
    "PluginHost",
    "EventEmitter",
    "FakePlayer",
    "NMVideoPlayer",
    "NMMusicPlayer",
)
