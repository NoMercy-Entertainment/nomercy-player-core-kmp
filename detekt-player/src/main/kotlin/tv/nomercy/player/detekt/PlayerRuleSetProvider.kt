// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

// Seven pieces of advice, all advisory and all @Suppress-able.
//
// Nothing here prevents anything at runtime. The library never seals a class or
// makes a member final to stop a consumer doing something; the enforcement is
// guidance at the point of writing, and a consumer who means it says so and
// carries on. A rule pack that could not be silenced would be the wall this
// project decided not to build.
class PlayerRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "player"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            NoRawPlayerBus(config),
            NoRawTimersInPlugin(config),
            NoRawFetchInPlugin(config),
            PluginManifestRequired(config),
            NoUncheckedCast(config),
            NoSingleLetterIdent(config),
            NoSequencedCollectionApi(config),
        ),
    )
}
