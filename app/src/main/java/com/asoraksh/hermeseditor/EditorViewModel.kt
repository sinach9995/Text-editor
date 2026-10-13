package com.asoraksh.hermeseditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single source of truth for the open document.
 *
 * Everything the Activity-level result callbacks need (uri, title, text, dirty
 * flag) and everything the Compose editor needs (TextFieldValue, undo/redo
 * history) live here together, so an Activity recreation -- rotation, or
 * recreate() on a language switch -- keeps both sides in sync without the old
 * updateEditor/pendingDocument callback bridge.
 *
 * Threading contract:
 *  - All state mutation happens on the main thread. EditorHistory is not
 *    thread-safe (raw ArrayDeque), so record/reset/undo/redo stay on Main.
 *  - Only stream reads/writes and the draft file write hop to Dispatchers.IO.
 *    Text is snapshotted on Main before being handed to an IO coroutine.
 */
class EditorViewModel : ViewModel() {

    var documentUri: Uri? = null
        private set

    var title: String by mutableStateOf(UNTOOLED_TXT)
        private set

    var editor: TextFieldValue by mutableStateOf(TextFieldValue(""))
        private set

    var isDirty: Boolean by mutableStateOf(false)
        private set

    /** Bumped after any history mutation so Compose re-reads canUndo/canRedo. */
    var historyTick: Int by mutableStateOf(0)
        private set

    /**
     * Bumped on every document swap. Keyed on (title, uri) alone would miss a
     * re-open of the file already open, which still needs a full surface reset.
     */
    var documentSession: Int by mutableStateOf(0)
        private set

    /**
     * One-shot hint for the session reset. A newly created file always opens in
     * source mode even when it is Markdown; an opened file derives preview mode
     * from its extension.
     */
    var forceSourceMode: Boolean by mutableStateOf(false)
        private set

    val history = EditorHistory()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var autosaveJob: Job? = null
    private var draftFile: File? = null

    fun snapshot(): EditorSnapshot = EditorSnapshot(editor.text, editor.selection.start, editor.selection.end)

    private fun of(value: TextFieldValue) =
        EditorSnapshot(value.text, value.selection.start, value.selection.end)

    /**
     * Called once from the Activity before the first composition. Restores a
     * previous session from filesDir, or migrates a legacy SharedPreferences
     * draft written by an older build.
     */
    fun configureDraftFile(file: File, legacyDraft: String?) {
        if (draftFile != null) return
        draftFile = file
        val restored = if (file.exists()) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            // One-time migration so an upgrading user does not lose an active draft.
            legacyDraft?.takeIf { it.isNotEmpty() }?.also { legacy ->
                runCatching { file.writeText(legacy, Charsets.UTF_8) }
            }
        }
        if (restored != null) {
            editor = TextFieldValue(restored)
            history.reset(snapshot())
            historyTick++
            isDirty = true
        }
    }

    fun setDocument(uri: Uri?, newTitle: String, text: String) {
        documentUri = uri
        title = newTitle
        editor = TextFieldValue(text)
        history.reset(snapshot())
        historyTick++
        documentSession++
        forceSourceMode = false
        isDirty = false
    }

    fun setTitle(newTitle: String) {
        if (title != newTitle) title = newTitle
    }

    fun setDocumentUri(uri: Uri?) {
        documentUri = uri
    }

    fun markClean() {
        isDirty = false
        // The document is now on disk, so the recovery copy is redundant. Leaving
        // it behind would make the next cold start reopen saved text as "unsaved".
        autosaveJob?.cancel()
        val file = draftFile
        scope.launch { withContext(Dispatchers.IO) { file?.let { runCatching { it.delete() } } } }
    }

    fun newDocument(markdown: Boolean) {
        documentUri = null
        title = if (markdown) UNTOOLED_MD else UNTOOLED_TXT
        editor = TextFieldValue("")
        history.reset(snapshot())
        historyTick++
        documentSession++
        forceSourceMode = true
        isDirty = false
    }

    fun onTextChanged(newValue: TextFieldValue, allowCoalescing: Boolean) {
        history.record(snapshot(), of(newValue), android.os.SystemClock.uptimeMillis(), allowCoalescing = allowCoalescing)
        historyTick++
        editor = newValue
        if (!isDirty) isDirty = true
        scheduleAutosave(newValue.text)
    }

    fun undo(): Boolean {
        val target = history.undo(snapshot()) ?: return false
        editor = TextFieldValue(target.text, TextRange(target.start, target.end))
        historyTick++
        isDirty = true
        scheduleAutosave(target.text)
        return true
    }

    fun redo(): Boolean {
        val target = history.redo(snapshot()) ?: return false
        editor = TextFieldValue(target.text, TextRange(target.start, target.end))
        historyTick++
        isDirty = true
        scheduleAutosave(target.text)
        return true
    }

    fun breakCoalescing() = history.breakCoalescing()

    // --- Crash recovery -----------------------------------------------------
    // The draft file is recovery-only. Every save reads editor.text directly.

    private fun scheduleAutosave(text: String) {
        autosaveJob?.cancel()
        if (!isDirty) return
        autosaveJob = scope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            withContext(Dispatchers.IO) { draftFile?.let { runCatching { it.writeText(text, Charsets.UTF_8) } } }
        }
    }

    /**
     * Immediate write for onPause/onStop, so backgrounding never loses keystrokes.
     * No-op when clean: markClean() retires the draft file, and rewriting it here
     * would make the next cold start report an already-saved document as dirty.
     */
    fun flushAutosave() {
        autosaveJob?.cancel()
        if (!isDirty) return
        val text = editor.text
        scope.launch { withContext(Dispatchers.IO) { draftFile?.let { runCatching { it.writeText(text, Charsets.UTF_8) } } } }
    }

    override fun onCleared() {
        autosaveJob?.cancel()
        scope.cancel()
        super.onCleared()
    }

    companion object {
        const val UNTOOLED_TXT = "untitled.txt"
        const val UNTOOLED_MD = "untitled.md"
        private const val AUTOSAVE_DEBOUNCE_MS = 500L
    }
}