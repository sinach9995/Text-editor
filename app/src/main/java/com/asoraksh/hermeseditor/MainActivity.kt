@file:OptIn(ExperimentalFoundationApi::class)

package com.asoraksh.hermeseditor

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.util.Locale
import java.io.File
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("hermes_editor", MODE_PRIVATE) }

    // One source of truth shared by the Activity-level result callbacks and
    // the Compose editor. Survives rotation and recreate() on a language switch.
    private val vm: EditorViewModel by lazy { ViewModelProvider(this)[EditorViewModel::class.java] }

    private val messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private fun notifyMessage(message: String) { messages.trySend(message) }

    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("hermes_editor", Context.MODE_PRIVATE).getString("lang", "en") ?: "en"
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(Locale(lang))
        super.attachBaseContext(ContextWrapper(newBase.createConfigurationContext(config)))
    }

    private val importsDir: File by lazy { File(filesDir, "imports") }

    private fun ownedImportFile(uri: Uri): File? =
        if (uri.scheme == "file") ImportedFiles.ownedFile(importsDir, uri.path) else null

    private fun openInputStreamFor(uri: Uri): java.io.InputStream? =
        if (uri.scheme == "file") ownedImportFile(uri)?.let { java.io.FileInputStream(it) }
        else contentResolver.openInputStream(uri)

    private fun writeTextToUri(uri: Uri, text: String) {
        ownedImportFile(uri)?.let {
            ImportedFiles.writeAtomic(it, text)
            return
        }
        requireNotNull(contentResolver.openOutputStream(uri, "wt"))
            .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    /**
     * Take persistable READ first, then attempt WRITE in its own guarded call.
     * A single read|write request throws SecurityException as a unit on
     * providers that granted only read, which would forfeit the read grant too.
     * Returns true when read access survives process restarts (persistent SAF
     * grant or app-owned local import copy).
     */
    private fun takePersistablePermissions(uri: Uri): Boolean {
        if (uri.scheme == "file") return ownedImportFile(uri) != null
        val readGranted = try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            runCatching {
                contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            }.getOrDefault(false)
        } catch (_: IllegalArgumentException) {
            false
        }
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: SecurityException) { }
        return readGranted
    }

    /** Grants and local import copies are released once no open tab or Recent row needs them. */
    private fun releasePermission(uri: Uri) {
        ownedImportFile(uri)?.let { owned ->
            if (vm.tabs.none { it.uri == uri } && recentFiles.none { it.uri == uri.toString() }) {
                ImportedFiles.delete(owned)
            }
            return
        }
        if (recentFiles.any { it.uri == uri.toString() }) return
        runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openUri(it) }
    }

    /** The files this app has opened, newest first; read by the Recent tab. */
    private var recentFiles by mutableStateOf<List<RecentFile>>(emptyList())

    private fun rememberRecent(target: Uri, title: String) {
        val before = recentFiles.map { it.uri }.toSet()
        RecentFiles.record(this, target.toString(), title)
        recentFiles = RecentFiles.load(this)
        // An entry pushed off the end can never be tapped again, so its grant
        // goes back now instead of leaking for the life of the process.
        (before - recentFiles.map { it.uri }.toSet()).forEach { dropped ->
            if (vm.tabs.none { it.uri?.toString() == dropped }) {
                runCatching { releasePermission(Uri.parse(dropped)) }
            }
        }
    }

    /**
     * The list the Recent page shows: documents open right now, then the history.
     * A restored tab was never opened in this run, so without the first half it
     * would be missing from the very list it belongs to.
     */
    private fun recentList(): List<RecentFile> {
        val open = vm.tabs.mapNotNull { tab -> tab.uri?.let { RecentFile(it.toString(), tab.title) } }
        val seen = open.map { it.uri }.toSet()
        return open + recentFiles.filter { it.uri !in seen }
    }

    /** Long-press on a row: forget it, and hand its grant back if no tab holds it. */
    private fun forgetRecent(file: RecentFile) {
        RecentFiles.forget(this, file.uri)
        recentFiles = RecentFiles.load(this)
        if (vm.tabs.none { it.uri?.toString() == file.uri }) {
            runCatching { releasePermission(Uri.parse(file.uri)) }
        }
    }

    /**
     * Opens [source] in a tab of its own, or jumps to the tab that already holds
     * it. The Open dialog, the Recent tab and an incoming share all go through
     * here so all three agree on what "already open" means. Temporary grants
     * (such as Telegram/external shares) are copied to app-private storage so
     * they reopen cleanly after both apps close.
     */
    private fun openUri(source: Uri, fromRecent: Boolean = false) {
        val persistedRead = takePersistablePermissions(source)
        val key = documentKey(source)
        val sourceName = displayName(source)
        val cachedFile = if (!persistedRead) ImportedFiles.targetFile(importsDir, key, sourceName) else null
        val cachedUri = cachedFile?.let { Uri.fromFile(it) }
        // An already-open file is jumped to, never opened twice: two tabs on one
        // file would save over each other with stale text.
        val existing = vm.tabs.firstOrNull { tab ->
            tab.uri?.let { it == source || (cachedUri != null && it == cachedUri) || documentKey(it) == key } == true
        }
        if (existing != null) {
            vm.switchTab(existing.id)
            return
        }
        // The read below is async. Nothing is pinned to a tab any more: the file
        // gets a tab of its own once it has been read, so a tab switch during the
        // read cannot matter at all.
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { openInputStreamFor(source)?.let { BoundedReader.readText(it) } }
            }
            val text = result.getOrNull()
            when {
                result.exceptionOrNull() is BoundedReader.TooLargeException ->
                    notifyMessage(getString(R.string.err_file_too_large))
                // Leave every buffer untouched; an empty tab here would be an
                // empty tab the user never asked for.
                text == null -> {
                    notifyMessage(getString(R.string.err_open))
                    // A row that cannot be read any more fails on every tap;
                    // forget it instead of leaving a dead entry on the list.
                    if (fromRecent) forgetRecent(RecentFile(source.toString(), sourceName))
                }
                else -> {
                    val target = if (cachedFile != null) {
                        withContext(Dispatchers.IO) {
                            runCatching { ImportedFiles.writeAtomic(cachedFile, text); cachedUri }.getOrNull()
                        } ?: source
                    } else {
                        source
                    }
                    if (vm.openInNewTab(target, displayName(target), text) == null) {
                        if (cachedUri != null && target == cachedUri) releasePermission(target)
                        notifyMessage(getString(if (vm.tabs.size >= TabBudget.MAX_TABS) R.string.tab_limit_reached else R.string.tab_memory_reached))
                    } else {
                        rememberRecent(target, displayName(target))
                    }
                }
            }
        }
    }

    /** A tap on the Recent tab: switch to the tab that holds the file, or reopen it. */
    private fun openRecent(file: RecentFile) {
        val open = vm.tabs.firstOrNull { it.uri?.toString() == file.uri }
        if (open != null) {
            vm.switchTab(open.id)
            return
        }
        val uri = runCatching { Uri.parse(file.uri) }.getOrNull() ?: return
        openUri(uri, fromRecent = true)
    }

    /**
     * Stable identity of a document inside its provider. getDocumentId() throws
     * for URIs that are not DocumentsProvider-backed, so the isDocumentUri check
     * must come first and both calls stay inside runCatching; the URI string is
     * the fallback, and the caller also compares whole URIs.
     */
    private fun documentKey(uri: Uri): String = runCatching {
        if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
            android.provider.DocumentsContract.getDocumentId(uri)
        } else null
    }.getOrNull() ?: uri.toString()
    private var pendingSaveTabId: String? = null
    private fun takeSaveTabId(): String = pendingSaveTabId?.also { pendingSaveTabId = null } ?: vm.activeTabId

    private val createTxtDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) saveAsToUri(uri) else pendingSaveTabId = null
    }
    private val createMdDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) saveAsToUri(uri) else pendingSaveTabId = null
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val target = uri ?: run { pendingSaveTabId = null; return@registerForActivityResult }
        val tabId = takeSaveTabId()
        lifecycleScope.launch {
            val raw = vm.awaitTextOf(tabId) ?: run {
                notifyMessage(getString(R.string.save_failed))
                return@launch
            }
            val plain = markdownToPlainText(raw)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(contentResolver.openOutputStream(target, "wt"))
                        .bufferedWriter(Charsets.UTF_8).use { w -> w.write(plain) }
                }.isSuccess
            }
            notifyMessage(
                if (ok) getString(R.string.exported, displayName(target))
                else getString(R.string.save_failed)
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pending_save_tab_id", pendingSaveTabId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The store and the recovery-draft file live as long as the ViewModel,
        // not as long as one Activity instance. After process death the system
        // recreates the Activity WITH savedInstanceState and a brand new
        // ViewModel, so attaching these only on a null savedInstanceState left
        // that restore with a null store and a null draft file -- the exact
        // case this feature exists for.
        val store = PrefsSessionStore(this)
        recentFiles = RecentFiles.load(this)
        vm.attachStore(store)
        vm.attachDraft(File(filesDir, "draft.txt"))
        // A tab that points at a new uri drops the old grant; only the Activity
        // can release it, only the tab model knows when the last reference goes.
        vm.onReleaseUriPermission = ::releasePermission
        pendingSaveTabId = savedInstanceState?.getString("pending_save_tab_id")

        // A replayed launch intent must NOT overwrite unsaved edits on a
        // recreation (rotation, language switch, process death). Only a truly
        // fresh activity honours it; later intents arrive through onNewIntent.
        val fresh = savedInstanceState == null
        val legacy = if (fresh) prefs.getString("draft", "")?.also { prefs.edit().remove("draft").apply() } else null
        val incoming = if (fresh) intent?.takeIf { it.data != null } else null
        // The remembered tab strip is rebuilt FIRST, always. A share used to
        // skip the restore entirely, so a file opened from the phone's file
        // manager arrived as the only tab and the documents from the previous
        // session looked gone. It now lands beside them, in its own tab.
        // Idempotent: a rotation reuses this ViewModel and returns at once.
        restoreLastSession(legacy, store)
        if (incoming != null) handleIncomingIntent(incoming)
        setContent { HermesEditor() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }
    override fun onPause() { super.onPause(); saveScrollPosition(); vm.flushAutosave() }

    /** Set from the composition, which is the only place that knows the live scroll offsets. */
    private var saveScrollPosition: () -> Unit = {}

    /**
     * Cold start with no incoming intent: reopen the remembered document, or the
     * recovery draft when there are unsaved edits. The read happens on IO and
     * an unreadable uri clears the remembered identity instead of crashing.
     */
    private fun restoreLastSession(legacyDraft: String?, store: PrefsSessionStore) {
        val legacy = store.loadLegacyDocument()
        vm.restoreSession(
            draftFile = File(filesDir, "draft.txt"),
            legacyDraft = legacyDraft,
            lastUri = legacy?.first,
            lastTitle = legacy?.second,
        ) { uri ->
            // Bounded, like every other read path: a 40 MB remembered file must
            // not kill the process before the first frame.
            openInputStreamFor(uri)?.let { BoundedReader.readText(it) }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        // File-manager shares do not always grant persistent access; reading
        // must still work, so the permission calls are individually guarded.
        takePersistablePermissions(uri)
        // No discard question: the incoming file gets its own tab, so no open
        // document is replaced and there is nothing to lose.
        openIncoming(uri)
    }

    // A share and the Open dialog are the same operation once the uri is here:
    // one dedupe rule, one read, one recency entry.
    private fun openIncoming(uri: Uri) = openUri(uri)
    private fun dedupeSavedUri(ownerTabId: String, uri: Uri): Boolean {
        val key = documentKey(uri)
        val duplicates = vm.tabs.filter { tab ->
            tab.id != ownerTabId && tab.uri?.let { it == uri || documentKey(it) == key } == true
        }
        for (dup in duplicates) {
            if (dup.isDirty) vm.setDocumentUri(dup.id, null) else vm.closeTab(dup.id)
        }
        return duplicates.isNotEmpty()
    }

    private fun writeTabToUri(uri: Uri, saveAs: Boolean) {
        val tabId = takeSaveTabId()
        takePersistablePermissions(uri)
        lifecycleScope.launch {
            val draft = vm.awaitTextOf(tabId) ?: run {
                notifyMessage(getString(R.string.save_failed))
                return@launch
            }
            val result: Result<Unit> = withContext(Dispatchers.IO) {
                runCatching { writeTextToUri(uri, draft) }
            }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (saveAs) notifyMessage(getString(R.string.save_failed))
                else fallbackSaveAsAfterDenied(failure)
            } else {
                val name = displayName(uri)
                val hadUri = vm.tabs.firstOrNull { it.id == tabId }?.uri != null
                vm.setDocumentUri(tabId, uri)
                val deduped = dedupeSavedUri(tabId, uri)
                vm.renameTitle(tabId, name)
                // Only clear dirty/draft when nothing was typed during the write.
                if (vm.sameText(tabId, draft)) vm.markClean(tabId)
                rememberRecent(uri, name)
                notifyMessage(getString(when {
                    deduped -> R.string.saved_deduped
                    saveAs && hadUri -> R.string.saved_copy
                    else -> R.string.saved
                }, name))
            }
        }
    }

    private fun saveToUri(uri: Uri) = writeTabToUri(uri, saveAs = false)
    private fun saveAsToUri(uri: Uri) = writeTabToUri(uri, saveAs = true)

    /**
     * An ACTION_VIEW / ACTION_SEND share is frequently read-only. Rather than
     * reporting a bare failure, ask the editor surface to open the Save As
     * dialog so the user can write to a location they do control.
     */
    private val saveAsFallback = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private fun fallbackSaveAsAfterDenied(cause: Throwable?) {
        notifyMessage(getString(R.string.err_read_only_save_as))
        saveAsFallback.trySend(Unit)
    }
    private fun baseName(title: String) = title.substringBeforeLast('.', title).ifBlank { "document" }
    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val name = cursor.getString(index)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (_: Exception) { }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return if (fallback != null && !fallback.startsWith("msf:") && !fallback.contains(":")) fallback else "Document.txt"
    }

    private fun openLink(url: String) {
        try {
            val parsed = Uri.parse(url)
            if (parsed.scheme == "http" || parsed.scheme == "https") {
                startActivity(Intent(Intent.ACTION_VIEW, parsed))
            }
        } catch (_: Exception) { }
    }

    private fun isMarkdownName(name: String) = name.endsWith(".md", ignoreCase = true) || name.endsWith(".markdown", ignoreCase = true)

    private fun detectDirection(sample: String): Boolean? {
        var arabic = 0
        var letters = 0
        for (ch in sample) {
            if (letters >= 500) break
            if (ch.isWhitespace()) continue
            val isAr = ch in '؀'..'ۿ' || ch in 'ݐ'..'ݿ' || ch in 'ࢠ'..'ࣿ' || ch in 'ﭐ'..'﷿' || ch in 'ﹰ'..'﻿'
            val isLat = ch in 'A'..'Z' || ch in 'a'..'z'
            if (!isAr && !isLat) continue
            letters++
            if (isAr) arabic++
        }
        if (letters < 20) return null
        val ratio = arabic.toFloat() / letters
        return when {
            ratio >= 0.6 -> true
            ratio <= 0.4 -> false
            else -> null
        }
    }

    @Composable
    private fun HermesEditor() {
        val vm = this.vm
        val editor by vm::editor
        val title by vm::title
        val text = editor.text
        val history = vm.history
        val historyTick by vm::historyTick
        var pastedEdit by remember { mutableStateOf(false) }
        val systemClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        val editorClipboard = remember(systemClipboard, history) {
            object : androidx.compose.ui.platform.ClipboardManager {
                override fun getText(): androidx.compose.ui.text.AnnotatedString? {
                    history.breakCoalescing()
                    pastedEdit = true
                    return systemClipboard.getText()
                }
                override fun setText(annotatedString: androidx.compose.ui.text.AnnotatedString) = systemClipboard.setText(annotatedString)
                override fun hasText(): Boolean = systemClipboard.hasText()
            }
        }
        val snackbar = remember { SnackbarHostState() }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val editorFocus = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        var fontSize by remember { mutableStateOf(prefs.getFloat("document_font_size", 17f).coerceIn(13f, 30f)) }
        var finding by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var matchIndex by remember { mutableStateOf(0) }
        // Only scan while the Find bar is open: findMatches collects EVERY hit,
        // and this ran on each keystroke of a 5 MB document with the bar shut.
        val matches = remember(text, query, finding) {
            if (finding) findMatches(text, query) else emptyList<IntRange>()
        }
        val activeMatch = if (finding) matches.getOrNull(matchIndex.coerceAtMost((matches.size - 1).coerceAtLeast(0)))?.let { TextRange(it.first, it.last + 1) } else null
        val editorScroll = rememberScrollState()
        var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        val previewScroll = rememberScrollState()
        val previewAnchors = remember { PreviewAnchors() }
        val previewTextAnchors = remember { PreviewTextAnchors() }
        previewTextAnchors.onCorrection = { delta -> previewScroll.dispatchRawDelta(delta) }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val topPad = if (finding) 76.dp else 20.dp
        val textTop = with(density) { topPad.toPx() }
        val previewTop = with(density) { 16.dp.toPx() }
        var pendingAnchor by remember { mutableStateOf<DocumentAnchor?>(null) }
        var pinchAnchor by remember { mutableStateOf<DocumentAnchor?>(null) }
        var pinching by remember { mutableStateOf(false) }
        // Keep typography stable while fingers are down.  Reflowing a long
        // BasicTextField for every pointer event causes the visible jumping
        // reported on-device.  The gesture is drawn as a GPU scale first and
        // committed to the real font size only when the pinch ends.
        var pinchVisualScale by remember { mutableStateOf(1f) }
        var pinchCentroid by remember { mutableStateOf(Offset.Zero) }
        var sourceContentSize by remember { mutableStateOf(IntSize.Zero) }
        var pinchLineFraction by remember { mutableStateOf(0f) }
        var pinchScrollOwner by remember { mutableStateOf<Job?>(null) }
        var lastPinchLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        var cursorVisibilityRequest by remember { mutableStateOf(false) }
        val cursorRequester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        val imeBottom = WindowInsets.ime.getBottom(density)
        var viewportHeight by remember { mutableStateOf(0) }
        var editorFocused by remember { mutableStateOf(false) }
        fun sourceAnchor(screen: Offset): DocumentAnchor {
            val layout = textLayout ?: return DocumentAnchor(0, screen.y)
            val offset = layout.getOffsetForPosition(Offset(screen.x - with(density) { 20.dp.toPx() }, editorScroll.value + screen.y - textTop))
            val y = layout.getCursorRect(offset).top + textTop - editorScroll.value
            return DocumentAnchor(offset, y)
        }

        LaunchedEffect(Unit) { for (message in messages) { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message, duration = SnackbarDuration.Short) } }
        LaunchedEffect(activeMatch) {
            withFrameNanos { }
            val match = activeMatch
            val layout = textLayout
            if (match != null && layout != null && layout.layoutInput.text.text == text && pinchAnchor == null) {
                val y = (layout.getBoundingBox(match.start.coerceAtMost((text.length - 1).coerceAtLeast(0))).top + textTop - with(density) { 72.dp.toPx() }).toInt()
                editorScroll.animateScrollTo(y.coerceIn(0, editorScroll.maxValue))
            }
        }
        var selectedTheme by rememberSaveable { mutableStateOf(if (prefs.getString("theme", "dark") == "light") AppTheme.LIGHT else AppTheme.DARK) }
        // Read the saved language again after recreate(). rememberSaveable
        // restored English after the first Persian choice and mixed Farsi
        // resources with an LTR onboarding state.
        var lang by remember { mutableStateOf(prefs.getString("lang", "en") ?: "en") }
        var showDiscardDialog by remember { mutableStateOf(false) }
        var showDiscardConfirm by remember { mutableStateOf(false) }
        var showFormatChoice by remember { mutableStateOf(false) }
        var showHelp by remember { mutableStateOf(false) }
        // One guided tour replaces the old auto-dismissed Snackbar hints.
        // Language is chosen explicitly once so a Persian reader never has to
        // understand an English-only first-launch instruction.
        var showOnboardingLanguage by remember {
            mutableStateOf(!prefs.getBoolean("onboarding_language_chosen", false))
        }
        var showOnboarding by remember {
            mutableStateOf(
                prefs.getBoolean("onboarding_language_chosen", false) &&
                    !prefs.getBoolean("onboarding_complete", false)
            )
        }
        var onboardingStep by rememberSaveable { mutableStateOf(0) }
        var showDirMenu by remember { mutableStateOf(false) }
        var dirSuggest by remember { mutableStateOf<Boolean?>(null) }
        var dirSession by remember { mutableStateOf(0) }
        val docRtl by vm::docRtl
        val mdPreview by vm::mdPreview
        fun captureAnchor(screen: Offset): DocumentAnchor = if (mdPreview)
            previewAnchors.capture(previewScroll.value + screen.y - previewTop, screen.y)
        else sourceAnchor(screen)
        fun switchMode() {
            pendingAnchor = captureAnchor(Offset(0f, textTop))
            previewAnchors.clear()
            vm.togglePreview()
        }
        /**
         * The first line of the visible area, as an offset plus the screen y it
         * currently occupies. A font change reflows every wrapped line, so the
         * raw pixel offset no longer means the same paragraph: the reader used
         * to land somewhere else with no way back to their place. Anchoring the
         * top line keeps what they were reading exactly where it was.
         */
        fun topVisibleAnchor(): DocumentAnchor {
            val layout = textLayout ?: return DocumentAnchor(0, textTop)
            // TextLayoutResult has no vertical-offset lookup: ask for the
            // character at the top edge, then take its line.
            val atTop = layout.getOffsetForPosition(Offset(0f, editorScroll.value + 1f))
            val line = layout.getLineForOffset(atTop).coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
            return DocumentAnchor(layout.getLineStart(line), layout.getLineTop(line) + textTop - editorScroll.value)
        }
        /**
         * Puts [anchor] back at the screen y it was captured at, once the layout
         * has settled on [expected] (null: two frames are enough).
         *
         * A reflow of a multi-MB buffer lands a frame or two after the click.
         * Correcting against the OLD layout restores the old offsets and the
         * page jumps anyway, so wait (bounded) for a layout carrying the new
         * size instead of counting frames and hoping.
         */
        suspend fun settleAnchor(anchor: DocumentAnchor, expected: TextUnit?) {
            if (expected == null) {
                withFrameNanos { }; withFrameNanos { }
            } else {
                var frames = 0
                while (frames++ < 12 && textLayout?.layoutInput?.style?.fontSize != expected) withFrameNanos { }
            }
            withFrameNanos { }
            val layout = textLayout ?: return
            // A stale layout describes text we are no longer showing.
            if (layout.layoutInput.text.text != text) return
            val scroll = if (mdPreview) previewScroll else editorScroll
            val y = if (mdPreview) previewAnchors.y(anchor.offset)?.plus(previewTop)
                else layout.getCursorRect(anchor.offset.coerceIn(0, text.length)).top + textTop
            if (y != null) scroll.scrollTo((y - anchor.screenY).toInt().coerceIn(0, scroll.maxValue))
        }
        /**
         * One entry point for the menu's -/+ so every size change anchors. The
         * correction runs as its own coroutine rather than through
         * [pendingAnchor]: two consecutive clicks can capture the SAME anchor,
         * and a LaunchedEffect keyed on it would never re-run for the second.
         */
        fun applyFontSize(size: Float) {
            val next = size.coerceIn(13f, 30f)
            if (next == fontSize) return
            val anchor = if (mdPreview) captureAnchor(Offset(0f, previewTop)) else topVisibleAnchor()
            val expected = if (mdPreview) null else next.sp
            fontSize = next
            prefs.edit().putFloat("document_font_size", next).apply()
            scope.launch { settleAnchor(anchor, expected) }
        }
        val startPinch: (Offset) -> Unit = { centroid ->
            pendingAnchor = null
            pinchScrollOwner?.cancel()
            cursorVisibilityRequest = false
            pinchVisualScale = 1f
            pinchCentroid = centroid
            // Cancel an existing fling/Find/BIV animation before capturing geometry.
            val scroll = if (mdPreview) previewScroll else editorScroll
            pinchScrollOwner = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                scroll.scroll(MutatePriority.PreventUserInput) { awaitCancellation() }
            }
            lastPinchLayout = textLayout
            pinchAnchor = captureAnchor(centroid)
            if (mdPreview) {
                previewTextAnchors.capture(centroid)
            } else {
                textLayout?.let { layout ->
                    val fixed = pinchAnchor!!
                    val rect = layout.getCursorRect(fixed.offset)
                    pinchLineFraction = ((centroid.y - fixed.screenY) / rect.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    pinchAnchor = fixed.copy(screenY = fixed.screenY + pinchLineFraction * rect.height)
                }
            }
            pinching = true
        }
        val zoomDocument: (Float, Offset) -> Unit = { factor, centroid ->
            // Visual-only scaling during the gesture keeps the character below
            // the fingers fixed.  Do not change fontSize here: changing it
            // reflows wrapped lines asynchronously and makes the page jump.
            pinchCentroid = centroid
            val minRelativeScale = 13f / fontSize
            val maxRelativeScale = 30f / fontSize
            val next = (pinchVisualScale * factor).coerceIn(minRelativeScale, maxRelativeScale)
            if (next.isFinite()) pinchVisualScale = next
        }
        val endPinch: () -> Unit = {
            // Make one real typography change after the fingers lift.  The
            // existing gesture-start anchor corrects the one resulting reflow.
            val nextFontSize = (fontSize * pinchVisualScale).coerceIn(13f, 30f)
            val changed = nextFontSize != fontSize
            pinchVisualScale = 1f
            if (changed) {
                fontSize = nextFontSize
                // Force exactly one correction when the final layout arrives.
                lastPinchLayout = null
            }
            pinching = false
            prefs.edit().putFloat("document_font_size", fontSize).apply()
        }
        fun restorePinch(y: Float) {
            val anchor = pinchAnchor ?: return
            val scroll = if (mdPreview) previewScroll else editorScroll
            // Synchronous correction on measured geometry, not queued animations.
            scroll.dispatchRawDelta(y - anchor.screenY - scroll.value)
        }
        LaunchedEffect(pinching) {
            if (!pinching && pinchAnchor != null) {
                withFrameNanos { }; withFrameNanos { }
                pinchAnchor = null
                previewTextAnchors.release()
                pinchScrollOwner?.cancel()
                pinchScrollOwner = null
            }
        }
        LaunchedEffect(imeBottom, viewportHeight, editorFocused, editor.selection, text, pinching) {
            if (imeBottom > 0 && editorFocused && !mdPreview && !finding && pinchAnchor == null) {
                withFrameNanos { }
                val layout = textLayout
                if (layout != null && layout.layoutInput.text.text == text && pinchAnchor == null && editorFocused) {
                    val rect = layout.getCursorRect(editor.selection.end.coerceIn(0, text.length))
                    val topMargin = with(density) { 12.dp.toPx() }
                    val bottomMargin = with(density) { (if (imeBottom > 0) 70.dp else 12.dp).toPx() }
                    val visibleTop = rect.top + textTop - editorScroll.value
                    val visibleBottom = rect.bottom + textTop - editorScroll.value
                    if (visibleTop < topMargin || visibleBottom > viewportHeight - bottomMargin) {
                        cursorVisibilityRequest = true
                        try {
                            cursorRequester.bringIntoView(rect.inflate(topMargin))
                        } finally {
                            cursorVisibilityRequest = false
                        }
                    }
                }
            }
        }
        LaunchedEffect(mdPreview, pendingAnchor) {
            val anchor = pendingAnchor ?: return@LaunchedEffect
            // Wait for the new mode to measure and publish source/block positions.
            settleAnchor(anchor, null)
            pendingAnchor = null
        }
        var discardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        val colors = if (selectedTheme == AppTheme.DARK) darkColorScheme(background = Color(0xFF17191F), surface = Color(0xFF20232B), surfaceVariant = Color(0xFF2A2E38), primary = Color(0xFF5E9FE8), onBackground = Color(0xFFF5F7FA), onSurface = Color(0xFFF5F7FA)) else lightColorScheme(background = Color(0xFFFAFAFC), surface = Color.White, surfaceVariant = Color(0xFFECEEF3), primary = Color(0xFF236DD1), onBackground = Color(0xFF1B1D22), onSurface = Color(0xFF1B1D22))
        // Reset the per-session editor surface after the ViewModel switches to a
        // different tab, opens a file, or creates one.
        var pendingScrollY by remember { mutableStateOf<Int?>(null) }
        // onPause runs outside the composition, so the live offsets are handed
        // to the Activity through this hook; it is a plain write, no state.
        SideEffect {
            saveScrollPosition = {
                vm.setScrollY(if (mdPreview) previewScroll.value else editorScroll.value)
            }
        }
        fun onNewDocumentSession(newTitle: String) {
            finding = false; query = ""; matchIndex = 0
            // The tab owns its preview mode now; beginSession only applies the
            // extension-based default the first time a document is opened.
            vm.beginSession(isMarkdownName(newTitle))
            previewAnchors.clear()
            pendingScrollY = vm.scrollYFor(vm.activeTabId)
            dirSession++
        }
        fun selectTab(id: String) {
            // While the Recent page is up, tapping the tab of the document that
            // is already active must still bring it back on screen.
            if (id == vm.activeTabId && !vm.home) return
            saveScrollPosition()
            vm.switchTab(id)
        }
        fun requestCloseTab(id: String) {
            val tab = vm.tabs.firstOrNull { it.id == id } ?: return
            val action: () -> Unit = {
                val uri = tab.uri
                val sharedWithAnotherTab = uri != null && vm.tabs.count { it.id != id && it.uri == uri } > 0
                vm.closeTab(id)
                // Persistable grants are a limited resource (~128 per app), but a
                // Recent row needs its grant to outlive the tab that opened it:
                // releasing here made every row of that list fail to reopen.
                // The budget still holds -- Recent keeps 12 entries at most, and
                // an evicted entry gives its grant back (see rememberRecent).
                if (uri != null && !sharedWithAnotherTab &&
                    recentFiles.none { it.uri == uri.toString() }
                ) releasePermission(uri)
            }
            if (tab.isDirty) { discardAction = action; showDiscardDialog = true } else action()
        }
        fun newFile(markdown: Boolean) {
            // With nothing open, activeTab is a detached placeholder and every
            // field of it looks "untouched", so the reuse rule used to swallow
            // the tap: New was a dead button on the Recent page.
            val current = vm.tabs.firstOrNull { it.id == vm.activeTabId }
            val untouched = current != null && current.uri == null && current.editor.text.isEmpty() &&
                !current.isDirty &&
                current.title == (if (markdown) EditorViewModel.UNTOOLED_MD else EditorViewModel.UNTOOLED_TXT)
            if (untouched) {
                // A blank document is already behind Recent: go back to it
                // instead of stacking an identical tab.
                vm.showDocument()
                return
            }
            // No warning here: "New" adds a tab and leaves the current one
            // intact, so asking about discarding text was a lie.
            if (!vm.newDocument(markdown)) {
                notifyMessage(getString(if (vm.tabs.size >= TabBudget.MAX_TABS) R.string.tab_limit_reached else R.string.tab_memory_reached))
            }
        }
        fun setLang(code: String) {
            if (code == lang) return
            prefs.edit().putString("lang", code).apply()
            lang = code
            recreate()
        }
        fun chooseOnboardingLanguage(code: String) {
            val languageChanged = code != lang
            prefs.edit()
                .putString("lang", code)
                .putBoolean("onboarding_language_chosen", true)
                .apply()
            // Update these before a Persian resource recreation. The next
            // activity reads the language freshly from preferences.
            lang = code
            showOnboardingLanguage = false
            onboardingStep = 0
            showOnboarding = true
            if (languageChanged) {
                // attachBaseContext reloads the localized resources after this.
                recreate()
            }
        }
        fun finishOnboarding() {
            prefs.edit().putBoolean("onboarding_complete", true).apply()
            showOnboarding = false
        }
        fun replayOnboarding() {
            prefs.edit().putBoolean("onboarding_complete", false).apply()
            onboardingStep = 0
            showOnboarding = true
        }
        // The ViewModel bumps documentSession on every open/new, so this fires
        // exactly once per session change -- including a re-open of the file
        // already open, which a (title, uri) key would have missed.
        val documentSession by vm::documentSession
        // Skip when only the Activity was recreated (rotation, language): that
        // must keep Find, Preview mode and scroll position.
        LaunchedEffect(documentSession) {
            if (vm.surfaceSession != documentSession) { vm.surfaceSession = documentSession; onNewDocumentSession(vm.title) }
        }
        // Applied once the new text has been measured: a lazily hydrated tab
        // arrives with an empty buffer first, where a large scroll would clamp
        // to 0 and be lost.
        LaunchedEffect(documentSession, text, mdPreview) {
            val target = pendingScrollY ?: return@LaunchedEffect
            // A lazily hydrated tab shows an empty buffer first; keep the target
            // and wait for the real text instead of clamping it to zero.
            if (text.isEmpty()) return@LaunchedEffect
            withFrameNanos { }; withFrameNanos { }
            val scroll = if (mdPreview) previewScroll else editorScroll
            scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
            pendingScrollY = null
        }
        // Ask the editor surface to open Save As after a denied write.
        LaunchedEffect(Unit) { for (unit in saveAsFallback) { showFormatChoice = true } }
        // Suggest a writing direction only when the opened document clearly
        // mismatches the currently active direction. Tracked per opened
        // document session (not filename), so two files sharing a name each
        // get their own single suggestion. Manual choice is kept.
        LaunchedEffect(dirSession) {
            if (vm.directionCheckedSession == documentSession) return@LaunchedEffect
            vm.directionCheckedSession = documentSession
            if (text.isNotBlank()) {
                val detected = detectDirection(text)
                dirSuggest = if (detected != null && detected != docRtl) detected else null
            } else {
                dirSuggest = null
            }
        }
        val isLightTheme = selectedTheme == AppTheme.LIGHT
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = isLightTheme
                isAppearanceLightNavigationBars = isLightTheme
            }
        }
        LaunchedEffect(vm.home) {
            if (vm.home) {
                finding = false
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }
        MaterialTheme(colorScheme = colors) {
            val versionName = remember {
                try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.7.0" } catch (_: Exception) { "1.7.0" }
            }
            BackHandler {
                when {
                    finding -> finding = false
                    vm.home && vm.tabs.isNotEmpty() -> vm.showDocument()
                    else -> { saveScrollPosition(); vm.flushAutosave(); finish() }
                }
            }
            Scaffold(
                containerColor = colors.background,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    HeaderBar(
                        theme = selectedTheme,
                        setTheme = { selectedTheme = it; prefs.edit().putString("theme", if (it == AppTheme.LIGHT) "light" else "dark").apply() },
                        lang = lang,
                        setLang = ::setLang,
                        onDiscardRequest = { showDiscardConfirm = true },
                        isMarkdown = isMarkdownName(title),
                        previewing = mdPreview,
                        onTogglePreview = { switchMode() },
                        onDirRequest = { showDirMenu = true },
                        onHelpRequest = { showHelp = true },
                        onFind = { if (mdPreview) switchMode(); finding = true },
                        fontSize = fontSize,
                        onFontSize = ::applyFontSize,
                        tabs = vm.tabs,
                        activeTabId = vm.activeTabId,
                        homeActive = vm.home,
                        onSelectTab = ::selectTab,
                        onCloseTab = ::requestCloseTab,
                        onHome = { finding = false; vm.goHome(); focusManager.clearFocus() },
                        versionName = versionName,
                    )
                },
                contentWindowInsets = WindowInsets.safeDrawing
            ) { innerPadding ->
                Box(
                    Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
                        .onSizeChanged { viewportHeight = it.height }
                        .then(if (vm.home) Modifier.clearAndSetSemantics { } else Modifier)
                ) {
                val docDirection = if (docRtl) TextDirection.Rtl else TextDirection.Ltr
                val docAlign = if (docRtl) TextAlign.Right else TextAlign.Left
                if (isMarkdownName(title) && mdPreview) {
                    CompositionLocalProvider(LocalLayoutDirection provides if (docRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        MarkdownPreview(
                            markdown = text,
                            onLinkClick = ::openLink,
                            fontSize = fontSize,
                            scrollState = previewScroll, anchors = previewAnchors,
                            textAnchors = previewTextAnchors,
                            pinchScale = pinchVisualScale,
                            pinchCentroid = pinchCentroid,
                            modifier = Modifier.fillMaxSize().documentPinchZoom(zoomDocument, startPinch, endPinch)
                        )
                    }
                } else {
                    CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalClipboardManager provides editorClipboard,
                        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides object : androidx.compose.foundation.gestures.BringIntoViewSpec {
                            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                                // CoreTextField focus can request the OLD selection before
                                // the tap updates it. Only our settled cursor request may scroll.
                                if (!cursorVisibilityRequest || !editorFocused || pinchAnchor != null || pendingAnchor != null) return 0f
                                val layout = textLayout ?: return 0f
                                val cursor = layout.getCursorRect(editor.selection.end.coerceIn(0, text.length))
                                val topMargin = with(density) { 12.dp.toPx() }
                                val bottomMargin = with(density) { (if (imeBottom > 0) 70.dp else 12.dp).toPx() }
                                val top = cursor.top + textTop - editorScroll.value - topMargin
                                val bottom = cursor.bottom + textTop - editorScroll.value + bottomMargin
                                return when {
                                    top < 0f -> top
                                    bottom > containerSize -> bottom - containerSize
                                    else -> 0f
                                }
                            }
                        }
                    ) {
                    Box(Modifier.fillMaxSize().clipToBounds().documentPinchZoom(zoomDocument, startPinch, endPinch)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                editorFocus.requestFocus()
                                keyboardController?.show()
                            }
                        }
                        .verticalScroll(editorScroll).padding(horizontal = 20.dp)
                        // Room for the corner buttons: 5 stacked dots when closed,
                        // 1 compact horizontal row when the keyboard is open.
                        .padding(top = topPad, bottom = if (imeBottom == 0) 280.dp else 70.dp)) {
                    BasicTextField(
                        value = editor,
                        enabled = !vm.home,
                        onValueChange = { newValue ->
                            vm.onTextChanged(newValue, allowCoalescing = !pastedEdit)
                            pastedEdit = false
                            if (finding && matches.isNotEmpty() && newValue.text != text) matchIndex = 0
                        },
                        textStyle = TextStyle(color = colors.onBackground, fontSize = fontSize.sp, lineHeight = (fontSize * 1.5f).sp, textDirection = docDirection, textAlign = docAlign),
                        cursorBrush = SolidColor(colors.primary),
                        visualTransformation = remember(colors.primary, colors.surfaceVariant, fontSize, title, activeMatch) {
                            MarkdownSourceTransformation(
                                linkColor = colors.primary,
                                codeBg = colors.surfaceVariant,
                                baseFontSize = fontSize.sp,
                                markdown = isMarkdownName(title),
                                activeMatch = activeMatch
                            )
                        },
                        onTextLayout = { textLayout = it },
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(editorFocus)
                            .onFocusChanged { editorFocused = it.isFocused }
                            .bringIntoViewRequester(cursorRequester)
                            .onSizeChanged { sourceContentSize = it }
                            .graphicsLayer {
                                // BasicTextField is a very tall child inside a
                                // vertical scroll container.  Its local pivot
                                // must therefore include the current scroll
                                // amount, otherwise scaling appears to jump.
                                val width = sourceContentSize.width.toFloat().coerceAtLeast(1f)
                                val height = sourceContentSize.height.toFloat().coerceAtLeast(1f)
                                val pivotX = ((pinchCentroid.x - with(density) { 20.dp.toPx() }) / width)
                                    .coerceIn(0f, 1f)
                                val pivotY = ((editorScroll.value + pinchCentroid.y - textTop) / height)
                                    .coerceIn(0f, 1f)
                                scaleX = pinchVisualScale
                                scaleY = pinchVisualScale
                                transformOrigin = TransformOrigin(pivotX, pivotY)
                            }
                            .onGloballyPositioned {
                                val anchor = pinchAnchor
                                val layout = textLayout
                                if (anchor != null && layout != null && layout.layoutInput.text.text == text && lastPinchLayout !== layout) {
                                    lastPinchLayout = layout
                                    val rect = layout.getCursorRect(anchor.offset.coerceIn(0, text.length))
                                    restorePinch(rect.top + pinchLineFraction * rect.height + textTop)
                                }
                            },
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    stringResource(R.string.hint),
                                    color = colors.onSurfaceVariant,
                                    fontSize = fontSize.sp,
                                    textAlign = docAlign
                                )
                            }
                            innerTextField()
                        }
                    )
                    }
                    }
                }
                // The actions live in the corner, so nothing takes a full-width
                // strip from the text. They are hidden behind Find and Markdown
                // preview, where they would either cover the document or do
                // nothing useful.
                if (!finding && !mdPreview && !vm.home) {
                    FloatingActions(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 10.dp),
                        inRow = imeBottom > 0,
                        showDocuments = imeBottom == 0,
                        canUndo = historyTick >= 0 && history.canUndo,
                        canRedo = historyTick >= 0 && history.canRedo,
                        onSave = { vm.documentUri?.let { pendingSaveTabId = vm.activeTabId; saveToUri(it) } ?: run { showFormatChoice = true } },
                        onSaveAs = { history.breakCoalescing(); showFormatChoice = true },
                        onExport = { pendingSaveTabId = vm.activeTabId; exportDocument.launch(baseName(title) + ".txt") },
                        onNew = { newFile(false) },
                        onNewMarkdown = { newFile(true) },
                        onOpen = { openDocument.launch(arrayOf("text/plain", "text/markdown", "text/*")) },
                        undo = { vm.undo() },
                        redo = { vm.redo() },
                    )
                }
                Box(Modifier.align(Alignment.TopEnd).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (finding) FloatingFind(query, { query = it; matchIndex = 0 }, matches.size,
                        matchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0)),
                        { if (matches.isNotEmpty()) matchIndex = (matchIndex.coerceAtMost(matches.lastIndex) - 1 + matches.size) % matches.size },
                        { if (matches.isNotEmpty()) matchIndex = (matchIndex + 1) % matches.size },
                        { finding = false; query = ""; matchIndex = 0; focusManager.clearFocus() })

                }
                }
                // The Recent page covers the page rather than replacing it: the
                // document keeps its scroll, selection and text behind the list,
                // so coming back from it costs nothing.
                //
                // It is a sibling of the editor Box, not a child, so it has to
                // carry the window insets itself -- without this padding it
                // started at the top of the window and the whole title block sat
                // behind the header band.
                if (vm.home) RecentPage(
                    files = recentList(),
                    openUris = vm.tabs.mapNotNull { it.uri?.toString() }.toSet(),
                    onNew = { newFile(false) },
                    onOpen = { openDocument.launch(arrayOf("text/plain", "text/markdown", "text/*")) },
                    onFile = { openRecent(it) },
                    onForget = ::forgetRecent,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            }
            if (showDiscardDialog) AlertDialog(onDismissRequest = { showDiscardDialog = false }, title = { Text(stringResource(R.string.discard_title)) }, text = { Text(stringResource(R.string.discard_open)) }, confirmButton = { TextButton(onClick = { showDiscardDialog = false; discardAction?.invoke() }) { Text(stringResource(R.string.cont)) } }, dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text(stringResource(R.string.cancel)) } })
            if (showDiscardConfirm) AlertDialog(onDismissRequest = { showDiscardConfirm = false }, title = { Text(stringResource(R.string.discard_title)) }, text = { Text(stringResource(R.string.discard_clear)) }, confirmButton = { TextButton(onClick = { showDiscardConfirm = false; vm.discardActiveTab() }) { Text(stringResource(R.string.discard)) } }, dismissButton = { TextButton(onClick = { showDiscardConfirm = false }) { Text(stringResource(R.string.cancel)) } })
            if (dirSuggest != null) {
                val toRtl = dirSuggest == true
                AlertDialog(
                    onDismissRequest = { dirSuggest = null },
                    title = { Text(stringResource(if (toRtl) R.string.dir_sug_title_fa else R.string.dir_sug_title_en)) },
                    text = { Text(stringResource(if (toRtl) R.string.dir_sug_text_fa else R.string.dir_sug_text_en)) },
                    confirmButton = { TextButton(onClick = { vm.applyDirection(toRtl); dirSuggest = null }) { Text(stringResource(if (toRtl) R.string.dir_sug_go_rtl else R.string.dir_sug_go_ltr)) } },
                    dismissButton = { TextButton(onClick = { dirSuggest = null }) { Text(stringResource(if (toRtl) R.string.dir_sug_keep_ltr else R.string.dir_sug_keep_rtl)) } }
                )
            }
            if (showDirMenu) AlertDialog(
                onDismissRequest = { showDirMenu = false },
                title = { Text(stringResource(R.string.dir_menu)) },
                text = {
                    Column {
                        dirOption(stringResource(R.string.dir_ltr), !docRtl) { vm.applyDirection(false); showDirMenu = false }
                        dirOption(stringResource(R.string.dir_rtl), docRtl) { vm.applyDirection(true); showDirMenu = false }
                    }
                },
                confirmButton = { },
                dismissButton = { TextButton(onClick = { showDirMenu = false }) { Text(stringResource(R.string.cancel)) } }
            )
            if (showFormatChoice) AlertDialog(
                onDismissRequest = { showFormatChoice = false },
                title = { Text(stringResource(R.string.saveas_title)) },
                text = {
                    Column {
                        TextButton(onClick = { showFormatChoice = false; history.breakCoalescing(); pendingSaveTabId = vm.activeTabId; createTxtDocument.launch(baseName(title) + ".txt") }) { Text(stringResource(R.string.saveas_txt)) }
                        TextButton(onClick = { showFormatChoice = false; history.breakCoalescing(); pendingSaveTabId = vm.activeTabId; createMdDocument.launch(baseName(title) + ".md") }) { Text(stringResource(R.string.saveas_md)) }
                    }
                },
                confirmButton = { },
                dismissButton = { TextButton(onClick = { showFormatChoice = false }) { Text(stringResource(R.string.cancel)) } }
            )
            if (showHelp) HelpPage(
                isRtl = lang == "fa",
                isLight = isLightTheme,
                onClose = { showHelp = false },
                onReplayIntro = {
                    showHelp = false
                    replayOnboarding()
                }
            )
            if (showOnboardingLanguage) {
                OnboardingLanguageDialog(onLanguage = ::chooseOnboardingLanguage)
            } else if (showOnboarding) {
                OnboardingGuide(
                    language = lang,
                    step = onboardingStep,
                    onBack = { if (onboardingStep > 0) onboardingStep-- },
                    onSkip = ::finishOnboarding,
                    onNext = {
                        if (onboardingStep >= 3) finishOnboarding()
                        else onboardingStep++
                    }
                )
            }
        }
    }

    @Composable
    private fun dirOption(label: String, selected: Boolean, onSelect: () -> Unit) {
        Row(Modifier.fillMaxWidth().combinedClickable(role = Role.RadioButton, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }

}
