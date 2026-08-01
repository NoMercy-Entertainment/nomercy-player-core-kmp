// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.ItemChange
import tv.nomercy.player.core.events.QueueAppend
import tv.nomercy.player.core.events.QueueChange
import tv.nomercy.player.core.events.QueueClear
import tv.nomercy.player.core.events.QueueInsert
import tv.nomercy.player.core.events.QueueMove
import tv.nomercy.player.core.events.QueuePrepend
import tv.nomercy.player.core.events.QueueRemove
import tv.nomercy.player.core.media.MediaList
import tv.nomercy.player.core.media.PlaylistItem
import tv.nomercy.player.core.player.PlayerPhase

// Owns the queue, the backlog and the cursor.
//
// The MediaList underneath already knows how to keep the cursor on the playing
// item. This controller's job is everything around that: republishing the
// list's changes on the player's own bus, and turning "play this one" into a
// cursor move plus a load.
//
// Wide because the queue API is wide — ten mutations, each with an event. The
// alternative is a consumer reaching into the MediaList directly, which is
// exactly the coupling this exists to prevent.
@Suppress("TooManyFunctions")
public open class QueueController(private val ctx: PlayerContext) {

    // Set when the player is composed. Without it the cursor still moves and
    // the item still loads; only autoplay needs a transport.
    public var transport: TransportController? = null

    // A queue replaced while an item was loading must not have the finished
    // load overwrite the newer choice. Every load carries the epoch it started
    // in and drops out if it is no longer current.
    private var epoch: Int = 0

    // Republishes the MediaList's own events on the player bus. Idempotent
    // because composition and a late setup both call it, and subscribing twice
    // would double every queue event.
    public fun wireQueue() {
        if (ctx.queueWired) return
        ctx.queueWired = true
        wire(ctx.queue, backlog = false)
        wire(ctx.backlog, backlog = true)
    }

    public fun queue(): List<PlaylistItem> = ctx.queue.get()

    public fun queue(items: List<PlaylistItem>) {
        epoch += 1
        ctx.queue.set(items.map(::ingest))
    }

    public fun queueLength(): Int = ctx.queue.length()

    public fun queueIndexOf(id: String): Int = ctx.queue.indexOf(id)

    public fun queueAppend(items: List<PlaylistItem>): Unit = ctx.queue.append(items.map(::ingest))

    public fun queuePrepend(items: List<PlaylistItem>): Unit = ctx.queue.prepend(items.map(::ingest))

    public fun queueInsert(items: List<PlaylistItem>, index: Int): Unit =
        ctx.queue.insert(items.map(::ingest), index)

    public fun queueRemove(id: String): Unit = ctx.queue.remove(id)

    public fun queueRemoveAt(index: Int): Unit = ctx.queue.removeAt(index)

    public fun queueMove(from: Int, to: Int): Unit = ctx.queue.move(from, to)

    public fun queueClear(): Unit = ctx.queue.clear()

    public fun queueShuffle(): Unit = ctx.queue.shuffle()

    public fun queueSort(comparator: Comparator<PlaylistItem>): Unit = ctx.queue.sort(comparator)

    public fun backlog(): List<PlaylistItem> = ctx.backlog.get()

    // Replacing the backlog wholesale is how a host restores one: a session
    // resumes and hands back the history it persisted, in one call rather than
    // an append per item that would emit a change event each time.
    public fun backlog(items: List<PlaylistItem>): Unit = ctx.backlog.set(items)

    public fun backlogAppend(items: List<PlaylistItem>): Unit = ctx.backlog.append(items)

    public fun backlogRemove(id: String): Unit = ctx.backlog.remove(id)

    public fun backlogClear(): Unit = ctx.backlog.clear()

    public fun item(): PlaylistItem? = ctx.queue.current()

    public fun index(): Int = ctx.queue.currentIndex()

    public fun peekNext(): PlaylistItem? = ctx.queue.peekNext()

    public fun peekPrevious(): PlaylistItem? = ctx.queue.peekPrevious()

    // Moves the cursor and, once the player is running, loads what it landed
    // on. Before setup it only moves the cursor: a host building a queue ahead
    // of time should not trigger playback by choosing a starting track.
    public suspend fun item(id: String, autoplay: Boolean = false) {
        // Resolved first: without this, an id that is not in the queue leaves
        // the cursor alone and then reloads whatever was already playing.
        val index: Int = ctx.queue.indexOf(id)
        if (index < 0) return

        ctx.queue.setCurrent(index)
        val chosen: PlaylistItem = ctx.queue.current() ?: return
        if (ctx.phase == PlayerPhase.IDLE || ctx.phase == PlayerPhase.DISPOSED) return

        val startedIn: Int = ++epoch
        ctx.load(chosen)
        if (autoplay && startedIn == epoch) transport?.play()
    }

    public suspend fun playItem(id: String): Unit = item(id, autoplay = true)

    // One-based, because it exists for "jump to track 3" from a UI that counts
    // the way people do. Out of range is ignored rather than clamped: jumping
    // to a track that is not there should do nothing, not play the last one.
    public fun seekToIndex(position: Int) {
        require(position > 0) { "seekToIndex is one-based; got $position" }
        if (position > ctx.queue.length()) return
        ctx.queue.setCurrent(position - 1)
    }

    // The seam a library uses to normalise an item on the way in — resolving
    // relative urls, filling a title from metadata. Pass-through in core, which
    // has nothing to normalise against.
    protected open fun ingest(item: PlaylistItem): PlaylistItem = item

    private fun wire(list: MediaList<PlaylistItem>, backlog: Boolean) {
        list.onChange { ctx.emit(if (backlog) CoreEvents.Backlog else CoreEvents.Queue, QueueChange(it.items)) }
        list.onAppend {
            ctx.emit(
                if (backlog) CoreEvents.BacklogAppend else CoreEvents.QueueAppend,
                QueueAppend(it.items, it.from),
            )
        }
        list.onRemove {
            ctx.emit(
                if (backlog) CoreEvents.BacklogRemove else CoreEvents.QueueRemove,
                QueueRemove(it.id, it.index, it.item),
            )
        }
        list.onClear {
            ctx.emit(
                if (backlog) CoreEvents.BacklogClear else CoreEvents.QueueClear,
                QueueClear(it.previousLength),
            )
        }
        if (backlog) return

        list.onPrepend { ctx.emit(CoreEvents.QueuePrepend, QueuePrepend(it.items)) }
        list.onInsert { ctx.emit(CoreEvents.QueueInsert, QueueInsert(it.items, it.index)) }
        list.onMove { ctx.emit(CoreEvents.QueueMove, QueueMove(it.from, it.to)) }
        list.onShuffle { ctx.emit(CoreEvents.QueueShuffle, QueueChange(it.items)) }
        list.onSort { ctx.emit(CoreEvents.QueueSort, QueueChange(it.items)) }

        // The cursor moving is what a chrome renders as "now playing", whether
        // a caller moved it or a reorder carried it.
        list.onCurrent { announceCursorMove(it.item, it.index) }
    }

    // The position and duration still describe the OUTGOING item, because the
    // cursor moves before the media does. Dropped here rather than left for the
    // incoming item to inherit — a listener reading time() inside this very
    // event otherwise gets the previous item's end position and attributes it
    // to the new one.
    private fun announceCursorMove(item: PlaylistItem?, index: Int) {
        if (ctx.mediaIsStale()) {
            ctx.internalCurrentTime = 0.0
            ctx.internalDuration = 0.0
        }
        ctx.emit(CoreEvents.Item, ItemChange(item, index))
    }
}
