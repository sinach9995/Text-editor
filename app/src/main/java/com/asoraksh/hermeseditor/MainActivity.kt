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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.util.Locale
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity

private enum class AppTheme { DARK, LIGHT }

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("hermes_editor", MODE_PRIVATE) }
    private var documentUri: Uri? = null
    private var updateEditor: ((String, String, Boolean) -> Unit)? = null
    private var pendingDocument: Pair<String, String>? = null
    private val messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private fun notifyMessage(message: String) { messages.trySend(message) }

    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("hermes_editor", Context.MODE_PRIVATE).getString("lang", "en") ?: "en"
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(Locale(lang))
        super.attachBaseContext(ContextWrapper(newBase.createConfigurationContext(config)))
    }

    private fun msg(en: String, fa: String) = if ((prefs.getString("lang", "en") ?: "en") == "fa") fa else en

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val text = contentResolver.openInputStream(it)?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() } ?: ""
                documentUri = it
                updateEditor?.invoke(text, displayName(it), true)
            } catch (error: Exception) { updateEditor?.invoke("", msg("Could not open file", "فایل باز نشد"), false) }
        }
    }
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { saveToUri(it) }
    }
    private val createTxtDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { saveAsToUri(it) }
    }
    private val createMdDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let { saveAsToUri(it) }
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            try {
                val plain = markdownToPlainText(prefs.getString("draft", "") ?: "")
                requireNotNull(contentResolver.openOutputStream(it, "wt")).bufferedWriter(Charsets.UTF_8).use { w -> w.write(plain) }
                notifyMessage(getString(R.string.exported, displayName(it)))
            } catch (_: Exception) { notifyMessage(getString(R.string.save_failed)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        setContent { HermesEditor() }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIncomingIntent(intent) }
    override fun onPause() { super.onPause(); cacheDraft() }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        try {
            // File-manager shares do not always grant persistent access; reading must still work.
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            documentUri = uri
            val document = text to displayName(uri)
            pendingDocument = document
            updateEditor?.invoke(document.first, document.second, true)
        } catch (_: Exception) { }
    }
    private fun saveToUri(uri: Uri) {
        val draft = prefs.getString("draft", "") ?: ""
        try {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(draft) } ?: throw IllegalStateException("unwritable")
            documentUri = uri
            updateEditor?.invoke(draft, displayName(uri), false)
            notifyMessage(getString(R.string.saved, displayName(uri)))
        } catch (_: Exception) { notifyMessage(getString(R.string.save_failed)) }
    }
    private fun saveAsToUri(uri: Uri) {
        val draft = prefs.getString("draft", "") ?: ""
        try {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(draft) } ?: throw IllegalStateException("unwritable")
            documentUri = uri
            updateEditor?.invoke(draft, displayName(uri), false)
            notifyMessage(getString(R.string.saved_copy, displayName(uri)))
        } catch (_: Exception) { notifyMessage(getString(R.string.save_failed)) }
    }
    private fun cacheDraft(text: String? = null) { prefs.edit().putString("draft", text ?: prefs.getString("draft", "") ?: "").apply() }
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
        var editor by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(prefs.getString("draft", "") ?: "")) }
        val text = editor.text
        val history = remember { EditorHistory() }
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
        var historyTick by remember { mutableStateOf(0) }
        val snackbar = remember { SnackbarHostState() }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        var fontSize by remember { mutableStateOf(prefs.getFloat("document_font_size", 17f).coerceIn(13f, 30f)) }
        var finding by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var matchIndex by remember { mutableStateOf(0) }
        val matches = remember(text, query) { findMatches(text, query) }
        val activeMatch = if (finding) matches.getOrNull(matchIndex.coerceAtMost((matches.size - 1).coerceAtLeast(0)))?.let { TextRange(it.first, it.last + 1) } else null
        val editorScroll = rememberScrollState()
        var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        val previewScroll = rememberScrollState()
        val previewAnchors = remember { PreviewAnchors() }
        val previewTextAnchors = remember { PreviewTextAnchors() }
        previewTextAnchors.onCorrection = { delta -> previewScroll.dispatchRawDelta(delta) }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val textTop = with(density) { 20.dp.toPx() }
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

        fun snapshot(value: TextFieldValue) = EditorSnapshot(value.text, value.selection.start, value.selection.end)
        fun restore(value: EditorSnapshot?) {
            if (value != null) { editor = TextFieldValue(value.text, TextRange(value.start, value.end)); cacheDraft(value.text); historyTick++ }
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
        var title by rememberSaveable { mutableStateOf("untitled.txt") }
        var selectedTheme by rememberSaveable { mutableStateOf(if (prefs.getString("theme", "dark") == "light") AppTheme.LIGHT else AppTheme.DARK) }
        var lang by rememberSaveable { mutableStateOf(prefs.getString("lang", "en") ?: "en") }
        var showDiscardDialog by remember { mutableStateOf(false) }
        var showDiscardConfirm by remember { mutableStateOf(false) }
        var showFormatChoice by remember { mutableStateOf(false) }
        var showHelp by remember { mutableStateOf(false) }
        // One guided tour replaces the old auto-dismissed Snackbar hints.
        // Language is chosen explicitly once so a Persian reader never has to
        // understand an English-only first-launch instruction.
        var showOnboardingLanguage by rememberSaveable {
            mutableStateOf(!prefs.getBoolean("onboarding_language_chosen", false))
        }
        var showOnboarding by rememberSaveable {
            mutableStateOf(
                prefs.getBoolean("onboarding_language_chosen", false) &&
                    !prefs.getBoolean("onboarding_complete", false)
            )
        }
        var onboardingStep by rememberSaveable { mutableStateOf(0) }
        var showDirMenu by remember { mutableStateOf(false) }
        var dirSuggest by remember { mutableStateOf<Boolean?>(null) }
        var dirSession by remember { mutableStateOf(0) }
        var docRtl by rememberSaveable { mutableStateOf(false) }
        var mdPreview by rememberSaveable { mutableStateOf(false) }
        fun captureAnchor(screen: Offset): DocumentAnchor = if (mdPreview)
            previewAnchors.capture(previewScroll.value + screen.y - previewTop, screen.y)
        else sourceAnchor(screen)
        fun switchMode() {
            pendingAnchor = captureAnchor(Offset(0f, textTop))
            previewAnchors.clear()
            mdPreview = !mdPreview
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
                    val margin = with(density) { 12.dp.toPx() }
                    val visibleTop = rect.top + textTop - editorScroll.value
                    val visibleBottom = rect.bottom + textTop - editorScroll.value
                    if (visibleTop < margin || visibleBottom > viewportHeight - margin) {
                        cursorVisibilityRequest = true
                        try {
                            cursorRequester.bringIntoView(rect.inflate(margin))
                        } finally {
                            cursorVisibilityRequest = false
                        }
                    }
                }
            }
        }
        LaunchedEffect(mdPreview, pendingAnchor) {
            val anchor = pendingAnchor ?: return@LaunchedEffect
            // Wait for the new font/mode to measure and publish source/block positions.
            withFrameNanos { }; withFrameNanos { }
            val y = if (mdPreview) previewAnchors.y(anchor.offset)?.plus(previewTop)
                else textLayout?.getCursorRect(anchor.offset.coerceIn(0, text.length))?.top?.plus(textTop)
            if (y != null) {
                val scroll = if (mdPreview) previewScroll else editorScroll
                scroll.scrollTo((y - anchor.screenY).toInt().coerceIn(0, scroll.maxValue))
            }
            pendingAnchor = null
        }
        var showLaunch by remember { mutableStateOf(true) }
        var discardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        val colors = if (selectedTheme == AppTheme.DARK) darkColorScheme(background = Color(0xFF17191F), surface = Color(0xFF20232B), surfaceVariant = Color(0xFF2A2E38), primary = Color(0xFF5E9FE8), onBackground = Color(0xFFF5F7FA), onSurface = Color(0xFFF5F7FA)) else lightColorScheme(background = Color(0xFFFAFAFC), surface = Color.White, surfaceVariant = Color(0xFFECEEF3), primary = Color(0xFF236DD1), onBackground = Color(0xFF1B1D22), onSurface = Color(0xFF1B1D22))
        fun update(newText: String, newTitle: String, newSession: Boolean) {
            title = newTitle; cacheDraft(newText)
            if (newSession) {
                editor = TextFieldValue(newText)
                history.reset(snapshot(editor)); historyTick++
                finding = false; query = ""; matchIndex = 0
                mdPreview = isMarkdownName(newTitle)
                previewAnchors.clear()
                pendingAnchor = DocumentAnchor(0, textTop)
                scope.launch { editorScroll.scrollTo(0); previewScroll.scrollTo(0) }
                dirSession++
            }
        }
        fun newFile(markdown: Boolean) {
            val action: () -> Unit = {
                documentUri = null
                update("", if (markdown) "untitled.md" else "untitled.txt", true)
                mdPreview = false
            }
            if (text.isNotEmpty()) { discardAction = action; showDiscardDialog = true } else action()
        }
        fun setLang(code: String) {
            if (code == lang) return
            prefs.edit().putString("lang", code).apply()
            lang = code
            recreate()
        }
        fun chooseOnboardingLanguage(code: String) {
            prefs.edit()
                .putString("lang", code)
                .putBoolean("onboarding_language_chosen", true)
                .apply()
            if (code != lang) {
                // attachBaseContext reloads the localized resources after this.
                recreate()
            } else {
                showOnboardingLanguage = false
                onboardingStep = 0
                showOnboarding = true
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
        LaunchedEffect(Unit) {
            updateEditor = ::update
            pendingDocument?.let { update(it.first, it.second, true); pendingDocument = null }
        }
        // Suggest a writing direction only when the opened document clearly
        // mismatches the currently active direction. Tracked per opened
        // document session (not filename), so two files sharing a name each
        // get their own single suggestion. Manual choice is kept.
        LaunchedEffect(dirSession) {
            if (text.isNotBlank()) {
                val detected = detectDirection(text)
                dirSuggest = if (detected != null && detected != docRtl) detected else null
            } else {
                dirSuggest = null
            }
        }
        DisposableEffect(Unit) { onDispose { updateEditor = null; cacheDraft(text) } }
        if (showLaunch) {
            LaunchFrame()
            LaunchedEffect(Unit) { showLaunch = false }
            return
        }
        MaterialTheme(colorScheme = colors) {
            BackHandler { cacheDraft(text); finish() }
            Scaffold(
                containerColor = colors.background,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    Column {
                    TopBar(
                        title = title,
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
                        onFind = { if (mdPreview) switchMode(); finding = true }
                    )
                    }
                },
                bottomBar = {
                    Column(Modifier.navigationBarsPadding().imePadding()) {
                    ActionBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        onNew = { newFile(false) },
                        onNewMarkdown = { newFile(true) },
                        onOpen = { val action = { openDocument.launch(arrayOf("text/plain", "text/markdown", "text/*")) }; if (text.isNotEmpty()) { discardAction = action; showDiscardDialog = true } else { action() } },
                        onQuickSave = { documentUri?.let(::saveToUri) ?: run { showFormatChoice = true } },
                        onSaveAs = { history.breakCoalescing(); showFormatChoice = true },
                        onExport = { exportDocument.launch(baseName(title) + ".txt") }
                    )
                    }
                },
                contentWindowInsets = WindowInsets.safeDrawing
            ) { innerPadding ->
                Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).onSizeChanged { viewportHeight = it.height }) {
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
                                val margin = with(density) { 12.dp.toPx() }
                                val top = cursor.top + textTop - editorScroll.value - margin
                                val bottom = cursor.bottom + textTop - editorScroll.value + margin
                                return when {
                                    top < 0f -> top
                                    bottom > containerSize -> bottom - containerSize
                                    else -> 0f
                                }
                            }
                        }
                    ) {
                    Box(Modifier.fillMaxSize().clipToBounds().documentPinchZoom(zoomDocument, startPinch, endPinch)
                        .verticalScroll(editorScroll).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 24.dp)) {
                    BasicTextField(
                        value = editor,
                        onValueChange = { newValue ->
                            history.record(snapshot(editor), snapshot(newValue), android.os.SystemClock.uptimeMillis(), allowCoalescing = !pastedEdit)
                            pastedEdit = false
                            editor = newValue
                            cacheDraft(newValue.text)
                            if (finding && matches.isNotEmpty() && newValue.text != text) matchIndex = 0
                        },
                        textStyle = TextStyle(color = colors.onBackground, fontSize = fontSize.sp, lineHeight = (fontSize * 1.5f).sp, textDirection = docDirection, textAlign = docAlign),
                        cursorBrush = SolidColor(colors.primary),
                        visualTransformation = MarkdownSourceTransformation(
                            linkColor = colors.primary,
                            codeBg = colors.surfaceVariant,
                            baseFontSize = fontSize.sp,
                            markdown = isMarkdownName(title),
                            activeMatch = activeMatch
                        ),
                        onTextLayout = { textLayout = it },
                        modifier = Modifier.fillMaxWidth()
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
                if (!finding && !mdPreview) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 4.dp)) {
                        FloatingHistory(historyTick >= 0 && history.canUndo, historyTick >= 0 && history.canRedo,
                            { restore(history.undo(snapshot(editor))) }, { restore(history.redo(snapshot(editor))) })
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (finding) FloatingFind(query, { query = it; matchIndex = 0 }, matches.size,
                        matchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0)),
                        { if (matches.isNotEmpty()) matchIndex = (matchIndex.coerceAtMost(matches.lastIndex) - 1 + matches.size) % matches.size },
                        { if (matches.isNotEmpty()) matchIndex = (matchIndex + 1) % matches.size },
                        { finding = false; query = ""; matchIndex = 0; focusManager.clearFocus() })

                }
                }
            }
            if (showDiscardDialog) AlertDialog(onDismissRequest = { showDiscardDialog = false }, title = { Text(stringResource(R.string.discard_title)) }, text = { Text(stringResource(R.string.discard_open)) }, confirmButton = { TextButton(onClick = { showDiscardDialog = false; discardAction?.invoke() }) { Text(stringResource(R.string.cont)) } }, dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text(stringResource(R.string.cancel)) } })
            if (showDiscardConfirm) AlertDialog(onDismissRequest = { showDiscardConfirm = false }, title = { Text(stringResource(R.string.discard_title)) }, text = { Text(stringResource(R.string.discard_clear)) }, confirmButton = { TextButton(onClick = { showDiscardConfirm = false; documentUri = null; update("", "untitled.txt", true) }) { Text(stringResource(R.string.discard)) } }, dismissButton = { TextButton(onClick = { showDiscardConfirm = false }) { Text(stringResource(R.string.cancel)) } })
            if (dirSuggest != null) {
                val toRtl = dirSuggest == true
                AlertDialog(
                    onDismissRequest = { dirSuggest = null },
                    title = { Text(stringResource(if (toRtl) R.string.dir_sug_title_fa else R.string.dir_sug_title_en)) },
                    text = { Text(stringResource(if (toRtl) R.string.dir_sug_text_fa else R.string.dir_sug_text_en)) },
                    confirmButton = { TextButton(onClick = { docRtl = toRtl; dirSuggest = null }) { Text(stringResource(if (toRtl) R.string.dir_sug_go_rtl else R.string.dir_sug_go_ltr)) } },
                    dismissButton = { TextButton(onClick = { dirSuggest = null }) { Text(stringResource(if (toRtl) R.string.dir_sug_keep_ltr else R.string.dir_sug_keep_rtl)) } }
                )
            }
            if (showDirMenu) AlertDialog(
                onDismissRequest = { showDirMenu = false },
                title = { Text(stringResource(R.string.dir_menu)) },
                text = {
                    Column {
                        dirOption(stringResource(R.string.dir_ltr), !docRtl) { docRtl = false; showDirMenu = false }
                        dirOption(stringResource(R.string.dir_rtl), docRtl) { docRtl = true; showDirMenu = false }
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
                        TextButton(onClick = { showFormatChoice = false; history.breakCoalescing(); createTxtDocument.launch(baseName(title) + ".txt") }) { Text(stringResource(R.string.saveas_txt)) }
                        TextButton(onClick = { showFormatChoice = false; history.breakCoalescing(); createMdDocument.launch(baseName(title) + ".md") }) { Text(stringResource(R.string.saveas_md)) }
                    }
                },
                confirmButton = { },
                dismissButton = { TextButton(onClick = { showFormatChoice = false }) { Text(stringResource(R.string.cancel)) } }
            )
            if (showHelp) HelpPage(
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

    @Composable private fun LanguageGlyph(modifier: Modifier = Modifier, tint: Color) {
        Canvas(modifier) {
            val r = size.minDimension / 2f
            val w = r * 0.16f
            drawCircle(tint, r - w, style = Stroke(w))
            drawLine(tint, Offset(center.x - r, center.y), Offset(center.x + r, center.y), w)
            drawOval(tint, Offset(center.x - r * 0.42f, center.y - r), Size(r * 0.84f, r * 2f), style = Stroke(w))
        }
    }

    @Composable private fun LaunchFrame() {
        Box(Modifier.fillMaxSize().background(colorResource(R.color.splash_background)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painter = painterResource(id = R.drawable.hermes_editor_icon), contentDescription = "Hermes Text Editor", modifier = Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)))
                Spacer(Modifier.height(20.dp))
                Text("Hermes Text Editor", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    @Composable private fun TopBar(title: String, theme: AppTheme, setTheme: (AppTheme) -> Unit, lang: String, setLang: (String) -> Unit, onDiscardRequest: () -> Unit, isMarkdown: Boolean, previewing: Boolean, onTogglePreview: () -> Unit, onDirRequest: () -> Unit, onHelpRequest: () -> Unit, onFind: () -> Unit) {
        var expanded by remember { mutableStateOf(false) }; var about by remember { mutableStateOf(false) }
        val iconTint = if (theme == AppTheme.DARK) Color(0xFFF5F7FA) else Color(0xFF1B1D22)
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) { Row(Modifier.fillMaxWidth().statusBarsPadding().height(72.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painter = painterResource(id = R.drawable.hermes_editor_icon), contentDescription = "Hermes Text Editor icon", modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text("Hermes Text Editor", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(title, maxLines = 1, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()), color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
            Box { IconButton(onClick = { expanded = true }) { Text("⋮", fontSize = 30.sp) }; DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (theme == AppTheme.DARK) R.string.theme_to_light else R.string.theme_to_dark)) },
                    leadingIcon = { Image(painter = painterResource(id = if (theme == AppTheme.DARK) R.drawable.asora_theme_sun_minimal else R.drawable.asora_theme_moon_minimal), contentDescription = null, modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(iconTint)) },
                    onClick = { setTheme(if (theme == AppTheme.DARK) AppTheme.LIGHT else AppTheme.DARK); expanded = false }
                )
                DropdownMenuItem(
                    text = { Text(if (lang == "fa") "English" else "فارسی") },
                    leadingIcon = { LanguageGlyph(modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface) },
                    onClick = { setLang(if (lang == "fa") "en" else "fa"); expanded = false }
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.dir_menu)) }, onClick = { onDirRequest(); expanded = false })
                if (isMarkdown) DropdownMenuItem({ Text(stringResource(if (previewing) R.string.edit_md else R.string.preview_md)) }, onClick = { onTogglePreview(); expanded = false })
                DropdownMenuItem({ Text(stringResource(R.string.find)) }, onClick = { onFind(); expanded = false })
                DropdownMenuItem({ Text(stringResource(R.string.discard)) }, onClick = { onDiscardRequest(); expanded = false })
                DropdownMenuItem({ Text(stringResource(R.string.help)) }, onClick = { onHelpRequest(); expanded = false })
                DropdownMenuItem({ Text(stringResource(R.string.about)) }, onClick = { about = true; expanded = false })
            } }
        } }
        if (about) {
            val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.2" } catch (_: Exception) { "1.2" }
            AlertDialog(onDismissRequest = { about = false }, title = { Text("Hermes Text Editor") }, text = { Text(stringResource(R.string.about_body, version)) }, confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.close)) } })
        }
    }

    @Composable private fun HelpPage(onClose: () -> Unit, onReplayIntro: () -> Unit) {
        val titles = stringArrayResource(R.array.help_titles)
        val bodies = stringArrayResource(R.array.help_bodies)
        // Keep the editor composed underneath so its selection and scroll survive.
        androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            CompositionLocalProvider(LocalLayoutDirection provides if (prefs.getString("lang", "en") == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ControlButton(ControlIcon.Back, stringResource(R.string.back), onClick = onClose)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.help), style = MaterialTheme.typography.titleLarge)
                        }
                        HorizontalDivider()
                        TextButton(
                            onClick = onReplayIntro,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text(stringResource(R.string.onboarding_replay)) }
                        HorizontalDivider()
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
                            titles.forEachIndexed { i, title ->
                                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text(bodies.getOrElse(i) { "" }, fontSize = 16.sp, lineHeight = 25.sp)
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun OnboardingLanguageDialog(onLanguage: (String) -> Unit) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Choose language / زبان را انتخاب کنید",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { onLanguage("en") }) { Text("English") }
                        Button(onClick = { onLanguage("fa") }) { Text("فارسی") }
                    }
                }
            }
        }
    }

    @Composable
    private fun OnboardingGuide(
        language: String,
        step: Int,
        onBack: () -> Unit,
        onSkip: () -> Unit,
        onNext: () -> Unit
    ) {
        val safeStep = step.coerceIn(0, 3)
        val title = when (safeStep) {
            0 -> stringResource(R.string.onboarding_new_title)
            1 -> stringResource(R.string.onboarding_save_title)
            2 -> stringResource(R.string.onboarding_history_title)
            else -> stringResource(R.string.onboarding_zoom_title)
        }
        val body = when (safeStep) {
            0 -> stringResource(R.string.onboarding_new_body)
            1 -> stringResource(R.string.onboarding_save_body)
            2 -> stringResource(R.string.onboarding_history_body)
            else -> stringResource(R.string.onboarding_zoom_body)
        }
        val progress = if (language == "fa") {
            listOf("۱ از ۴", "۲ از ۴", "۳ از ۴", "۴ از ۴")[safeStep]
        } else {
            "${safeStep + 1} / 4"
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = onSkip,
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                // Keep this upper card well clear of floating Undo/Redo and
                // the bottom New/Open/Save controls it describes.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 24.dp, top = 150.dp, end = 24.dp, bottom = 220.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column(Modifier.padding(24.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.onboarding_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    progress,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(body, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
                            Spacer(Modifier.height(20.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (safeStep > 0) {
                                    TextButton(onClick = onBack) { Text(stringResource(R.string.onboarding_back)) }
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip)) }
                                Spacer(Modifier.width(6.dp))
                                Button(onClick = onNext) {
                                    Text(
                                        stringResource(
                                            if (safeStep == 3) R.string.onboarding_done
                                            else R.string.onboarding_next
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun CombinedActionLabel(word: String) {
        // Center the label and arrow as a single compact group.  Keeping only
        // the word centered made the arrows look detached on a narrow phone.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(word, maxLines = 1)
                Spacer(Modifier.width(3.dp))
                Text("▲", fontSize = 11.sp, modifier = Modifier.offset(y = (-1).dp))
            }
        }
    }

    @Composable private fun ActionBar(modifier: Modifier, onNew: () -> Unit, onNewMarkdown: () -> Unit, onOpen: () -> Unit, onQuickSave: () -> Unit, onSaveAs: () -> Unit, onExport: () -> Unit) {
        var showSaveMenu by remember { mutableStateOf(false) }
        var showNewMenu by remember { mutableStateOf(false) }
        Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(52.dp)) {
                    Surface(shape = ButtonDefaults.shape, color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxSize().combinedClickable(role = Role.Button, onClick = onNew, onLongClick = { showNewMenu = true })) {
                        CombinedActionLabel(stringResource(R.string.ui_new))
                    }
                    DropdownMenu(expanded = showNewMenu, onDismissRequest = { showNewMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.new_txt)) }, onClick = { showNewMenu = false; onNew() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.new_md)) }, onClick = { showNewMenu = false; onNewMarkdown() })
                    }
                }
                Button(onClick = onOpen, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.ui_open)) }
                Box(Modifier.weight(1f).height(52.dp)) {
                    val btnColors = ButtonDefaults.buttonColors()
                    Surface(
                        shape = ButtonDefaults.shape,
                        color = btnColors.containerColor,
                        contentColor = btnColors.contentColor,
                        modifier = Modifier.fillMaxSize().combinedClickable(role = Role.Button, onClickLabel = stringResource(R.string.ui_save), onClick = onQuickSave, onLongClick = { showSaveMenu = true })
                    ) {
                        CombinedActionLabel(stringResource(R.string.ui_save))
                    }
                    DropdownMenu(expanded = showSaveMenu, onDismissRequest = { showSaveMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_save_as)) }, onClick = { showSaveMenu = false; onSaveAs() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_export_txt)) }, onClick = { showSaveMenu = false; onExport() })
                    }
                }
            }
        }
    }
}
