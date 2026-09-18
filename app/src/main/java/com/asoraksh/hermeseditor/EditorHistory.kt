package com.asoraksh.hermeseditor

/**
 * Bounded undo/redo history with UTF-16 selection offsets.
 * Not thread-safe; all calls happen on the main thread.
 *
 * Coalescing policy: rapid adjacent single-character typing or backspace of
 * the same direction merges into one step. The comparison uses the most
 * recent POST-EDIT snapshot (tracked separately), never the run's earliest
 * stack entry, so runs of any length coalesce correctly. The undo stack keeps
 * the earliest snapshot of the run, so one Undo reverts the whole run.
 * Undo, Redo, reset (open/new/Save As), paste, a multi-character edit, a
 * selection-only update, or a pause longer than COALESCE_MILLIS reset the
 * run. A new edit clears redo. Memory is bounded by maxEntries/maxTotalChars.
 */
data class EditorSnapshot(val text: String, val start: Int = 0, val end: Int = start)

class EditorHistory(
    private val maxEntries: Int = 100,
    private val maxTotalChars: Int = 500_000
) {
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private var lastPostEdit: EditorSnapshot? = null
    private var lastChangeWasInsert = false
    private var lastChangeSingle = false
    private var lastMillis = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun breakCoalescing() {
        lastPostEdit = null
        lastChangeSingle = false
        lastMillis = 0
    }

    fun reset(snapshot: EditorSnapshot) {
        undoStack.clear(); redoStack.clear()
        breakCoalescing()
    }

    fun record(before: EditorSnapshot, after: EditorSnapshot, nowMillis: Long, allowCoalescing: Boolean = true) {
        if (before.text == after.text) {
            // Selection-only: never an entry, and it ends any coalescing run.
            lastPostEdit = null; lastMillis = 0
            return
        }
        redoStack.clear()
        val last = lastPostEdit
        val insert = after.text.length > before.text.length
        val single = allowCoalescing && isSingleCharEdit(before, after)
        val coalesce = last != null && before == last && undoStack.isNotEmpty() &&
            lastChangeSingle && single && lastChangeWasInsert == insert &&
            nowMillis - lastMillis in 0..COALESCE_MILLIS
        if (!coalesce) pushBounded(before)
        lastPostEdit = after
        lastChangeWasInsert = insert
        lastChangeSingle = single
        lastMillis = nowMillis
        trimTotal()
    }

    fun undo(current: EditorSnapshot): EditorSnapshot? {
        val target = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        lastPostEdit = null; lastMillis = 0
        return target
    }

    fun redo(current: EditorSnapshot): EditorSnapshot? {
        val target = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        lastPostEdit = null; lastMillis = 0
        return target
    }

    /** A collapsed-cursor insertion or Backspace at the current cursor. */
    private fun isSingleCharEdit(before: EditorSnapshot, after: EditorSnapshot): Boolean {
        if (before.start != before.end || after.start != after.end) return false
        val cursor = before.start
        if (cursor !in 0..before.text.length) return false
        return when (after.text.length - before.text.length) {
            1 -> after.start == cursor + 1 &&
                after.text.removeRange(cursor, cursor + 1) == before.text
            -1 -> cursor > 0 && after.start == cursor - 1 &&
                before.text.removeRange(cursor - 1, cursor) == after.text
            else -> false
        }
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

    companion object { private const val COALESCE_MILLIS = 900L }
}

/** Non-overlapping case-insensitive matches in UTF-16 indices. Empty query -> empty list. */
fun findMatches(text: String, query: String): List<IntRange> {
    if (query.isEmpty() || text.isEmpty()) return emptyList()
    val result = mutableListOf<IntRange>()
    // Search the original string: lowercasing can expand Unicode characters
    // and shift every subsequent selection/highlight offset.
    var index = 0
    while (index <= text.length - query.length) {
        val found = text.indexOf(query, startIndex = index, ignoreCase = true)
        if (found < 0) break
        result.add(found until (found + query.length))
        index = found + query.length
    }
    return result
}
