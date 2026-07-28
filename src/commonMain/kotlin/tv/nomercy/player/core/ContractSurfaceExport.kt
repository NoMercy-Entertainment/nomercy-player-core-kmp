// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core

import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.plugin.PluginErrorCodes
import tv.nomercy.player.core.events.CoreEvents

// What this library declares, as plain lists.
//
// The JVM gate asks the classes what they expose and Kotlin/Native has no
// reflection to ask with, so the Apple side gets the same answer handed to it as
// values. Without this a Swift conformance test could only check what somebody
// remembered to type into it, which is a test of the typing.
//
// Derived from the registries rather than restated. A second list would be a
// second place to forget, and the whole point is that these cannot disagree.
public object ContractSurfaceExport {

    public val eventNames: List<String> = CoreEvents.all.map { it.name }.sorted()

    // Both catalogues. The plugin codes are raised by the runtime rather than
    // by the player, and a Swift consumer catching an error does not care which
    // half of the library threw it — the JVM gate compares the union for the
    // same reason.
    public val errorCodes: List<String> = (CoreErrorCodes.all + PluginErrorCodes.all).sorted()

    // The version this build was compiled against, so a Swift test can say the
    // framework and the fixture describe the same ecosystem rather than assuming
    // the copy beside it is current.
    public val contractVersion: String = CONTRACT_VERSION
}
