package com.asoraksh.hermeseditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
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
 *
 * Cold-start restore (in priority order):
 *  1. draft.txt exists -> unsaved edits on top of the last document, so restore
 *     the draft text AND the remembered uri/title. isDirty = true, so Save
 *     overwrites the right file.
 *  2. no draft but a remembered uri -> re-read that file on IO, isDirty = false.
 *  3. neither -> empty untitled document.
 * A remembered uri that no longer resolves (file deleted, grant expired) is
 * forgotten and the editor falls back cleanly to an empty untitled document.
 *
 * An incoming ACTION_VIEW intent is applied by the Activity through
 * setDocument(), which supersedes any restore still in flight: the restore
 * generation is bumped so a late-finishing read cannot overwrite the intent.
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

    /** Last documentSession the UI reset itself for; a recreated Activity must not reset again. */
    var surfaceSession = -1
    var directionCheckedSession = -1

    val history = EditorHistory()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var autosaveJob: Job? = null
    private var draftFile: File? = null
    private var restored = false
    private var restoreGeneration = 0
    private var store: SessionStore? = null

    /** Injected by the Activity; lets this class persist identity without a Context. */
    fun attachStore(sessionStore: SessionStore) {
        store = sessionStore
    }

    /**
     * Injected by the Activity on every create. The recovery file is attached
     * separately from [restoreSession] because a session opened from a share
     * intent never restores anything, yet still needs crash recovery.
     */
    fun attachDraft(recoveryFile: File) {
        draftFile = recoveryFile
    }

    fun snapshot(): EditorSnapshot = EditorSnapshot(editor.text, editor.selection.start, editor.selection.end)

    private fun of(value: TextFieldValue) =
        EditorSnapshot(value.text, value.selection.start, value.selection.end)

    /**
     * Called once from the Activity before the first composition. Restores the
     * previous session: the recovery draft when there is one, otherwise the
     * document the user last had open.
     *
     * [readUri] performs the actual stream read on IO and returns null when the
     * uri is gone or the grant expired. It is a function rather than a direct
     * contentResolver call so this class stays Context-free and unit-testable.
     */
    fun restoreSession(
        draftFile: File,
        legacyDraft: String?,
        lastUri: Uri?,
        lastTitle: String?,
        readUri: (Uri) -> String?,
    ) {
        // Attach before the idempotence guard: a session that already has a
        // document (rotation, or a share that arrived first) still needs the
        // recovery file for autosave.
        this.draftFile = draftFile
        if (restored) return
        restored = true
        val generation = ++restoreGeneration
        val epoch = draftEpoch

        // Both reads are IO: a large document used to be read from disk on the
        // main thread inside onCreate, which contradicts the threading contract
        // above and stalls the first frame.
        scope.launch {
            val draft = withContext(Dispatchers.IO) {
                val existing = if (draftFile.exists()) {
                    runCatching { draftFile.readText(Charsets.UTF_8) }.getOrNull()
                } else null
                // A zero-length draft.txt is a real draft (the user erased
                // everything). It is safe to trust because every save, new and
                // open retires the file via retireDraft(), and writes are atomic.
                existing
                    ?: legacyDraft?.takeIf { it.isNotEmpty() }?.also { legacy ->
                        // One-time migration so an upgrading user does not lose
                        // an active draft.
                        writeDraft(legacy, epoch)
                    }
            }
            if (generation != restoreGeneration) return@launch

            if (draft != null) {
                // Unsaved edits existed on top of the remembered document.
                if (lastUri != null && lastTitle != null) {
                    documentUri = lastUri
                    title = lastTitle
                }
                editor = TextFieldValue(draft)
                history.reset(snapshot())
                historyTick++
                documentSession++
                isDirty = true
                return@launch
            }

            if (lastUri == null || lastTitle == null) return@launch

            // No unsaved work: reopen the last document. Read on IO, apply on Main.
            val text = withContext(Dispatchers.IO) { runCatching { readUri(lastUri) }.getOrNull() }
            if (generation != restoreGeneration) return@launch
            if (text == null) {
                // Unreachable uri: forget it rather than leaving a dead document.
                forgetLastDocument()
                editor = TextFieldValue("")
                title = UNTOOLED_TXT
                documentUri = null
                history.reset(snapshot())
                historyTick++
                documentSession++
                isDirty = false
                return@launch
            }
            documentUri = lastUri
            title = lastTitle
            editor = TextFieldValue(text)
            history.reset(snapshot())
            historyTick++
            documentSession++
            isDirty = false
        }
    }

    /** Clears the remembered uri/title. New and Discard both call this. */
    fun forgetLastDocument() {
        store?.clearLastDocument()
    }

    fun setDocument(uri: Uri?, newTitle: String, text: String) {
        // An explicit open (user action or incoming ACTION_VIEW) wins over any
        // restore still in flight; without this a slow read of last_uri could
        // land afterwards and overwrite the document the user just opened.
        restoreGeneration++
        restored = true
        // The previous document's recovery draft must not outlive it, and the
        // remembered identity must follow the document, or a later draft would
        // be restored onto (and saved over) the wrong file.
        retireDraft()
        documentUri = uri
        title = newTitle
        rememberDocument()
        editor = TextFieldValue(text)
        history.reset(snapshot())
        historyTick++
        documentSession++
        forceSourceMode = false
        isDirty = false
    }

    fun renameTitle(newTitle: String) {
        if (title != newTitle) title = newTitle
        rememberDocument()
    }

    fun setDocumentUri(uri: Uri?) {
        documentUri = uri
        rememberDocument()
    }

    /** Persists which file is open; a draft is only ever paired with this identity. */
    private fun rememberDocument() {
        store?.let { s -> documentUri?.let { s.saveLastDocument(it, title) } }
    }

    fun markClean() {
        isDirty = false
        rememberDocument()
        // The document is now on disk, so the recovery copy is redundant. Leaving
        // it behind would make the next cold start reopen saved text as "unsaved".
        retireDraft()
    }

    fun newDocument(markdown: Boolean) {
        restoreGeneration++
        restored = true
        retireDraft()
        forgetLastDocument()
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
        val changed = newValue.text != editor.text
        history.record(snapshot(), of(newValue), android.os.SystemClock.uptimeMillis(), allowCoalescing = allowCoalescing)
        historyTick++
        editor = newValue
        // A cursor move or selection is not an edit.
        if (changed) afterEdit(newValue.text)
    }

    /** A blank, never-saved document has nothing to lose, so it is not dirty and keeps no draft. */
    private fun afterEdit(text: String) {
        isDirty = documentUri != null || text.isNotEmpty()
        if (isDirty) scheduleAutosave(text) else retireDraft()
    }

    fun undo(): Boolean {
        val target = history.undo(snapshot()) ?: return false
        editor = TextFieldValue(target.text, TextRange(target.start, target.end))
        historyTick++
        afterEdit(target.text)
        return true
    }

    fun redo(): Boolean {
        val target = history.redo(snapshot()) ?: return false
        editor = TextFieldValue(target.text, TextRange(target.start, target.end))
        historyTick++
        afterEdit(target.text)
        return true
    }

    fun breakCoalescing() = history.breakCoalescing()

    // --- Crash recovery -----------------------------------------------------
    // The draft file is recovery-only. Every save reads editor.text directly.

    // Every draft write and delete goes through draftLock, and retireDraft()
    // bumps draftEpoch, so a write queued before a save/new/open can never land
    // after that delete and resurrect old text.
    private val draftLock = Any()
    private var draftEpoch = 0

    private fun writeDraft(text: String, epoch: Int) {
        synchronized(draftLock) {
            val file = draftFile ?: return
            if (epoch != draftEpoch) return
            runCatching {
                // Temp file + rename: a process kill mid-write must not truncate the draft.
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text, Charsets.UTF_8)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    // shortcut: delete runs on the caller (main) thread, a tiny file op that can
    // wait briefly on an in-flight write; move it off-thread if drafts get huge.
    private fun retireDraft() {
        autosaveJob?.cancel()
        synchronized(draftLock) {
            draftEpoch++
            draftFile?.let { runCatching { it.delete() } }
        }
    }

    private fun scheduleAutosave(text: String) {
        autosaveJob?.cancel()
        if (!isDirty) return
        val epoch = draftEpoch
        autosaveJob = scope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            withContext(Dispatchers.IO) { writeDraft(text, epoch) }
        }
    }

    /**
     * Immediate, synchronous write for onPause and Back, so backgrounding or
     * exiting never loses keystrokes: a coroutine launched here could be
     * cancelled by onCleared() before it ran. No-op when clean: retireDraft()
     * removed the file, and rewriting it would make the next cold start report
     * an already-saved document as dirty.
     */
    fun flushAutosave() {
        autosaveJob?.cancel()
        if (!isDirty) return
        writeDraft(editor.text, draftEpoch)
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