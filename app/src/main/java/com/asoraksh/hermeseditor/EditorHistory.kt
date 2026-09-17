package com.asoraksh.hermeseditor

/**
 * Bounded undo/redo history with UTF-16 selection offsets.
 * Not thread-safe; all calls happen on the main thread.
 *
 * Coalescing policy: rapid adjacent single-character typing or backspace
 * merges into one step; paste, larger edits and non-adjacent changes start
 * a new entry. A new edit clears redo. Selection-only updates never create
 * an entry. Memory is bounded by MAX_ENTRIES and total stored characters.
 */
data class EditorSnapshot(val text: String, val start: Int = 0, val end: Int = start)

class EditorHistory(
    private val maxEntries: Int = 100,
    private val maxTotalChars: Int = 500_000
) {
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private var lastMillis = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun reset(snapshot: EditorSnapshot) {
        undoStack.clear(); redoStack.clear(); lastMillis = 0
    }

    fun record(before: EditorSnapshot, after: EditorSnapshot, nowMillis: Long) {
        if (before.text == after.text) return
        redoStack.clear()
        val adjacent = nowMillis - lastMillis <= 900 && undoStack.isNotEmpty() &&
            isAdjacentSingleChar(before, undoStack.last())
        if (adjacent) {
            // Keep the earliest text of the run, take the newest selection.
            val base = undoStack.removeLast()
            undoStack.addLast(EditorSnapshot(base.text, after.start, after.end))
        } else {
            pushBounded(before)
        }
        lastMillis = nowMillis
        trimTotal()
    }

    fun undo(current: EditorSnapshot): EditorSnapshot? {
        val target = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return target
    }

    fun redo(current: EditorSnapshot): EditorSnapshot? {
        val target = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return target
    }

    private fun undoStackLastBase(): EditorSnapshot = undoStack.lastOrNull() ?: EditorSnapshot("")

    private fun isAdjacentSingleChar(before: EditorSnapshot, base: EditorSnapshot): Boolean {
        val prev = base.text
        val next = before.text
        if (kotlin.math.abs(prev.length - next.length) != 1) return false
        val (short, long) = if (prev.length < next.length) prev to next else next to prev
        if (short != long.removeRange(pickRange(short, long))) return false
        return true
    }

    private fun pickRange(short: String, long: String): IntRange {
        var i = 0
        while (i < short.length && short[i] == long[i]) i++
        return i..i
    }

    private fun pushBounded(snapshot: EditorSnapshot) {
        undoStack.addLast(snapshot)
        while (undoStack.size > maxEntries) undoStack.removeFirst()
    }

    private fun trimTotal() {
        var total = undoStack.sumOf { it.text.length } + redoStack.sumOf { it.text.length }
        while (total > maxTotalChars && undoStack.size > 1) { total -= undoStack.removeFirst().text.length }
        while (total > maxTotalChars && redoStack.isNotEmpty()) { total -= redoStack.removeFirst().text.length }
    }
}

/** Non-overlapping case-insensitive matches in UTF-16 indices. Empty query -> empty list. */
fun findMatches(text: String, query: String): List<IntRange> {
    if (query.isEmpty() || text.isEmpty()) return emptyList()
    val result = mutableListOf<IntRange>()
    val lower = text.lowercase()
    val needle = query.lowercase()
    var index = 0
    while (index <= lower.length - needle.length) {
        if (lower.regionMatches(index, needle, 0, needle.length)) {
            result.add(index until (index + needle.length))
            index += needle.length
        } else index++
    }
    return result
}
