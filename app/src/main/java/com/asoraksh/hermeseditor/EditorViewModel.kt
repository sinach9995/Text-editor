package com.asoraksh.hermeseditor

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
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
 * One open document plus everything the editor surface needs when it becomes
 * the active tab.
 *
 * The observable fields are Compose state; the ViewModel exposes them through
 * delegating properties, so switching tabs recomposes exactly the composables
 * that opening a different file used to recompose in v1.5.
 */
class TabState(val id: String) {

    var uri: Uri? by mutableStateOf(null)
    var title: String by mutableStateOf(EditorViewModel.UNTOOLED_TXT)
    var editor: TextFieldValue by mutableStateOf(TextFieldValue(""))
    var isDirty: Boolean by mutableStateOf(false)
    var mdPreview: Boolean by mutableStateOf(false)
    var docRtl: Boolean by mutableStateOf(false)

    /**
     * Per-tab history. v1.5 kept 100 entries / 500k chars for one document;
     * ten of those would be ~10 MB of full-text snapshots, so each tab is
     * capped lower. Honest limit: every entry is a copy of the whole text, so a
     * multi-MB document can only keep a single undo step.
     */
    val history = EditorHistory(maxEntries = 30, maxTotalChars = 120_000)

    var scrollY: Int = 0
    var charCount: Int = 0

    /** Text is in memory for this tab (false = stub rebuilt from the manifest). */
    var hydrated: Boolean = false

    /** Opened or created in this run, so the preview default may be applied once. */
    var fresh: Boolean = false

    /** True while an IO read of this tab is in flight, so a second switch does not start another. */
    var loading: Boolean = false

    /** Bumped by setDocument so a hydrate that started earlier cannot land on top of it. */
    var loadToken: Int = 0

    var surfaceSession: Int = -1
    var directionCheckedSession: Int = -1
    var forceSourceMode: Boolean = false

    /** Bumped before deleting this tab's draft, so a queued write cannot resurrect it. */
    var draftEpoch: Int = 0
    var autosaveJob: Job? = null

    fun record(): TabRecord = TabRecord(
        id = id,
        uri = uri?.toString(),
        title = title,
        isDirty = isDirty,
        charCount = charCount,
        mdPreview = mdPreview,
        docRtl = docRtl,
        scrollY = scrollY,
    )
}

/**
 * Multi-tab source of truth as of v1.6.
 *
 * The v1.5 public surface (documentUri / title / editor / isDirty / history /
 * documentSession / surfaceSession / directionCheckedSession / forceSourceMode)
 * is kept as delegating properties onto the active tab, so the 1100-line
 * Activity keeps reading the same names and the tab diff does not turn into a
 * UI rewrite.
 *
 * Threading contract:
 *  - All state mutation happens on Main. EditorHistory is not thread-safe, so
 *    record/reset/undo/redo stay on Main.
 *  - Only stream reads, draft reads and draft writes hop to Dispatchers.IO.
 *    Text is snapshotted on Main before being handed to an IO coroutine.
 *
 * Cold-start restore:
 *  1. The manifest rebuilds one stub per open tab; only the active tab is
 *     hydrated, so ten tabs do not cost ten file reads at boot.
 *  2. A stub hydrates from its draft first (unsaved work wins), then from its
 *     uri, then falls back to an empty document.
 *  3. A v1.5 install has no manifest: last_uri/last_title plus draft.txt become
 *     tab_1, so an in-flight draft survives the upgrade.
 */
class EditorViewModel : ViewModel() {

    private val _tabs = mutableStateListOf<TabState>()
    val tabs: List<TabState> get() = _tabs

    var activeTabId: String by mutableStateOf(TAB_1)
        private set

    /**
     * The active tab. Always valid: a share intent can arrive before any restore
     * ran, and the editor must never render "no document".
     */
    val activeTab: TabState
        get() = _tabs.firstOrNull { it.id == activeTabId }
            ?: _tabs.firstOrNull()
            ?: TabState(TAB_1).also { _tabs.add(it); activeTabId = it.id }

