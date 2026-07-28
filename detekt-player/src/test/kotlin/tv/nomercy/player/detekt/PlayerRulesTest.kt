// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Every rule is checked three ways: it fires where it should, it stays quiet on
// the sanctioned form, and it can be silenced. The third matters most — a rule
// that cannot be suppressed is a wall, and this project decided not to build
// walls.
class PlayerRulesTest {

    private fun bus(code: String) = NoRawPlayerBus(Config.empty).lint(code)
    private fun timers(code: String) = NoRawTimersInPlugin(Config.empty).lint(code)
    private fun fetch(code: String) = NoRawFetchInPlugin(Config.empty).lint(code)
    private fun manifest(code: String) = PluginManifestRequired(Config.empty).lint(code)
    private fun casts(code: String) = NoUncheckedCast(Config.empty).lint(code)
    private fun idents(code: String) = NoSingleLetterIdent(Config.empty).lint(code)
    private fun sequenced(code: String) = NoSequencedCollectionApi(Config.empty).lint(code)

    // The shape a Kotlin plugin actually leaks through: it was handed the
    // player and calls the bus on it. The base class has the same four methods
    // and cleans up after them.
    @Test
    fun theRawBusIsFlaggedWhenThePluginHoldsThePlayer() {
        val findings = bus(
            """
            class Lyrics(private val host: PluginHost) : Plugin<Options>() {
                fun use() {
                    host.on("play") { }
                }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("this.on"))
    }

    // Same reach, written through this. An IDE produces this spelling as soon
    // as anything in the method shadows the name.
    @Test
    fun theRawBusIsFlaggedThroughThisToo() {
        val findings = bus(
            """
            class Lyrics : Plugin<Options>() {
                private val owner: ComposedPlayer = somePlayer()
                fun use() {
                    this.owner.emit(MyEvents.Line, line)
                }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    // The name says nothing. A property called `player` that is not one is a
    // property, and the rule that fired on it would be firing on a name.
    @Test
    fun aReceiverThatIsNotAPlayerIsNotTheRulesBusiness() {
        val findings = bus(
            """
            class Lyrics(private val channel: RealtimeChannel) : Plugin<Options>() {
                private val player: LyricsAnimator = LyricsAnimator()
                fun use() {
                    channel.on(RealtimeEvent.MESSAGE) { }
                    player.on("frame") { }
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun theScopedFormAndOrdinaryPlayerCallsAreLeftAlone() {
        val findings = bus(
            """
            class Lyrics(private val host: PluginHost) : Plugin<Options>() {
                fun use() {
                    this.on("play") { }
                    host.play()
                    host.seekByPercentage(50)
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun theSameCallOutsideAPluginIsNotTheRulesBusiness() {
        // Core's controllers reach the bus directly because they are the bus.
        val findings = bus(
            """
            class TransportController(private val host: PluginHost) {
                fun use() {
                    host.on("play") { }
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun theRawBusRuleCanBeSilenced() {
        val findings = bus(
            """
            class Lyrics(private val host: PluginHost) : Plugin<Options>() {
                @Suppress("NoRawPlayerBus")
                fun use() {
                    host.on("play") { }
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun anUnscopedCoroutineInAPluginIsFlaggedAndTheScopedHelpersAreNot() {
        val flagged = timers(
            """
            class Viz : Plugin<Options>() {
                fun use() {
                    launch { paint() }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, flagged.size)

        val clean = timers(
            """
            class Viz : Plugin<Options>() {
                fun use() {
                    this.interval(16) { paint() }
                    this.timeout(1000) { done() }
                }
            }
            """.trimIndent(),
        )
        assertTrue(clean.isEmpty())
    }

    @Test
    fun theTimerRuleCanBeSilenced() {
        val findings = timers(
            """
            class Viz : Plugin<Options>() {
                @Suppress("NoRawTimersInPlugin")
                fun use() {
                    launch { paint() }
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun aPluginsOwnHttpClientIsFlaggedAndTheHostsFetchIsNot() {
        val flagged = fetch(
            """
            class Art : Plugin<Options>() {
                suspend fun load() {
                    val client = HttpClient()
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, flagged.size)

        val clean = fetch(
            """
            class Art : Plugin<Options>() {
                suspend fun load() {
                    this.fetch("https://example.test/art")
                }
            }
            """.trimIndent(),
        )
        assertTrue(clean.isEmpty())
    }

    @Test
    fun aPluginWithoutAManifestIsFlaggedAndOneWithItIsNot() {
        val flagged = manifest(
            """
            class Nameless : Plugin<Options>() {
                override fun use() { }
            }
            """.trimIndent(),
        )
        assertEquals(1, flagged.size)
        assertTrue(flagged.single().message.contains("PluginManifest"))

        val clean = manifest(
            """
            class Named : Plugin<Options>() {
                companion object Manifest : PluginManifest {
                    override val id: String = "named"
                    override val version: String = "1.0.0"
                }
                override val manifest: PluginManifest get() = Manifest
            }
            """.trimIndent(),
        )
        assertTrue(clean.isEmpty())
    }

    @Test
    fun anAbstractPluginBaseMayLeaveTheManifestToItsSubclasses() {
        val findings = manifest(
            """
            abstract class BasePlugin : Plugin<Options>() {
                open fun shared() { }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun aSuppressedUncheckedCastIsFlaggedAndOtherSuppressionsAreNot() {
        val flagged = casts(
            """
            class Thing {
                @Suppress("UNCHECKED_CAST")
                fun get(): List<String> = raw as List<String>
            }
            """.trimIndent(),
        )
        assertEquals(1, flagged.size)

        val clean = casts(
            """
            class Thing {
                @Suppress("TooManyFunctions")
                fun get(): List<String> = emptyList()
            }
            """.trimIndent(),
        )
        assertTrue(clean.isEmpty())
    }

    @Test
    fun aOneLetterNameIsFlaggedButCountersAndCoordinatesAreNot() {
        val flagged = idents(
            """
            class Thing {
                fun go() {
                    val p = buildPlayer()
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, flagged.size)

        val clean = idents(
            """
            class Thing {
                fun go() {
                    val i = 0
                    val x = 1.0
                    val n = count()
                }
            }
            """.trimIndent(),
        )
        assertTrue(clean.isEmpty())
    }

    @Test
    fun anUnderscoreIsDeliberatelyUnusedRatherThanUninformative() {
        val findings = idents(
            """
            class Thing {
                fun go() {
                    bus.onAll { name, _ -> record(name) }
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun aLoopVariableIsNotAName() {
        val findings = idents(
            """
            class Thing {
                fun go() {
                    for (a in list) { use(a) }
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun removingTheLastElementByItsJavaNameIsFlagged() {
        val findings = sequenced(
            """
            class Emitter {
                fun unwind() {
                    stack.removeLast()
                }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("removeAt(lastIndex)"))
    }

    @Test
    fun removingTheFirstElementIsFlaggedWithItsOwnReplacement() {
        val findings = sequenced(
            """
            class Queue {
                fun take() {
                    pending.removeFirst()
                }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("removeAt(0)"))
    }

    @Test
    fun theIndexedFormIsWhatTheRuleIsAskingFor() {
        val findings = sequenced(
            """
            class Emitter {
                fun unwind() {
                    stack.removeAt(stack.lastIndex)
                    pending.removeAt(0)
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun aRemoveThatTakesAnArgumentIsADifferentFunction() {
        // Only the no-argument forms collide with java.util.List. Flagging
        // anything named removeFirst would catch functions that were never at
        // risk, and a rule that cries wolf gets switched off.
        val findings = sequenced(
            """
            class Buffer {
                fun drop() {
                    frames.removeFirst(count)
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun theProviderExposesEveryRuleUnderOneId() {
        val ruleSet = PlayerRuleSetProvider().instance(Config.empty)

        assertEquals("player", ruleSet.id)
        assertEquals(
            listOf(
                "NoRawPlayerBus",
                "NoRawTimersInPlugin",
                "NoRawFetchInPlugin",
                "PluginManifestRequired",
                "NoUncheckedCast",
                "NoSingleLetterIdent",
                "NoSequencedCollectionApi",
            ),
            ruleSet.rules.map { it.ruleId },
        )
    }
}
