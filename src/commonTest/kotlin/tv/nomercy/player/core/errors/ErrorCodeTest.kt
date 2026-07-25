// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.errors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ErrorCodeTest {

    @Test
    fun aWellFormedCodeSplitsIntoItsThreeParts() {
        val code = ErrorCode.parse("core:auth/forbidden")

        assertEquals("core", code.namespace)
        assertEquals("auth", code.category)
        assertEquals("forbidden", code.reason)
    }

    @Test
    fun aPluginNamespaceParsesWithoutBeingAllowListed() {
        // The reason the scheme has a namespace at all: a plugin mints codes
        // nobody registered.
        val code = ErrorCode.parse("fillz:viz/canvas-init")

        assertEquals("fillz", code.namespace)
        assertEquals("canvas-init", code.reason)
    }

    @Test
    fun toStringAndMakeRebuildTheOriginalString() {
        assertEquals("core:auth/forbidden", ErrorCode.parse("core:auth/forbidden").toString())
        assertEquals("core:state/queue-empty", ErrorCode.make("core", "state", "queue-empty"))
    }

    @Test
    fun malformedCodesAreRejectedRatherThanGuessedAt() {
        assertNull(ErrorCode.parseOrNull("garbage"))
        assertNull(ErrorCode.parseOrNull("core:auth"))
        assertNull(ErrorCode.parseOrNull("core/auth/forbidden"))
        assertNull(ErrorCode.parseOrNull("core:auth/forbidden/extra"))
        assertNull(ErrorCode.parseOrNull(""))
    }

    @Test
    fun caseIsPartOfTheFormatBecauseDashboardsGroupOnTheString() {
        assertNull(ErrorCode.parseOrNull("Core:Auth/Forbidden"))
    }

    @Test
    fun parseThrowsAndTheMessageShowsTheExpectedShape() {
        val failure = assertFailsWith<IllegalArgumentException> { ErrorCode.parse("nope") }

        assertEquals(
            "malformed error code: 'nope' (expected namespace:category/reason)",
            failure.message,
        )
    }

    @Test
    fun severityLevelsAreOrderedIndependentlyOfDeclarationOrder() {
        assertEquals(1, Severity.INFO.level)
        assertEquals(2, Severity.WARNING.level)
        assertEquals(3, Severity.ERROR.level)
        assertEquals(4, Severity.FATAL.level)
        assertEquals(Severity.FATAL, Severity.fromToken("fatal"))
    }

    @Test
    fun scopeHelpersBuildTheRightKindAndCarryTheId() {
        assertEquals(ScopeKind.CORE, ErrorScope.core().kind)
        assertNull(ErrorScope.core().id)
        assertEquals(ScopeKind.PLUGIN, ErrorScope.plugin("lyrics").kind)
        assertEquals("lyrics", ErrorScope.plugin("lyrics").id)
        assertEquals("hls", ErrorScope.stream("hls").id)
        assertEquals(ScopeKind.BACKEND, ErrorScope.backend("exo").kind)
    }
}