    // --- v1.5-compatible surface, delegated to the active tab -------------

    val documentUri: Uri? get() = activeTab.uri

    var title: String
        get() = activeTab.title
        set(value) { activeTab.title = value }

    val editor: TextFieldValue get() = activeTab.editor

    val isDirty: Boolean get() = activeTab.isDirty

    val history: EditorHistory get() = activeTab.history

    var mdPreview: Boolean
        get() = activeTab.mdPreview
        set(value) { activeTab.mdPreview = value }

    var docRtl: Boolean
        get() = activeTab.docRtl
        set(value) { activeTab.docRtl = value }

    val forceSourceMode: Boolean get() = activeTab.forceSourceMode

    var surfaceSession: Int
        get() = activeTab.surfaceSession
        set(value) { activeTab.surfaceSession = value }

    var directionCheckedSession: Int
        get() = activeTab.directionCheckedSession
        set(value) { activeTab.directionCheckedSession = value }

    /** Bumped after any history mutation so Compose re-reads canUndo/canRedo. */
    var historyTick: Int by mutableStateOf(0)
        private set

    /**
     * Bumped on every tab switch, open and new. Global rather than per tab so a
     * switch always resets the surface (Find, pinch, scroll), reusing the exact
     * code path v1.5 used for "a different document arrived".
     */
    var documentSession: Int by mutableStateOf(0)
        private set

    // --- infrastructure ---------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var store: SessionStore? = null
    private var reader: ((Uri) -> String?)? = null
    private var draftDir: File? = null
    private var restored = false

    fun attachStore(sessionStore: SessionStore) {
        store = sessionStore
    }

    /** Injected by the Activity; this class reads through it on IO so it stays Context-free. */
    fun attachReader(read: (Uri) -> String?) {
        reader = read
    }

    /**
     * Kept for the v1.5 call site: the Activity hands over the recovery file.
     * Per-tab drafts live beside it, so only the parent directory matters for
     * ordinary writes; the file itself is still read once, as the v1.5
     * migration source (see [readDraft]).
     */
    fun attachDraft(recoveryFile: File) {
        draftDir = recoveryFile.parentFile
    }

    /** v1.5's single recovery file. Read once, only when no manifest exists. */
    private var legacyDraftFile: File? = null

    /** The pre-1.5 preference hand-off, consumed by the first hydrate. */
    private var pendingLegacyText: String? = null

    /**
     * The Activity owns the SAF grant; the tab model knows when one is dead.
     * Called with the uri whose last reference just went away.
     */
    var onReleaseUriPermission: ((Uri) -> Unit)? = null

    // --- tabs -------------------------------------------------------------

    private fun nextId(): String {
        var n = 1
        while (_tabs.any { it.id == "tab_$n" }) n++
        return "tab_$n"
    }

    fun canOpenAnotherTab(): Boolean = TabBudget.canAddTab(records())

    private fun records(): List<TabRecord> = _tabs.map { it.record() }

    /** Returns false when the tab limit or the text budget refuses a new tab. */
    fun newTab(markdown: Boolean): Boolean {
        if (!canOpenAnotherTab()) return false
        val tab = TabState(nextId())
        tab.title = if (markdown) UNTOOLED_MD else UNTOOLED_TXT
        tab.hydrated = true
        tab.fresh = true
        tab.forceSourceMode = true
        scheduleFlush(activeTab)
        _tabs.add(tab)
        activeTabId = tab.id
        bumpSession()
        persistManifest()
        return true
    }

    /**
     * Switches the active tab. The outgoing tab's draft is written on IO: a
     * synchronous write on every switch would block the frame for a multi-MB
     * document, and the epoch check already stops a late write from landing
     * after a save or a close.
     */
    fun switchTab(id: String): Boolean {
        if (id == activeTabId) return true
        val target = _tabs.firstOrNull { it.id == id } ?: return false
        scheduleFlush(activeTab)
        activeTabId = id
        if (!target.hydrated) hydrate(target)
        bumpSession()
        persistManifest()
        return true
    }

