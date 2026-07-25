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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlayerErrorTest {

    @Test
    fun anAuthErrorIsStillANetworkErrorSoRetryHandlersKeepCatchingIt() {
        val error = AuthError("core:auth/forbidden", scope = ErrorScope(ScopeKind.AUTH))

        assertIs<NetworkError>(error)
        assertIs<PlayerError>(error)
        assertEquals(Severity.ERROR, error.severity)
    }

    @Test
    fun everyConcreteErrorCarriesAParseableCode() {
        val errors: List<PlayerError> = listOf(
            stateError("core:state/queue-empty", "queue empty"),
            mediaFormatError("core:media/codec-unsupported", "no hevc"),
            resourceError("core:resource/worker-init-failed", "boom"),
            browserPolicyError("core:policy/autoplay-blocked", "tap to start", suggestion = "Tap to play."),
            pluginError("lyrics:parse/bad-lrc", "bad lrc", pluginId = "lyrics"),
            DrmError("core:drm/key-expired", ErrorScope.core()),
            StreamError("core:stream/fragment-failed", ErrorScope.stream("hls")),
            NotImplementedError("no subs", "subtitles"),
            AuthError("core:auth/forbidden", ErrorScope(ScopeKind.AUTH)),
        )

        errors.forEach { error -> ErrorCode.parse(error.code) }
        assertEquals(errors.size, errors.map { it.code }.distinct().size)
    }

    @Test
    fun aFactoryPrefixesTheMessageWithTheCodeSoALogLineIsSelfIdentifying() {
        val error = stateError("core:state/queue-empty", "nothing to play")

        assertEquals("core:state/queue-empty", error.code)
        assertEquals(ScopeKind.CORE, error.scope.kind)
        assertEquals("core:state/queue-empty: nothing to play", error.message)
    }

    @Test
    fun aPluginErrorIsAttributedToThePluginNotToTheCore() {
        val attributed = pluginError(
            "lyrics:parse/bad-lrc",
            "bad lrc",
            severity = Severity.WARNING,
            pluginId = "lyrics",
        )
        assertEquals(ScopeKind.PLUGIN, attributed.scope.kind)
        assertEquals("lyrics", attributed.scope.id)
        assertEquals(Severity.WARNING, attributed.severity)

        val anonymous = pluginError("core:plugin/unknown", "no id given")
        assertEquals(ScopeKind.CORE, anonymous.scope.kind)
    }

    @Test
    fun notImplementedAlwaysBuildsAParseableCodeEvenWithoutAFeature() {
        assertEquals("core:not-implemented/subtitles", NotImplementedError("no subs", "subtitles").code)
        assertEquals("core:not-implemented/unknown", NotImplementedError("no idea").code)
        ErrorCode.parse(NotImplementedError("no idea").code)
    }

    @Test
    fun isHttpMatchesTheStatusCenturyAndOnlyARealInt() {
        val serverSide = NetworkError(
            "core:network/server-error",
            ErrorScope(ScopeKind.NETWORK),
            context = mapOf("httpStatus" to 503),
        )
        assertTrue(serverSide.isHttp(5))
        assertFalse(serverSide.isHttp(4))

        // The context bag is untyped, so a string status must not answer yes.
        val stringed = NetworkError(
            "core:network/server-error",
            ErrorScope(ScopeKind.NETWORK),
            context = mapOf("httpStatus" to "503"),
        )
        assertFalse(stringed.isHttp(5))

        val none = NetworkError("core:network/offline", ErrorScope(ScopeKind.NETWORK))
        assertFalse(none.isHttp(5))
    }

    @Test
    fun aPolicyErrorCarriesTheSentenceToShowTheViewer() {
        val error = browserPolicyError(
            "core:policy/autoplay-blocked",
            "blocked",
            suggestion = "Tap anywhere to play.",
        )

        assertEquals("Tap anywhere to play.", error.suggestion)
    }

    @Test
    fun theHierarchyStaysOpenSoAConsumerCanMintItsOwn() {
        // The taxonomy is a vocabulary, not a wall: this compiles, and that is
        // the assertion.
        val vendored = object : PlayerError("fillz:viz/canvas-init", ErrorScope.plugin("fillz")) {}

        assertIs<PlayerError>(vendored)
        assertEquals("fillz", vendored.scope.id)
    }
}
