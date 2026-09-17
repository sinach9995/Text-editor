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
        var historyTick by remember { mutableStateOf(0) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var fontSize by remember { mutableStateOf(prefs.getFloat("document_font_size", 17f).coerceIn(13f, 30f)) }
        val zoomDocument: (Float) -> Unit = { factor ->
            if (factor.isFinite() && factor > 0f) {
                fontSize = (fontSize * factor).coerceIn(13f, 30f)
                prefs.edit().putFloat("document_font_size", fontSize).apply()
            }
        }
        var finding by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var matchIndex by remember { mutableStateOf(0) }
        val matches = remember(text, query) { findMatches(text, query) }
        val activeMatch = if (finding) matches.getOrNull(matchIndex.coerceAtMost((matches.size - 1).coerceAtLeast(0)))?.let { TextRange(it.first, it.last + 1) } else null
        val editorScroll = rememberScrollState()
        var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        fun snapshot(value: TextFieldValue) = EditorSnapshot(value.text, value.selection.start, value.selection.end)
        fun restore(value: EditorSnapshot?) {
            if (value != null) { editor = TextFieldValue(value.text, TextRange(value.start, value.end)); cacheDraft(value.text); historyTick++ }
        }
        LaunchedEffect(Unit) { for (message in messages) { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message, duration = SnackbarDuration.Short) } }
        LaunchedEffect(Unit) {
            for ((key, resource) in listOf("zoom_hint_seen" to R.string.zoom_hint, "save_hint_seen" to R.string.save_hint)) {
                if (!prefs.getBoolean(key, false)) {
                    prefs.edit().putBoolean(key, true).apply()
                    snackbar.showSnackbar(getString(resource), duration = SnackbarDuration.Short)
                }
            }
        }
        LaunchedEffect(activeMatch, textLayout) {
            val match = activeMatch
            val layout = textLayout
            if (match != null && layout != null && layout.layoutInput.text.text == text) {
                val y = layout.getBoundingBox(match.start.coerceAtMost((text.length - 1).coerceAtLeast(0))).top.toInt()
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
        var showDirMenu by remember { mutableStateOf(false) }
        var dirSuggest by remember { mutableStateOf<Boolean?>(null) }
        var dirSession by remember { mutableStateOf(0) }
        var docRtl by rememberSaveable { mutableStateOf(false) }
        var mdPreview by rememberSaveable { mutableStateOf(false) }
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
                dirSession++
            }
        }
        fun setLang(code: String) {
            if (code == lang) return
            prefs.edit().putString("lang", code).apply()
            lang = code
            recreate()
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
                    TopBar(
                        title = title,
                        theme = selectedTheme,
                        setTheme = { selectedTheme = it; prefs.edit().putString("theme", if (it == AppTheme.LIGHT) "light" else "dark").apply() },
                        lang = lang,
                        setLang = ::setLang,
                        onDiscardRequest = { showDiscardConfirm = true },
                        isMarkdown = isMarkdownName(title),
                        previewing = mdPreview,
                        onTogglePreview = { mdPreview = !mdPreview },
                        onDirRequest = { showDirMenu = true },
                        onHelpRequest = { showHelp = true },
                        onFind = { finding = true; mdPreview = false }
                    )
                },
                bottomBar = {
                    Column(Modifier.navigationBarsPadding().imePadding()) {
                    if (!mdPreview) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        TextButton(onClick = { restore(history.undo(snapshot(editor))) }, enabled = historyTick >= 0 && history.canUndo) { Text(stringResource(R.string.undo)) }
                        TextButton(onClick = { restore(history.redo(snapshot(editor))) }, enabled = historyTick >= 0 && history.canRedo) { Text(stringResource(R.string.redo)) }
                    }
                    ActionBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        onNew = { val action = { documentUri = null; update("", "untitled.txt", true) }; if (text.isNotEmpty()) { discardAction = action; showDiscardDialog = true } else { action() } },
                        onOpen = { val action = { openDocument.launch(arrayOf("text/plain", "text/markdown", "text/*")) }; if (text.isNotEmpty()) { discardAction = action; showDiscardDialog = true } else { action() } },
                        onQuickSave = { documentUri?.let(::saveToUri) ?: run { showFormatChoice = true } },
                        onSaveAs = { showFormatChoice = true },
                        onExport = { exportDocument.launch(baseName(title) + ".txt") }
                    )
                    }
                },
                contentWindowInsets = WindowInsets.safeDrawing
            ) { innerPadding ->
                val docDirection = if (docRtl) TextDirection.Rtl else TextDirection.Ltr
                val docAlign = if (docRtl) TextAlign.Right else TextAlign.Left
                if (isMarkdownName(title) && mdPreview) {
                    CompositionLocalProvider(LocalLayoutDirection provides if (docRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        MarkdownPreview(
                            markdown = text,
                            onLinkClick = ::openLink,
                            fontSize = fontSize,
                            modifier = Modifier.fillMaxSize().padding(innerPadding).documentPinchZoom(zoomDocument)
                        )
                    }
                } else {
                    BasicTextField(
                        value = editor,
                        onValueChange = { newValue ->
                            history.record(snapshot(editor), snapshot(newValue), System.currentTimeMillis())
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
                        modifier = Modifier.fillMaxSize().padding(innerPadding).documentPinchZoom(zoomDocument).padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(editorScroll),
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
                        TextButton(onClick = { showFormatChoice = false; createTxtDocument.launch(baseName(title) + ".txt") }) { Text(stringResource(R.string.saveas_txt)) }
                        TextButton(onClick = { showFormatChoice = false; createMdDocument.launch(baseName(title) + ".md") }) { Text(stringResource(R.string.saveas_md)) }
                    }
                },
                confirmButton = { },
                dismissButton = { TextButton(onClick = { showFormatChoice = false }) { Text(stringResource(R.string.cancel)) } }
            )
            if (showHelp) HelpDialog { showHelp = false }
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

    @Composable private fun HelpDialog(onClose: () -> Unit) {
        val titles = stringArrayResource(R.array.help_titles)
        val bodies = stringArrayResource(R.array.help_bodies)
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.help)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    titles.forEachIndexed { i, t ->
                        Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(bodies.getOrElse(i) { "" }, fontSize = 14.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } }
        )
    }

    @Composable private fun ActionBar(modifier: Modifier, onNew: () -> Unit, onOpen: () -> Unit, onQuickSave: () -> Unit, onSaveAs: () -> Unit, onExport: () -> Unit) {
        var showSaveMenu by remember { mutableStateOf(false) }
        Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onNew, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.ui_new)) }
                Button(onClick = onOpen, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.ui_open)) }
                Box(Modifier.weight(1f).height(52.dp)) {
                    val btnColors = ButtonDefaults.buttonColors()
                    Surface(
                        shape = ButtonDefaults.shape,
                        color = btnColors.containerColor,
                        contentColor = btnColors.contentColor,
                        modifier = Modifier.fillMaxSize().combinedClickable(role = Role.Button, onClickLabel = stringResource(R.string.ui_save), onClick = onQuickSave, onLongClick = { showSaveMenu = true })
                    ) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ui_save))
                            Spacer(Modifier.width(6.dp))
                            Text("▲", fontSize = 14.sp)
                        }
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