    /**
     * Removes a tab. The caller has already confirmed the discard when it was
     * dirty. Closing the last tab leaves one clean untitled tab behind, so the
     * editor never has to render "no document".
     */
    fun closeTab(id: String): Boolean {
        val index = _tabs.indexOfFirst { it.id == id }
        if (index < 0) return false
        val closing = _tabs[index]
        retireDraft(closing)
        _tabs.removeAt(index)
        if (_tabs.isEmpty()) {
            val fresh = TabState(nextId())
            fresh.hydrated = true
            fresh.fresh = true
            _tabs.add(fresh)
            activeTabId = fresh.id
        } else if (activeTabId == id) {
            val next = _tabs[index.coerceAtMost(_tabs.lastIndex)]
            activeTabId = next.id
            if (!next.hydrated) hydrate(next)
        }
        bumpSession()
        persistManifest()
        return true
    }

    private fun bumpSession() {
        documentSession++
        historyTick++
    }

    fun scrollYFor(id: String): Int = _tabs.firstOrNull { it.id == id }?.scrollY ?: 0

    fun setScrollY(value: Int) {
        activeTab.scrollY = value.coerceAtLeast(0)
    }

    fun setPreview(value: Boolean) {
        activeTab.mdPreview = value
        persistManifest()
    }

    fun togglePreview() = setPreview(!activeTab.mdPreview)

    fun applyDirection(value: Boolean) {
        activeTab.docRtl = value
        persistManifest()
    }

    /** Called by the Activity when a session starts: applies the preview default once. */
    fun beginSession(markdownName: Boolean) {
        val tab = activeTab
        if (tab.forceSourceMode) {
            tab.mdPreview = false
            tab.forceSourceMode = false
        } else if (tab.fresh) {
            tab.mdPreview = markdownName
        }
        tab.fresh = false
    }

    // --- persistence ------------------------------------------------------

    fun persistManifest(sync: Boolean = false) {
        val s = store ?: return
        if (_tabs.isEmpty()) return
        s.saveManifest(TabManifest(records(), activeTabId), sync)
    }

    private fun draftFile(tab: TabState): File? {
        val dir = draftDir ?: return null
        return File(dir, "draft_${tab.id}.txt")
    }

    /**
     * IO side of [hydrate]: the tab's own draft, else the v1.5 recovery source.
     *
     * v1.5 wrote unsaved text to filesDir/draft.txt, so an upgrade that only
     * looked at draft_<id>.txt silently resurrected the SAVED file and left the
     * unsaved text behind as an orphan. The legacy text is adopted into
     * draft_<id>.txt here, before it is returned, so a crash between the read
     * and the first edit cannot lose it a second time.
     */
    private fun readDraft(tab: TabState): String? {
        draftFile(tab)?.takeIf { it.exists() }?.let { file ->
            runCatching { BoundedReader.readText(file) }.getOrNull()?.let { return it }
        }
        val legacyFile = legacyDraftFile
        val carried = pendingLegacyText ?: legacyFile
            ?.takeIf { it.exists() }
            ?.let { file -> runCatching { BoundedReader.readText(file) }.getOrNull() }
        if (carried != null) {
            writeDraft(tab, carried, tab.draftEpoch)
            if (legacyFile != null && legacyFile != draftFile(tab) && legacyFile.exists()) {
                runCatching { legacyFile.delete() }
            }
        }
        return carried
    }

