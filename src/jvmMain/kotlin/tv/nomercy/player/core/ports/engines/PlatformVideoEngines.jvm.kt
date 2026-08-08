// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.engines

// The desktop brings no engine of its own. libmpv is the engine, and the list
// it is appended to is empty rather than absent so that the shape stays the
// same on every target — an engine arriving here later is one entry, not a
// change to how selection works.
internal actual val platformVideoEngines: List<VideoEngineProvider> = emptyList()
