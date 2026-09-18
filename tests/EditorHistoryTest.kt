package com.asoraksh.hermeseditor

fun main() {
    fun expect(condition: Boolean, message: String) { if (!condition) throw AssertionError(message) }

    // 1-4: rapid typing run coalesces; one Undo -> "Hello", one Redo -> "Hello world"
    run {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello", 5, 5)
        h.reset(base)
        var current = base
        var t = 1000L
        for (ch in " world") {
            val next = EditorSnapshot(current.text + ch, current.text.length + 1, current.text.length + 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 50
            current = next
        }
        expect(current.text == "Hello world", "typing built Hello world")
        val undone = h.undo(current)!!
        expect(undone.text == "Hello", "one Undo returns to Hello, got '${undone.text}'")
        val redone = h.redo(undone)!!
        expect(redone.text == "Hello world", "one Redo returns to Hello world, got '${redone.text}'")
        expect(redone.start == 11 && redone.end == 11, "selection restored to end")
    }

    // 5: rapid consecutive backspace run coalesces; one Undo -> "Hello"
    run {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello world", 11, 11)
        h.reset(base)
        var current = base
        var t = 1000L
        while (current.text != "Hello") {
            val next = EditorSnapshot(current.text.dropLast(1), current.text.length - 1, current.text.length - 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 50
            current = next
        }
        val undone = h.undo(current)!!
        expect(undone.text == "Hello world", "Undo restores all deleted characters, got '${undone.text}'")
        val redone = h.redo(undone)!!
        expect(redone.text == "Hello", "Redo reapplies all deletions, got '${redone.text}'")
    }

    // 6: pause creates a separate Undo step
    run {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello", 5, 5)
        h.reset(base)
        var current = base
        var t = 1000L
        for (ch in " wo") {
            val next = EditorSnapshot(current.text + ch, current.text.length + 1, current.text.length + 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 50
            current = next
        }
        t += 5000 // pause longer than COALESCE_MILLIS
        for (ch in "rld") {
            val next = EditorSnapshot(current.text + ch, current.text.length + 1, current.text.length + 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 50
            current = next
        }
        expect(current.text == "Hello world", "built Hello world with pause")
        val first = h.undo(current)!!
        expect(first.text == "Hello wo", "pause split run: first undo -> 'Hello w', got '${first.text}'")
        val second = h.undo(first)!!
        expect(second.text == "Hello", "second undo -> Hello, got '${second.text}'")
    }

    // Long run (30 chars): the reported bug scenario — must stay ONE step
    run {
        val h = EditorHistory()
        h.reset(EditorSnapshot("Hello", 5, 5))
        var current = EditorSnapshot("Hello", 5, 5)
        var t = 1000L
        repeat(30) {
            val next = EditorSnapshot(current.text + "x", current.text.length + 1, current.text.length + 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 40
            current = next
        }
        val undone = h.undo(current)!!
        expect(undone.text == "Hello", "30-char rapid run undoes in ONE step, got '${undone.text}'")
    }

    // Paste (multi-char) is its own step and must not merge into adjacent typing
    run {
        val h = EditorHistory()
        h.reset(EditorSnapshot("Hello", 5, 5))
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
        val undone = h.undo(pasted)!!
        expect(undone.text == "Hello wo", "paste undoes alone, got '${undone.text}'")
    }

    // Undo/redo reset the run; new edit after undo is a fresh step, redo cleared
    run {
        val h = EditorHistory()
        h.reset(EditorSnapshot("Hello", 5, 5))
        var current = EditorSnapshot("Hello", 5, 5)
        var t = 1000L
        for (ch in " ab") {
            val next = EditorSnapshot(current.text + ch, current.text.length + 1, current.text.length + 1)
            h.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 50
            current = next
        }
        val undone = h.undo(current)!!
        expect(undone.text == "Hello", "run undone")
        val newType = EditorSnapshot("Hello!", 6, 6)
        h.record(EditorSnapshot(undone.text, undone.start, undone.end), newType, t + 100)
        expect(!h.canRedo, "new edit after undo clears redo")
        val secondUndo = h.undo(newType)!!
        expect(secondUndo.text == "Hello", "edit after undo starts fresh step, got '${secondUndo.text}'")
    }

    // Selection-only update never creates an entry and ends the coalescing run
    run {
        val h = EditorHistory()
        h.reset(EditorSnapshot("Hello", 5, 5))
        val step1 = EditorSnapshot("Hello ", 6, 6)
        h.record(EditorSnapshot("Hello", 5, 5), step1, 1000)
        // Cursor move: text unchanged
        h.record(EditorSnapshot("Hello ", 3, 3), EditorSnapshot("Hello ", 4, 5), 1050)
        val nextType = EditorSnapshot("Hello w", 7, 7)
        h.record(EditorSnapshot("Hello ", 6, 6), nextType, 1100)
        val undone = h.undo(nextType)!!
        expect(undone.text == "Hello ", "selection-only broke the run (separate step), got '${undone.text}'")
        expect(undone.start == 6 && undone.end == 6, "selection restored from snapshot")
        expect(h.undo(undone) == EditorSnapshot("Hello", 5, 5), "original typing step preserved")
        expect(!h.canUndo, "selection-only itself created no entry")
    }

    // Bounded entries and characters still enforced
    run {
        val small = EditorHistory(maxEntries = 3, maxTotalChars = 400)
        small.reset(EditorSnapshot("", 0, 0))
        var current = EditorSnapshot("", 0, 0)
        var t = 0L
        for (i in 1..10) {
            val next = EditorSnapshot(current.text + "chunk$i".repeat(2), current.text.length, current.text.length)
            small.record(EditorSnapshot(current.text, current.start, current.end), next, t)
            t += 5000 // pause: each chunk is its own entry
            current = next
        }
        var steps = 0
        while (small.canUndo) { small.undo(current); steps++ }
        expect(steps <= 3, "bounded entries, was $steps")
    }

    // Save As ends the run but retains the earliest undo snapshot.
    run {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello", 5, 5)
        val first = EditorSnapshot("Hello!", 6, 6)
        val next = EditorSnapshot("Hello!!", 7, 7)
        h.record(base, first, 1000)
        h.breakCoalescing()
        h.record(first, next, 1050)
        expect(h.undo(next) == first, "Save As splits typing run")
        expect(h.undo(first) == base, "Save As retains history")
    }
    // Even a one-character clipboard paste is a boundary on both sides.
    run {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello", 5, 5)
        val typed = EditorSnapshot("Hello!", 6, 6)
        val pasted = EditorSnapshot("Hello!x", 7, 7)
        val next = EditorSnapshot("Hello!xy", 8, 8)
        h.record(base, typed, 1000)
        h.record(typed, pasted, 1050, allowCoalescing = false)
        h.record(pasted, next, 1100)
        expect(h.undo(next) == pasted, "typing after paste separate")
        expect(h.undo(pasted) == typed, "single-character paste separate")
        expect(h.undo(typed) == base, "typing before paste preserved")
    }
    // Redo and document reset both end a run.
    run {
        val h = EditorHistory()
        val base = EditorSnapshot("Hello", 5, 5)
        val first = EditorSnapshot("Hello!", 6, 6)
        h.record(base, first, 1000)
        h.undo(first)
        h.redo(base)
        val next = EditorSnapshot("Hello!!", 7, 7)
        h.record(first, next, 1050)
        expect(h.undo(next) == first, "Redo ends previous run")
        h.reset(base)
        expect(!h.canUndo && !h.canRedo, "new document clears old history")
        h.record(base, first, 1100)
        expect(h.undo(first) == base, "new document run has its own base")
    }

    // findMatches (offset preservation)
    expect(findMatches("", "x").isEmpty() && findMatches("abc", "").isEmpty(), "empty inputs")
    val m = findMatches("Abc abc ABc", "abc")
    expect(m.size == 3 && m[0] == 0..2 && m[1] == 4..6 && m[2] == 8..10, "case-insensitive ranges")
    val persian = findMatches("سلام دنیا سلام", "سلام")
    expect(persian.size == 2, "persian matches")
    expect(findMatches("aaa", "aa").size == 1, "non-overlapping")
    println("ALL UNDO/REDO REGRESSION TESTS PASSED")
}
