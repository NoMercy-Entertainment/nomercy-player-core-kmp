// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

import tv.nomercy.player.core.ports.ShuffleStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private data class TestItem(
    override val id: String,
    override val url: String = "https://example.test/$id",
    override val title: String? = null,
) : PlaylistItem

private fun items(vararg ids: String): List<TestItem> = ids.map { TestItem(it) }

private fun listOfItems(vararg ids: String): MediaList<TestItem> =
    MediaList<TestItem>().apply { set(items(*ids)) }

class MediaListTest {

    @Test
    fun aShuffleStrategyInstalledAfterTheListExistsIsTheOneThatOrders() {
        // The reason this exists: a smart shuffle is chosen by a consumer while
        // the player is already running, and the strategy used to be a private
        // constructor value. A generator nothing could install is a generator
        // nothing uses.
        var asked = 0
        val reversing = object : ShuffleStrategy {
            override fun <T : PlaylistItem> order(items: List<T>, currentIndex: Int): List<T> {
                asked += 1
                return items.reversed()
            }
        }

        val list = listOfItems("a", "b", "c", "d", "e", "f")
        list.setShuffleStrategy(reversing)
        list.shuffle()

        // Both, because either alone can pass by luck: the default Fisher-Yates
        // can produce a reversed six by chance, and a strategy that was asked
        // but whose answer was thrown away would still be counted.
        assertEquals(1, asked, "the installed strategy is the one that was asked")
        assertEquals(listOf("f", "e", "d", "c", "b", "a"), list.get().map { it.id })
    }

    @Test
    fun aSelectionMadeBeforeTheListSeedsIsAppliedWhenItDoes() {
        // "Start on episode three", asked before the playlist has resolved. Both
        // setCurrent overloads used to return silently on an empty list, so the
        // request was dropped and playback began at the first item.
        val byId = MediaList<TestItem>()
        byId.setCurrent("c")
        byId.set(items("a", "b", "c"))
        assertEquals("c", byId.current()?.id)

        val byIndex = MediaList<TestItem>()
        byIndex.setCurrent(1)
        byIndex.set(items("a", "b", "c"))
        assertEquals("b", byIndex.current()?.id)
    }

    @Test
    fun aParkedSelectionIsAppliedOnceAndNotOverALaterQueue() {
        val list = MediaList<TestItem>()
        list.setCurrent("c")
        list.set(items("a", "b", "c"))
        assertEquals("c", list.current()?.id)

        // A second queue the caller never selected into. Re-applying the old
        // request here would move the cursor for a reason nobody gave.
        list.set(items("x", "y", "c"))
        assertEquals("c", list.current()?.id, "the same id is kept because set() preserves what is playing")

        val fresh = MediaList<TestItem>()
        fresh.setCurrent("c")
        fresh.set(items("a", "b", "c"))
        fresh.set(items("p", "q"))
        assertEquals("p", fresh.current()?.id)
    }

    @Test
    fun aSelectionForAnItemAPopulatedListDoesNotHaveIsIgnored() {
        // Not parked: an unknown id in a queue that already exists is the
        // caller's mistake, and parking it would apply it to whatever arrives
        // next.
        val list = listOfItems("a", "b")
        list.setCurrent("zzz")
        assertEquals("a", list.current()?.id)

        list.set(items("zzz", "b"))
        assertEquals("zzz", list.current()?.id, "only because set() keeps the id that was playing, not from a park")
    }

    @Test
    fun anEmptyListHasNoCursorAndNothingCurrent() {
        val list = MediaList<TestItem>()

        assertEquals(-1, list.currentIndex())
        assertNull(list.current())
        assertEquals(0, list.length())
    }

    @Test
    fun settingContentsSeedsTheCursorAtTheFirstItem() {
        val list = MediaList<TestItem>()
        var changes = 0
        list.onChange { changes++ }

        list.set(items("a", "b", "c"))

        assertEquals(0, list.currentIndex())
        assertEquals("a", list.current()?.id)
        assertEquals(1, changes)
    }

