// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

import tv.nomercy.player.core.events.EventEmitter
import tv.nomercy.player.core.events.EventKey
import tv.nomercy.player.core.events.Subscription
import tv.nomercy.player.core.ports.FisherYatesShuffle
import tv.nomercy.player.core.ports.ShuffleStrategy

private const val EVENT_CHANGE = "change"
private const val EVENT_APPEND = "append"
private const val EVENT_PREPEND = "prepend"
private const val EVENT_INSERT = "insert"
private const val EVENT_REMOVE = "remove"
private const val EVENT_MOVE = "move"
private const val EVENT_CLEAR = "clear"
private const val EVENT_SHUFFLE = "shuffle"
private const val EVENT_SORT = "sort"
private const val EVENT_CURRENT = "current"

private const val NO_CURSOR = -1

// An ordered list that knows which item is playing.
//
// Every mutation keeps the cursor on the same item rather than the same index,
// which is the entire reason this type exists instead of a MutableList. Inserting
// three tracks above the playing one must not switch what is playing, and that
// is exactly what a plain list would do.
//
// Listeners are typed instance methods rather than a shared key registry: the
// payloads are generic in the item type, and a top-level key object cannot carry
// that without erasing it back to Any.
//
// The width is a collection's: MutableList has about thirty methods and this
// adds a cursor and ten typed listeners on top. Splitting it would mean a caller
// holding two objects that have to agree about one list.
@Suppress("TooManyFunctions")
public class MediaList<T : PlaylistItem>(
    private val shuffleStrategy: ShuffleStrategy = FisherYatesShuffle(),
) {
    private val bus: EventEmitter<Unit> = EventEmitter()
    private val entries: MutableList<T> = mutableListOf()
    private var cursor: Int = NO_CURSOR

    public fun get(): List<T> = entries.toList()

    public fun length(): Int = entries.size

    public fun currentIndex(): Int = cursor

    public fun current(): T? = entries.getOrNull(cursor)

    public fun peekNext(): T? = entries.getOrNull(cursor + 1)

    public fun peekPrevious(): T? = if (cursor <= 0) null else entries.getOrNull(cursor - 1)

    public fun indexOf(id: String): Int = entries.indexOfFirst { it.id == id }

    // Replaces the contents while keeping the playing item playing if it is
    // still there. A queue that gets re-fetched from the server should not
    // restart the track the viewer is listening to.
    public fun set(items: List<T>) {
        val playingId: String? = current()?.id
        entries.clear()
        entries.addAll(items)
        moveCursor(playingId?.let { indexOf(it) }?.takeIf { it >= 0 } ?: defaultCursor())

        // After the cursor lands, so a parked selection wins over the default —
        // it is the more specific instruction and it was given first.
        applyPendingSelection()
        emitChange()
    }

    public fun append(items: List<T>) {
        if (items.isEmpty()) return
        val from: Int = entries.size
        val wasEmpty: Boolean = entries.isEmpty()
        entries.addAll(items)
        bus.emit(key<MediaListAppend<T>>(EVENT_APPEND), MediaListAppend(items, from))
        if (wasEmpty) moveCursor(0)
        if (wasEmpty) applyPendingSelection()
        emitChange()
    }

    // The cursor shifts by however many arrived, so the same item stays current.
    public fun prepend(items: List<T>) {
        if (items.isEmpty()) return
        entries.addAll(0, items)
        if (cursor >= 0) cursor += items.size
        bus.emit(key<MediaListPrepend<T>>(EVENT_PREPEND), MediaListPrepend(items))
        if (cursor < 0) moveCursor(0) else notifyCurrent()
        emitChange()
    }

    public fun insert(items: List<T>, index: Int) {
        if (items.isEmpty()) return
        val at: Int = index.coerceIn(0, entries.size)
        entries.addAll(at, items)
        if (cursor >= at) {
            cursor += items.size
            notifyCurrent()
        }
        bus.emit(key<MediaListInsert<T>>(EVENT_INSERT), MediaListInsert(items, at))
        if (cursor < 0) moveCursor(0)
        emitChange()
    }

    public fun remove(id: String) {
        val index: Int = indexOf(id)
        if (index >= 0) removeAt(index)
    }

    public fun removeAt(index: Int) {
        val removed: T = entries.getOrNull(index) ?: return
        entries.removeAt(index)
        adjustCursorAfterRemoval(index)
        bus.emit(key<MediaListRemove<T>>(EVENT_REMOVE), MediaListRemove(removed.id, index, removed))
        emitChange()
    }

    public fun move(from: Int, to: Int) {
        if (from !in entries.indices) return
        val target: Int = to.coerceIn(0, entries.lastIndex)
        if (from == target) return
        val moved: T = entries.removeAt(from)
        entries.add(target, moved)
        adjustCursorAfterMove(from, target)
        bus.emit(key<MediaListMove>(EVENT_MOVE), MediaListMove(from, target))
        emitChange()
    }

    public fun clear() {
        val previousLength: Int = entries.size
        entries.clear()
        moveCursor(NO_CURSOR)
        bus.emit(key<MediaListClear>(EVENT_CLEAR), MediaListClear(previousLength))
        emitChange()
    }

    // The strategy writes the permutation; keeping the cursor on the playing
    // item afterwards is bookkeeping and belongs here, so a consumer swapping in
    // weighted shuffle does not have to reimplement it.
    public fun shuffle() {
        if (entries.isEmpty()) return
        val playingId: String? = current()?.id
        val ordered: List<T> = shuffleStrategy.order(entries.toList(), cursor)
        entries.clear()
        entries.addAll(ordered)
        moveCursor(playingId?.let { indexOf(it) } ?: defaultCursor())
        bus.emit(key<MediaListChange<T>>(EVENT_SHUFFLE), MediaListChange(get()))
        emitChange()
    }

    public fun sort(comparator: Comparator<T>) {
        if (entries.isEmpty()) return
        val playingId: String? = current()?.id
        entries.sortWith(comparator)
        moveCursor(playingId?.let { indexOf(it) } ?: defaultCursor())
        bus.emit(key<MediaListChange<T>>(EVENT_SORT), MediaListChange(get()))
        emitChange()
    }

    public fun replaceItem(id: String, item: T) {
        val index: Int = indexOf(id)
        if (index < 0) return
        entries[index] = item
        if (index == cursor) notifyCurrent()
        emitChange()
    }

    // A selection made before there is anything to select, kept until there is.
    //
    // Both setCurrent overloads returned silently on an empty list, so "start on
    // episode three" asked before the playlist seeded was DROPPED and playback
    // began at the first item. The reference parks it — `_pendingSelection`, ap-
    // plied by the setup pipeline right after the playlist seeds — and a host
    // that resolves its queue asynchronously hits this every time.
    private var pendingIndex: Int? = null
    private var pendingId: String? = null

    public fun setCurrent(index: Int) {
        if (entries.isEmpty()) {
            pendingIndex = index
            pendingId = null
            return
        }
        if (index !in entries.indices) return
        moveCursor(index)
    }

    public fun setCurrent(item: T): Unit = setCurrent(item.id)

    public fun setCurrent(id: String) {
        val index: Int = indexOf(id)
        if (index >= 0) {
            moveCursor(index)
            return
        }

        // Only while empty. An id that is simply not in a populated queue is a
        // caller's mistake, and parking it would apply it to whatever arrives
        // next — which is a worse answer than doing nothing.
        if (entries.isEmpty()) {
            pendingId = id
            pendingIndex = null
        }
    }

    // Applied once, on the first list that could satisfy it. Cleared either way,
    // so a selection cannot be re-applied over a later queue the caller never
    // asked it for.
    private fun applyPendingSelection() {
        val index: Int? = pendingId?.let { indexOf(it).takeIf { found -> found >= 0 } }
            ?: pendingIndex?.takeIf { it in entries.indices }

        pendingIndex = null
        pendingId = null

        if (index != null) moveCursor(index)
    }

    public fun setCurrent(predicate: (T) -> Boolean) {
        val index: Int = entries.indexOfFirst(predicate)
        if (index >= 0) moveCursor(index)
    }

    public fun onChange(fn: (MediaListChange<T>) -> Unit): Subscription = bus.on(key(EVENT_CHANGE), fn)
    public fun onAppend(fn: (MediaListAppend<T>) -> Unit): Subscription = bus.on(key(EVENT_APPEND), fn)
    public fun onPrepend(fn: (MediaListPrepend<T>) -> Unit): Subscription = bus.on(key(EVENT_PREPEND), fn)
    public fun onInsert(fn: (MediaListInsert<T>) -> Unit): Subscription = bus.on(key(EVENT_INSERT), fn)
    public fun onRemove(fn: (MediaListRemove<T>) -> Unit): Subscription = bus.on(key(EVENT_REMOVE), fn)
    public fun onMove(fn: (MediaListMove) -> Unit): Subscription = bus.on(key(EVENT_MOVE), fn)
    public fun onClear(fn: (MediaListClear) -> Unit): Subscription = bus.on(key(EVENT_CLEAR), fn)
    public fun onShuffle(fn: (MediaListChange<T>) -> Unit): Subscription = bus.on(key(EVENT_SHUFFLE), fn)
    public fun onSort(fn: (MediaListChange<T>) -> Unit): Subscription = bus.on(key(EVENT_SORT), fn)
    public fun onCurrent(fn: (MediaListCurrent<T>) -> Unit): Subscription = bus.on(key(EVENT_CURRENT), fn)

    public fun dispose() {
        entries.clear()
        cursor = NO_CURSOR
    }

    private fun <P> key(name: String): EventKey<P> = EventKey(name)

    private fun defaultCursor(): Int = if (entries.isEmpty()) NO_CURSOR else 0

    private fun moveCursor(next: Int) {
        if (cursor == next) return
        cursor = next
        notifyCurrent()
    }

    private fun notifyCurrent() {
        bus.emit(key<MediaListCurrent<T>>(EVENT_CURRENT), MediaListCurrent(current(), cursor))
    }

    private fun emitChange() {
        bus.emit(key<MediaListChange<T>>(EVENT_CHANGE), MediaListChange(get()))
    }

    // Removing above the cursor shifts it down so the same item stays current.
    // Removing the current item leaves the cursor where it was, which now points
    // at whatever moved up into the gap — clamped when the gap was at the end.
    private fun adjustCursorAfterRemoval(index: Int) {
        when {
            entries.isEmpty() -> moveCursor(NO_CURSOR)
            index < cursor -> moveCursor(cursor - 1)
            index == cursor -> moveCursor(cursor.coerceAtMost(entries.lastIndex))
            else -> Unit
        }
    }

    private fun adjustCursorAfterMove(from: Int, to: Int) {
        when {
            cursor == from -> moveCursor(to)
            from < cursor && to >= cursor -> moveCursor(cursor - 1)
            from > cursor && to <= cursor -> moveCursor(cursor + 1)
            else -> Unit
        }
    }
}