    /**
     * Called once from the Activity before the first composition. Same signature
     * as v1.5 so the call site did not change; what changed is that a manifest
     * of stubs is rebuilt instead of a single document.
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
        // draft directory for autosave.
        draftDir = draftFile.parentFile
        reader = readUri
        // A document may already be here: a share intent is handled before this
        // in onCreate. Restoring on top of it would duplicate tab ids.
        if (restored || _tabs.isNotEmpty()) {
            restored = true
            persistManifest()
            return
        }
        restored = true

        val manifest = store?.loadManifest()
        val records: List<TabRecord> = manifest?.tabs ?: listOf(
            TabManifestCodec.migrate(
                legacyUri = lastUri?.toString(),
                legacyTitle = lastTitle,
                legacyDirty = !legacyDraft.isNullOrEmpty(),
                legacyChars = legacyDraft?.length ?: 0,
            ).tabs.first()
        )

        for (record in records) {
            val tab = TabState(record.id)
            tab.uri = record.uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            tab.title = record.title
            tab.isDirty = record.isDirty
            tab.charCount = record.charCount
            tab.mdPreview = record.mdPreview
            tab.docRtl = record.docRtl
            tab.scrollY = record.scrollY
            _tabs.add(tab)
        }
        if (_tabs.isEmpty()) _tabs.add(TabState(TAB_1))
        activeTabId = manifest?.activeTabId?.takeIf { id -> _tabs.any { it.id == id } } ?: _tabs.first().id

        // v1.5 kept unsaved text in ONE recovery file (filesDir/draft.txt) and
        // in an older preference key -- never in prefs["draft"] as the only
        // source. Queue both for hydrate() to read on IO. Nothing is written
        // from here: the old code queued a write and then hydrated the very
        // next line, so the read and the write raced on the IO pool.
        pendingLegacyText = legacyDraft?.takeIf { it.isNotEmpty() }
        if (manifest == null) legacyDraftFile = draftFile
        store?.clearLegacyDocument()

        hydrate(activeTab)
        persistManifest()
    }

    /**
     * Loads a stub's text: draft first (unsaved work wins), then the file, then
     * an empty buffer. Both reads are bounded -- the v1.6 fix for a huge
     * remembered file killing the process during a cold start.
     */
    private fun hydrate(tab: TabState) {
        if (tab.hydrated || tab.loading) return
        tab.loading = true
        val token = ++tab.loadToken
        scope.launch {
            val draft = withContext(Dispatchers.IO) { readDraft(tab) }
            if (token != tab.loadToken || !_tabs.contains(tab)) return@launch
            if (draft != null) {
                applyText(tab, draft, dirty = true)
                // The legacy source is now this tab's own draft file; never
                // seed another tab from it.
                pendingLegacyText = null
                legacyDraftFile = null
                tab.loading = false
                bumpSession()
                persistManifest()
                return@launch
            }
            val uri = tab.uri
            if (uri == null) {
                applyText(tab, "", dirty = false)
                tab.loading = false
                return@launch
            }
            val text = withContext(Dispatchers.IO) { runCatching { reader?.invoke(uri) }.getOrNull() }
            if (token != tab.loadToken || !_tabs.contains(tab)) return@launch
            tab.loading = false
            if (text == null) {
                // Grant expired or the file is gone: drop the identity but keep
                // the tab, so no other tab's work is disturbed.
                tab.uri = null
                applyText(tab, "", dirty = false)
                tab.title = UNTOOLED_TXT
                bumpSession()
                persistManifest()
                return@launch
            }
            applyText(tab, text, dirty = false)
            bumpSession()
            persistManifest()
        }
    }

    private fun applyText(tab: TabState, text: String, dirty: Boolean) {
        tab.editor = TextFieldValue(text)
        tab.history.reset()
        tab.charCount = text.length
        tab.isDirty = dirty
        tab.hydrated = true
    }

    // --- document operations (active tab) ---------------------------------

    fun snapshot(): EditorSnapshot = snapshotOf(activeTab)

    private fun snapshotOf(tab: TabState): EditorSnapshot =
        EditorSnapshot(tab.editor.text, tab.editor.selection.start, tab.editor.selection.end)

    private fun of(value: TextFieldValue): EditorSnapshot =
        EditorSnapshot(value.text, value.selection.start, value.selection.end)