    @Test
    fun replacingTheContentsKeepsThePlayingItemPlaying() {
        val list = listOfItems("a", "b", "c")
        list.setCurrent(2)

        list.set(items("x", "b", "c"))

        // A queue re-fetched from the server must not restart the track the
        // viewer is listening to.
        assertEquals(2, list.currentIndex())
        assertEquals("c", list.current()?.id)
    }

    @Test
    fun replacingTheContentsRestartsWhenThePlayingItemIsGone() {
        val list = listOfItems("a", "b")
        list.setCurrent(1)

        list.set(items("x", "y"))

        assertEquals(0, list.currentIndex())
        assertEquals("x", list.current()?.id)
    }

    @Test
    fun appendingToAnEmptyListStartsItAndReportsWhereItStarted() {
        val list = MediaList<TestItem>()
        val sequence = mutableListOf<String>()
        list.onAppend { sequence += "append:${it.from}" }
        list.onChange { sequence += "change" }

        list.append(items("a", "b"))

        assertEquals(0, list.currentIndex())
        assertEquals(listOf("append:0", "change"), sequence)
    }

    @Test
    fun appendingToANonEmptyListLeavesTheCursorAlone() {
        val list = listOfItems("a", "b")
        list.setCurrent(1)

        list.append(items("c"))

        assertEquals(1, list.currentIndex())
        assertEquals("b", list.current()?.id)
    }

    @Test
    fun prependingShiftsTheCursorSoTheSameItemStaysCurrent() {
        val list = listOfItems("a", "b")
        list.setCurrent(1)

        list.prepend(items("x", "y"))

        assertEquals(3, list.currentIndex())
        assertEquals("b", list.current()?.id)
    }

    @Test
    fun insertingAboveTheCursorShiftsItAndInsertingBelowDoesNot() {
        val above = listOfItems("a", "b", "c")
        above.setCurrent(2)
        above.insert(items("z"), 1)
        assertEquals(3, above.currentIndex())
        assertEquals("c", above.current()?.id)

        val below = listOfItems("a", "b", "c")
        below.setCurrent(0)
        below.insert(items("z"), 2)
        assertEquals(0, below.currentIndex())
        assertEquals("a", below.current()?.id)
    }

    @Test
    fun insertingExactlyAtTheCursorPushesTheCurrentItemDownWithIt() {
        val list = listOfItems("a", "b", "c")
        list.setCurrent(1)

        list.insert(items("z"), 1)

        // The boundary the off-by-one lives on: the current item moved down, so
        // the cursor has to move with it or the player switches tracks.
        assertEquals(2, list.currentIndex())
        assertEquals("b", list.current()?.id)
    }

    @Test
    fun removingAboveTheCursorShiftsItDown() {
        val list = listOfItems("a", "b", "c")
        list.setCurrent(2)

        list.removeAt(0)

        assertEquals(1, list.currentIndex())
        assertEquals("c", list.current()?.id)
    }

    @Test
    fun removingTheCurrentItemLandsOnWhateverTookItsPlace() {
        val middle = listOfItems("a", "b", "c")
        middle.setCurrent(1)
        middle.removeAt(1)
        assertEquals(1, middle.currentIndex())
        assertEquals("c", middle.current()?.id)

        // At the end there is nothing to move up, so the cursor clamps back.
        val last = listOfItems("a", "b", "c")
        last.setCurrent(2)
        last.removeAt(2)
        assertEquals(1, last.currentIndex())
        assertEquals("b", last.current()?.id)
    }

    @Test
    fun removingTheOnlyItemLeavesNoCursor() {
        val list = listOfItems("a")

        list.removeAt(0)

        assertEquals(-1, list.currentIndex())
        assertNull(list.current())
    }

    @Test
    fun removeByIdReportsWhatWasRemovedAndWhereItWas() {
        val list = listOfItems("a", "b", "c")
        var payload: MediaListRemove<TestItem>? = null
        list.onRemove { payload = it }

        list.remove("b")

        // By the time a listener runs the item is gone from the list, so the
        // payload has to carry both.
        assertEquals(1, payload?.index)
        assertEquals("b", payload?.item?.id)
        assertEquals(2, list.length())
    }

