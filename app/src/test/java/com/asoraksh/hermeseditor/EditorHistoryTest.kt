package com.asoraksh.hermeseditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorHistoryTest {

    @Test
    fun `rapid typing run coalesces into one undo step and redoes`() {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello", 5, 5)
        h.reset()
        var current = base
        var t = 1000L
        for (ch in " world") {
            val next = EditorSnapshot(current.text + ch, current.text.length + 1, current.text.length + 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 50
            current = next
        }
        assertEquals("Hello world", current.text)
        val undone = h.undo(current)!!
        assertEquals("Hello", undone.text)
        val redone = h.redo(undone)!!
        assertEquals("Hello world", redone.text)
        assertEquals(11, redone.start)
        assertEquals(11, redone.end)
    }

    @Test
    fun `rapid consecutive backspace run coalesces into one undo step`() {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello world", 11, 11)
        h.reset()
        var current = base
        var t = 1000L
        while (current.text != "Hello") {
            val next = EditorSnapshot(current.text.dropLast(1), current.text.length - 1, current.text.length - 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 50
            current = next
        }
        val undone = h.undo(current)!!
        assertEquals("Hello world", undone.text)
        val redone = h.redo(undone)!!
        assertEquals("Hello", redone.text)
    }

    @Test
    fun `pause longer than coalesce window splits undo steps`() {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello", 5, 5)
        h.reset()
        var current = base
        var t = 1000L
        for (ch in " wo") {
            val next = EditorSnapshot(current.text + ch, current.text.length + 1, current.text.length + 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 50
            current = next
        }
        t += 5000
        for (ch in "rld") {
            val next = EditorSnapshot(current.text + ch, current.text.length + 1, current.text.length + 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 50
            current = next
        }
        assertEquals("Hello world", current.text)
        val first = h.undo(current)!!
        assertEquals("Hello wo", first.text)
        val second = h.undo(first)!!
        assertEquals("Hello", second.text)
    }

    @Test
    fun `long rapid typing run undoes in one step`() {
        val h = EditorHistory()
        h.reset()
        var current = EditorSnapshot("Hello", 5, 5)
        var t = 1000L
        repeat(30) {
            val next = EditorSnapshot(current.text + "x", current.text.length + 1, current.text.length + 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 40
            current = next
        }
        assertEquals("Hello", h.undo(current)!!.text)
    }

    @Test
    fun `paste stays a separate undo step from adjacent typing`() {
        val h = EditorHistory()
        h.reset()
        var current = EditorSnapshot("Hello", 5, 5)
        var t = 1000L
        for (ch in " wo") {
            val next = EditorSnapshot(current.text + ch, current.text.length + 1, current.text.length + 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 50
            current = next
        }
        val pasted = EditorSnapshot("Hello wo pasted", 15, 15)
        h.record(EditorSnapshot(current.text, current.start, current.end), pasted, t + 50)
        assertEquals("Hello wo", h.undo(pasted)!!.text)
    }

    @Test
    fun `edit after undo clears redo stack and starts a fresh step`() {
        val h = EditorHistory()
        h.reset()
        var current = EditorSnapshot("Hello", 5, 5)
        var t = 1000L
        for (ch in " ab") {
            val next = EditorSnapshot(current.text + ch, current.text.length + 1, current.text.length + 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 50
            current = next
        }
        val undone = h.undo(current)!!
        assertEquals("Hello", undone.text)
        val newType = EditorSnapshot("Hello!", 6, 6)
        h.record(EditorSnapshot(undone.text, undone.start, undone.end), newType, t + 100)
        assertFalse(h.canRedo)
        assertEquals("Hello", h.undo(newType)!!.text)
    }

    @Test
    fun `selection-only update creates no entry and ends coalescing run`() {
        val h = EditorHistory()
        h.reset()
        val step1 = EditorSnapshot("Hello ", 6, 6)
        h.record(EditorSnapshot("Hello", 5, 5), step1, 1000)
        h.record(EditorSnapshot("Hello ", 3, 3), EditorSnapshot("Hello ", 4, 5), 1050)
        val nextType = EditorSnapshot("Hello w", 7, 7)
        h.record(EditorSnapshot("Hello ", 6, 6), nextType, 1100)
        val undone = h.undo(nextType)!!
        assertEquals("Hello ", undone.text)
        assertEquals(6, undone.start)
        assertEquals(6, undone.end)
        assertEquals(EditorSnapshot("Hello", 5, 5), h.undo(undone))
        assertFalse(h.canUndo)
    }

    @Test
    fun `bounded entries cap is enforced`() {
        val small = EditorHistory(maxEntries = 3, maxTotalChars = 400)
        small.reset()
        var current = EditorSnapshot("", 0, 0)
        var t = 0L
        for (i in 1..10) {
            val next = EditorSnapshot(current.text + "chunk$i".repeat(2), current.text.length, current.text.length)
            small.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 5000
            current = next
        }
        var steps = 0
        while (small.canUndo) {
            small.undo(current)
            steps++
        }
        assertTrue("bounded entries expected <= 3, was $steps", steps <= 3)
    }

    @Test
    fun `breakCoalescing splits typing run while retaining history`() {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello", 5, 5)
        val first = EditorSnapshot("Hello!", 6, 6)
        val next = EditorSnapshot("Hello!!", 7, 7)
        h.record(base, first, 1000)
        h.breakCoalescing()
        h.record(first, next, 1050)
        assertEquals(first, h.undo(next))
        assertEquals(base, h.undo(first))
    }

    @Test
    fun `single-character paste stays separate on both sides`() {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello", 5, 5)
        val typed = EditorSnapshot("Hello!", 6, 6)
        val pasted = EditorSnapshot("Hello!x", 7, 7)
        val next = EditorSnapshot("Hello!xy", 8, 8)
        h.record(base, typed, 1000)
        h.record(typed, pasted, 1050, allowCoalescing = false)
        h.record(pasted, next, 1100)
        assertEquals(pasted, h.undo(next))
        assertEquals(typed, h.undo(pasted))
        assertEquals(base, h.undo(typed))
    }

    @Test
    fun `redo and reset both end a coalescing run`() {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello", 5, 5)
        val first = EditorSnapshot("Hello!", 6, 6)
        h.record(base, first, 1000)
        h.undo(first)
        h.redo(base)
        val next = EditorSnapshot("Hello!!", 7, 7)
        h.record(first, next, 1050)
        assertEquals(first, h.undo(next))
        h.reset()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        h.record(base, first, 1100)
        assertEquals(base, h.undo(first))
    }

    @Test
    fun `findMatches finds non-overlapping case-insensitive UTF-16 ranges`() {
        assertTrue(findMatches("", "x").isEmpty())
        assertTrue(findMatches("abc", "").isEmpty())
        val m = findMatches("Abc abc ABc", "abc")
        assertEquals(listOf(0..2, 4..6, 8..10), m)
        assertEquals(2, findMatches("سلام دنیا سلام", "سلام").size)
        assertEquals(1, findMatches("aaa", "aa").size)
    }
}
