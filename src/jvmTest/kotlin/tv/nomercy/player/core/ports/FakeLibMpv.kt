// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.lang.reflect.Proxy
import java.util.Collections
import tv.nomercy.player.core.natives.libmpv.LibMpv
import tv.nomercy.player.core.natives.libmpv.MpvEvent
import tv.nomercy.player.core.natives.libmpv.MpvEventId
import tv.nomercy.player.core.natives.libmpv.MpvHandle

/** A libmpv that records `mpv_set_property_string` calls per handle and answers every other call with a zero. */
internal class FakeLibMpv {

    data class PropertySet(val handle: MpvHandle, val name: String, val value: String)

    val properties: MutableList<PropertySet> = Collections.synchronizedList(mutableListOf())

    val library: LibMpv = Proxy.newProxyInstance(
        LibMpv::class.java.classLoader,
        arrayOf(LibMpv::class.java),
    ) { _, method, args ->
        when (method.name) {
            "mpv_create" -> MpvHandle()
            "mpv_wait_event" -> MpvEvent.ByReference().apply { eventId = MpvEventId.SHUTDOWN }
            "mpv_set_property_string" -> {
                properties += PropertySet(args[0] as MpvHandle, args[1] as String, args[2] as String)
                0
            }
            else -> when (method.returnType) {
                Int::class.javaPrimitiveType -> 0
                Double::class.javaPrimitiveType -> 0.0
                Boolean::class.javaPrimitiveType -> false
                String::class.java -> ""
                else -> null
            }
        }
    } as LibMpv

    fun valuesOf(name: String): List<String> = properties.filter { it.name == name }.map { it.value }
}
