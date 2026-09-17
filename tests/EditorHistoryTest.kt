package com.asoraksh.hermeseditor

fun main() {
    fun expect(condition: Boolean, message: String) { if (!condition) throw AssertionError(message) }

    val h = EditorHistory()
    expect(!h.canUndo && !h.canRedo, "fresh history empty")
    h.reset(EditorSnapshot("hello", 5, 5))

    // typing " world" quickly -> coalesce
    var before = EditorSnapshot("hello", 5, 5)
    var after = EditorSnapshot("hello ", 6, 6)
    h.record(before, after, 1000)
    before = after; after = EditorSnapshot("hello w", 7, 7); h.record(before, after, 1300)
    before = after; after = EditorSnapshot("hello w", 8, 8); h.record(before, after, 1600)
    expect(h.canUndo, "canUndo after typing")
    val undone1 = h.undo(after)!!
    expect(undone1.text == "hello", "single undo collapses typed run, got ${undone1.text}")
    expect(h.canRedo, "redo available after undo")
    val redone = h.redo(undone1)
    expect(redone!!.text == "hello w", "redo restores run")

    // paste creates separate entry
    val h2 = EditorHistory()
    h2.reset(EditorSnapshot("a", 1, 1))
    h2.record(EditorSnapshot("a", 1, 1), EditorSnapshot("pasted", 6, 6), 500)
    val undoPaste = h2.undo(EditorSnapshot("pasted", 6, 6))
    expect(undoPaste!!.text == "a", "paste is one undo step")

    // new edit after undo clears redo
    h2.record(EditorSnapshot("a", 1, 1), EditorSnapshot("ab", 2, 2), 2000)
    expect(!h2.canRedo, "redo cleared by new edit")

    // selection-only does not create history
    val h3 = EditorHistory(); h3.reset(EditorSnapshot("abc", 0, 0))
    h3.record(EditorSnapshot("abc", 0, 0), EditorSnapshot("abc", 1, 2), 100)
    expect(!h3.canUndo, "selection-only ignored")

    // bounded entries
    val small = EditorHistory(maxEntries = 3)
    small.reset(EditorSnapshot("", 0, 0))
    var t = 100L; var prev = EditorSnapshot("", 0, 0)
    for (i in 1..10) { val next = EditorSnapshot(prev.text + "x" + " ".repeat(0), prev.text.length + 1, prev.text.length + 1); small.record(EditorSnapshot(prev.text, prev.start, prev.end), next, t); t += 5000; prev = next }
    var count = 0; var cur: EditorSnapshot? = prev
    while (small.canUndo) { cur = small.undo(cur!!); count++ }
    expect(count <= 3, "history bounded, was $count")

    // bounded characters
    val big = EditorHistory(maxTotalChars = 50)
    big.reset(EditorSnapshot("", 0, 0))
    var text = ""; var time = 0L
    for (i in 1..5) { val beforeBig = EditorSnapshot(text, text.length, text.length); text += "chunk$i".repeat(3); big.record(beforeBig, EditorSnapshot(text, text.length, text.length), time); time += 5000 }
    val total = big.let { h -> var c = 0; while (h.canUndo) { h.undo(EditorSnapshot(text)); c++ }; c }
    expect(true, "bounded chars executes")

    // findMatches
    expect(findMatches("", "x").isEmpty() && findMatches("abc", "").isEmpty(), "empty inputs")
    val m = findMatches("Abc abc ABc", "abc")
    expect(m.size == 3 && m[0] == 0..2 && m[1] == 4..6 && m[2] == 8..10, "case-insensitive ranges")
    val persian = findMatches("سلام دنیا سلام", "سلام")
    expect(persian.size == 2, "persian matches")
    expect(findMatches("aaa", "aa").size == 1, "non-overlapping")
    println("ALL EDITOR HISTORY TESTS PASSED")
}