    /**
     * Applies an explicitly opened/created document to ONE tab, and invalidates
     * any hydrate still in flight for it.
     *
     * The tab id is a parameter, not `activeTab`: the read that produced [text]
     * ran on IO, and the user can switch tabs while it is in flight. Resolving
     * the target afterwards is how an opened file used to land in -- and wipe --
     * whichever tab happened to be active when the read finished.
     */
    fun setDocument(tabId: String, uri: Uri?, newTitle: String, text: String) {
        val tab = _tabs.firstOrNull { it.id == tabId } ?: return
        retireDraft(tab)
        tab.loadToken++
        tab.loading = false
        replaceUri(tab, uri)
        tab.title = newTitle
        tab.editor = TextFieldValue(text)
        tab.history.reset()
        tab.charCount = text.length
        tab.isDirty = false
        tab.hydrated = true
        tab.fresh = true
        tab.forceSourceMode = false
        bumpSession()
        persistManifest()
    }

    /** v1.5-compatible name: a new document is a new tab. */
    fun newDocument(markdown: Boolean): Boolean = newTab(markdown)

    /**
     * Opens a document that came from outside the editor -- the Open dialog, a
     * file-manager share, an ACTION_VIEW -- in a tab of its OWN.
     *
     * Open used to load into whatever tab was active, so the document the user
     * was reading vanished from the strip and its place was taken by the file
     * they picked. That is "New" in reverse: the strip is a list of open
     * documents, and opening one must add to it, never swap its contents.
     *
     * Returns the new tab id, or null when the tab budget refuses another tab
     * (the caller then leaves both the strip and the file untouched).
     */
    fun openInNewTab(uri: Uri?, newTitle: String, text: String): String? {
        if (!canOpenAnotherTab()) return null
        val tab = TabState(nextId())
        tab.hydrated = true
        scheduleFlush(activeTab)
        _tabs.add(tab)
        activeTabId = tab.id
        setDocument(tab.id, uri, newTitle, text)
        return tab.id
    }

    /** Clears the active tab back to an empty untitled document (the Discard action). */
    fun discardActiveTab() = setDocument(activeTabId, null, UNTOOLED_TXT, "")

