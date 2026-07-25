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

    @Test
    fun theRawBusIsFlaggedInsideAPlugin() {
        val findings = bus(
            """
            class Lyrics : Plugin<Options>() {
                fun use() {
                    this.player.on("play") { }
                }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("this.on"))
    }

    @Test
    fun theScopedFormAndOrdinaryPlayerCallsAreLeftAlone() {
        val findings = bus(
            """
            class Lyrics : Plugin<Options>() {
                fun use() {
                    this.on("play") { }
                    this.player.play()
                    this.player.seekByPercentage(50)
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
            class TransportController {
                fun use() {
                    this.player.on("play") { }
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
            class Lyrics : Plugin<Options>() {
                @Suppress("NoRawPlayerBus")
                fun use() {
                    this.player.on("play") { }
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
            ),
            ruleSet.rules.map { it.ruleId },
        )
    }
}