    @Test
    fun movingTheCurrentItemTakesTheCursorWithIt() {
        val list = listOfItems("a", "b", "c")
        list.setCurrent(0)

        list.move(0, 2)

        assertEquals(2, list.currentIndex())
        assertEquals("a", list.current()?.id)
    }

    @Test
    fun movingAnotherItemPastTheCursorShiftsIt() {
        val downward = listOfItems("a", "b", "c")
        downward.setCurrent(1)
        downward.move(0, 2)
        assertEquals(0, downward.currentIndex())
        assertEquals("b", downward.current()?.id)

        val upward = listOfItems("a", "b", "c")
        upward.setCurrent(1)
        upward.move(2, 0)
        assertEquals(2, upward.currentIndex())
        assertEquals("b", upward.current()?.id)
    }

    @Test
    fun theCursorCanBeSetByItemIdIndexOrPredicate() {
        val source = items("a", "b", "c")
        val list = MediaList<TestItem>()
        list.set(source)

        list.setCurrent(source[1])
        assertEquals(1, list.currentIndex())

        list.setCurrent("c")
        assertEquals(2, list.currentIndex())

        list.setCurrent(0)
        assertEquals(0, list.currentIndex())

        list.setCurrent { it.id == "b" }
        assertEquals(1, list.currentIndex())
    }

    @Test
    fun anOutOfRangeOrUnknownCursorTargetIsIgnored() {
        val list = listOfItems("a", "b")
        list.setCurrent(0)

        list.setCurrent(9)
        list.setCurrent("nope")
        list.setCurrent { false }

        assertEquals(0, list.currentIndex())
    }

    @Test
    fun peekingLooksEitherWayWithoutMovingTheCursor() {
        val list = listOfItems("a", "b", "c")
        list.setCurrent(1)

        assertEquals("c", list.peekNext()?.id)
        assertEquals("a", list.peekPrevious()?.id)
        assertEquals(1, list.currentIndex())

        list.setCurrent(2)
        assertNull(list.peekNext())
        list.setCurrent(0)
        assertNull(list.peekPrevious())
    }

    @Test
    fun shufflingKeepsThePlayingItemPlaying() {
        val list = listOfItems("a", "b", "c", "d", "e")
        list.setCurrent(2)
        var shuffles = 0
        list.onShuffle { shuffles++ }

        list.shuffle()

        assertEquals("c", list.current()?.id)
        assertEquals(1, shuffles)
        assertEquals(5, list.length())
    }

    @Test
    fun sortingKeepsThePlayingItemPlaying() {
        val list = listOfItems("c", "a", "b")
        list.setCurrent(0)

        list.sort(compareBy { it.id })

        assertEquals("c", list.current()?.id)
        assertEquals(2, list.currentIndex())
        assertEquals(listOf("a", "b", "c"), list.get().map { it.id })
    }

    @Test
    fun clearingResetsTheCursorAndReportsWhatWasLost() {
        val list = listOfItems("a", "b")
        var previousLength = -1
        list.onClear { previousLength = it.previousLength }

        list.clear()

        assertEquals(-1, list.currentIndex())
        assertEquals(2, previousLength)
        assertEquals(0, list.length())
    }

    @Test
    fun theCurrentEventFiresWheneverTheCursorMovesHoweverItMoved() {
        val list = listOfItems("a", "b", "c")
        val seen = mutableListOf<Int>()
        list.onCurrent { seen += it.index }

        list.setCurrent(2)
        list.prepend(items("x"))
        list.clear()

        // Moved by a caller, carried by a reorder, and dropped by a clear.
        assertEquals(listOf(2, 3, -1), seen)
    }

    @Test
    fun replacingAnItemInPlaceKeepsTheCursorAndUpdatesTheContent() {
        val list = listOfItems("a", "b")
        list.setCurrent(1)

        list.replaceItem("b", TestItem("b", title = "renamed"))

        assertEquals(1, list.currentIndex())
        assertEquals("renamed", list.current()?.title)
    }
}
