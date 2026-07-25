// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Language code to key to translated string. A plugin ships its own bundle in
// its manifest and its keys are namespaced plugin.<id>.<key>, so shipping
// translations is part of writing a plugin rather than something bolted on
// after somebody complains.
public typealias Translations = Map<String, Map<String, String>>