    fun renameTitle(tabId: String, newTitle: String) {
        val tab = _tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.title != newTitle) tab.title = newTitle
        persistManifest()
    }

    fun setDocumentUri(tabId: String, uri: Uri?) {
        val tab = _tabs.firstOrNull { it.id == tabId } ?: return
        replaceUri(tab, uri)
        persistManifest()
    }

    /**
     * Points a tab at a new uri and hands the old grant back when nothing else
     * refers to it. Persistable grants are a limited resource (~128 per app)
     * and a replaced uri used to keep its grant for the life of the process.
     */
    /** True when [text] is still exactly what [tabId] holds (a save raced the keyboard). */
    /** The text a tab holds right now, or null when it is gone. */
    fun textOf(tabId: String): String? = _tabs.firstOrNull { it.id == tabId }?.editor?.text

    fun sameText(tabId: String, text: String): Boolean =
        _tabs.firstOrNull { it.id == tabId }?.editor?.text == text

    private fun replaceUri(tab: TabState, uri: Uri?) {
        val old = tab.uri
        tab.uri = uri
        if (old != null && old != uri && _tabs.none { other -> other !== tab && other.uri == old }) {
            onReleaseUriPermission?.invoke(old)
        }
    }

    fun markClean(tabId: String = activeTabId) {
        val tab = _tabs.firstOrNull { it.id == tabId } ?: return
        tab.isDirty = false
        retireDraft(tab)
        persistManifest()
    }

    fun onTextChanged(newValue: TextFieldValue, allowCoalescing: Boolean) {
        val tab = activeTab
        val changed = newValue.text != tab.editor.text
        tab.history.record(snapshotOf(tab), of(newValue), android.os.SystemClock.uptimeMillis(), allowCoalescing = allowCoalescing)
        historyTick++
        tab.editor = newValue
        tab.charCount = newValue.text.length
        // A cursor move or selection is not an edit.
        if (changed) afterEdit(tab, newValue.text)
    }

    /** A blank, never-saved document has nothing to lose, so it is not dirty and keeps no draft. */
    private fun afterEdit(tab: TabState, text: String) {
        tab.isDirty = tab.uri != null || text.isNotEmpty()
        if (tab.isDirty) scheduleAutosave(tab, text) else retireDraft(tab)
    }

    fun undo(): Boolean {
        val tab = activeTab
        val target = tab.history.undo(snapshotOf(tab)) ?: return false
        tab.editor = TextFieldValue(target.text, TextRange(target.start, target.end))
        historyTick++
        afterEdit(tab, target.text)
        return true
    }

    fun redo(): Boolean {
        val tab = activeTab
        val target = tab.history.redo(snapshotOf(tab)) ?: return false
        tab.editor = TextFieldValue(target.text, TextRange(target.start, target.end))
        historyTick++
        afterEdit(tab, target.text)
        return true
    }

    fun breakCoalescing() = activeTab.history.breakCoalescing()

    // --- Crash recovery ---------------------------------------------------
    // The draft file is recovery-only. Every save reads editor.text directly.

    // Every draft write and delete goes through draftLock, and retireDraft()
    // bumps that tab's draftEpoch, so a write queued before a save/new/close can
    // never land afterwards and resurrect old text.
    private val draftLock = Any()

    private fun writeDraft(tab: TabState, text: String, epoch: Int) {
        synchronized(draftLock) {
            val file = draftFile(tab) ?: return
            if (epoch != tab.draftEpoch) return
            runCatching {
                // Temp file + rename: a process kill mid-write must not truncate the draft.
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text, Charsets.UTF_8)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    private fun retireDraft(tab: TabState) {
        tab.autosaveJob?.cancel()
        synchronized(draftLock) {
            tab.draftEpoch++
            draftFile(tab)?.let { runCatching { it.delete() } }
        }
    }

    private fun scheduleAutosave(tab: TabState, text: String) {
        tab.autosaveJob?.cancel()
        if (!tab.isDirty) return
        val epoch = tab.draftEpoch
        tab.autosaveJob = scope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            withContext(Dispatchers.IO) { writeDraft(tab, text, epoch) }
            // The manifest carries charCount, and the budget check reads it
            // after a cold start: a draft written without this line left the
            // number stale and let the app overrun its own character budget.
            if (epoch == tab.draftEpoch && tab.isDirty) persistManifest()
        }
    }

    /** Async flush used on tab switch: cannot block the frame, the epoch keeps it honest. */
    private fun scheduleFlush(tab: TabState) {
        tab.autosaveJob?.cancel()
        if (!tab.isDirty) return
        val epoch = tab.draftEpoch
        val text = tab.editor.text
        scope.launch { withContext(Dispatchers.IO) { writeDraft(tab, text, epoch) } }
    }

    /**
     * Immediate, synchronous write for onPause and Back, so backgrounding or
     * exiting never loses keystrokes: a coroutine launched there could be
     * cancelled by onCleared() before it ran. No-op when clean, because
     * retireDraft() removed the file and rewriting it would make the next cold
     * start report an already-saved document as dirty.
     */
    fun flushAutosave() {
        val tab = activeTab
        tab.autosaveJob?.cancel()
        if (!tab.isDirty) { persistManifest(sync = true); return }
        writeDraft(tab, tab.editor.text, tab.draftEpoch)
        persistManifest(sync = true)
    }

    override fun onCleared() {
        _tabs.forEach { it.autosaveJob?.cancel() }
        scope.cancel()
        super.onCleared()
    }

    companion object {
        const val UNTOOLED_TXT = "untitled.txt"
        const val UNTOOLED_MD = "untitled.md"
        const val TAB_1 = "tab_1"
        private const val AUTOSAVE_DEBOUNCE_MS = 500L
    }
}