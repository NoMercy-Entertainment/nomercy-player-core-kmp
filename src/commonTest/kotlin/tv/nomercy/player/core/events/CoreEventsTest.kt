// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.media.PlaylistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreEventsTest {
    private data class Track(
        override val id: String,
        override val url: String = "https://example.test/$id",
        override val title: String? = null,
    ) : PlaylistItem

    @Test
    fun everyKeyNameIsTheWebBaseEventMapKeyVerbatim() {
        assertEquals("play", CoreEvents.Play.name)
        assertEquals("pause", CoreEvents.Pause.name)
        assertEquals("time", CoreEvents.Time.name)
        assertEquals("item", CoreEvents.Item.name)
        assertEquals("ended", CoreEvents.Ended.name)
        assertEquals("beforePlay", CoreEvents.BeforePlay.name)
        assertEquals("stream:error", CoreEvents.StreamError.name)
    }

    @Test
    fun aTypedPayloadRoundTripsThroughTheRegistryWithNoCast() {
        val bus = EventEmitter<Any>()
        var seen: TimeUpdate? = null
        bus.on(CoreEvents.Time) { seen = it }

        bus.emit(CoreEvents.Time, TimeUpdate(time = 12.5, duration = 100.0, percentage = 12.5))

        assertEquals(12.5, seen?.time)
        assertEquals(100.0, seen?.duration)
    }

    @Test
    fun theItemPayloadCarriesTheQueueEntryAndItsIndex() {
        val bus = EventEmitter<Any>()
        var seen: ItemChange? = null
        bus.on(CoreEvents.Item) { seen = it }

        bus.emit(CoreEvents.Item, ItemChange(item = Track(id = "abc"), index = 3))

        assertEquals("abc", seen?.item?.id)
        assertEquals(3, seen?.index)
    }

    @Test
    fun aUnitPayloadEventStillDelivers() {
        val bus = EventEmitter<Any>()
        var fired = false
        bus.on(CoreEvents.Ended) { fired = true }

        bus.emit(CoreEvents.Ended, Unit)

        assertTrue(fired)
    }

    @Test
    fun aNamespacedKeyIsAddressableByItsRawNameToo() {
        val bus = EventEmitter<Any>()
        var raw: Any? = null
        bus.on("stream:error") { raw = it }

        bus.emit(CoreEvents.StreamError, StreamError(details = "manifest 404", fatal = true))

        assertEquals(StreamError(details = "manifest 404", fatal = true), raw)
    }

    @Test
    fun beforePlayIsCancellableAndItsPayloadIsReshapedInPlace() = runTest {
        val bus = EventEmitter<Any>()
        bus.on(CoreEvents.BeforePlay) {
            it.data = PlaySource(source = "connect")
            it.preventDefault()
        }

        val result = bus.dispatchBefore(CoreEvents.BeforePlay, PlaySource(source = "ui"))

        assertTrue(result.prevented)
        assertEquals("connect", result.data.source)
        assertEquals(PreventReason.ListenerPrevented, result.reason)
    }
}
