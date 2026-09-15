package com.sinamirzaii.hermeseditor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

private enum class AppTheme { DARK, LIGHT }

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("hermes_editor", MODE_PRIVATE) }
    private var documentUri: Uri? = null
    private var updateEditor: ((String, String) -> Unit)? = null
    private var pendingDocument: Pair<String, String>? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val text = contentResolver.openInputStream(it)?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() } ?: ""
                documentUri = it
                updateEditor?.invoke(text, displayName(it))
            } catch (error: Exception) { updateEditor?.invoke("", "Could not open file") }
        }
    }
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { saveToUri(it) }
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
            updateEditor?.invoke(document.first, document.second)
        } catch (_: Exception) { }
    }
    private fun saveToUri(uri: Uri) {
        val draft = prefs.getString("draft", "") ?: ""
        try {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(draft) }
            documentUri = uri
            updateEditor?.invoke(draft, displayName(uri))
        } catch (_: Exception) { updateEditor?.invoke(draft, "Could not save file") }
    }
    private fun cacheDraft(text: String? = null) { prefs.edit().putString("draft", text ?: prefs.getString("draft", "") ?: "").apply() }
    private fun displayName(uri: Uri) = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "Document.txt" } ?: "Document.txt"

    @Composable
    private fun HermesEditor() {
        var text by rememberSaveable { mutableStateOf(prefs.getString("draft", "") ?: "") }
        var title by rememberSaveable { mutableStateOf("untitled.txt") }
        var selectedTheme by rememberSaveable { mutableStateOf(if (prefs.getString("theme", "dark") == "light") AppTheme.LIGHT else AppTheme.DARK) }
        var showDiscardDialog by remember { mutableStateOf(false) }
        var showDiscardConfirm by remember { mutableStateOf(false) }
        var discardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        val colors = if (selectedTheme == AppTheme.DARK) darkColorScheme(background = Color(0xFF17191F), surface = Color(0xFF20232B), surfaceVariant = Color(0xFF2A2E38), primary = Color(0xFF5E9FE8), onBackground = Color(0xFFF5F7FA), onSurface = Color(0xFFF5F7FA)) else lightColorScheme(background = Color(0xFFFAFAFC), surface = Color.White, surfaceVariant = Color(0xFFECEEF3), primary = Color(0xFF236DD1), onBackground = Color(0xFF1B1D22), onSurface = Color(0xFF1B1D22))
        fun update(newText: String, newTitle: String) { text = newText; title = newTitle; cacheDraft(newText) }
        LaunchedEffect(Unit) {
            updateEditor = ::update
            pendingDocument?.let { update(it.first, it.second); pendingDocument = null }
        }
        DisposableEffect(Unit) { onDispose { updateEditor = null; cacheDraft(text) } }
        MaterialTheme(colorScheme = colors) {
            BackHandler { cacheDraft(text); finish() }
            Scaffold(
                containerColor = colors.background,
                topBar = { TopBar(title, selectedTheme, { selectedTheme = it; prefs.edit().putString("theme", if (it == AppTheme.LIGHT) "light" else "dark").apply() }, { showDiscardConfirm = true }) },
                bottomBar = {
                    ActionBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        onNew = { val action = { documentUri = null; update("", "untitled.txt") }; if (text.isNotEmpty()) { discardAction = action; showDiscardDialog = true } else { action() } },
                        onOpen = { val action = { openDocument.launch(arrayOf("text/plain", "text/markdown", "text/*")) }; if (text.isNotEmpty()) { discardAction = action; showDiscardDialog = true } else { action() } },
                        onSave = { documentUri?.let(::saveToUri) ?: createDocument.launch(if (title == "untitled.txt") "document.txt" else title) }
                    )
                },
                contentWindowInsets = WindowInsets.safeDrawing
            ) { innerPadding ->
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; cacheDraft(it) },
                    textStyle = TextStyle(color = colors.onBackground, fontSize = 17.sp, lineHeight = 26.sp),
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text(
                                "Start writing…",
                                color = colors.onSurfaceVariant,
                                fontSize = 17.sp
                            )
                        }
                        innerTextField()
                    }
                )
            }
            if (showDiscardDialog) AlertDialog(onDismissRequest = { showDiscardDialog = false }, title = { Text("Discard current text?") }, text = { Text("Your draft is safely cached, but this editor will be replaced by the selected action.") }, confirmButton = { TextButton(onClick = { showDiscardDialog = false; discardAction?.invoke() }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text("Cancel") } })
            if (showDiscardConfirm) AlertDialog(onDismissRequest = { showDiscardConfirm = false }, title = { Text("Discard current text?") }, text = { Text("This will clear the editor. Your last saved file will not be changed.") }, confirmButton = { TextButton(onClick = { showDiscardConfirm = false; documentUri = null; update("", "untitled.txt") }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { showDiscardConfirm = false }) { Text("Cancel") } })
        }
    }

    @Composable private fun TopBar(title: String, theme: AppTheme, setTheme: (AppTheme) -> Unit, onDiscardRequest: () -> Unit) {
        var expanded by remember { mutableStateOf(false) }; var about by remember { mutableStateOf(false) }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) { Row(Modifier.fillMaxWidth().statusBarsPadding().height(72.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painter = painterResource(id = R.drawable.hermes_editor_icon), contentDescription = "Hermes Text Editor icon", modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text("Hermes Text Editor", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
            Box { IconButton(onClick = { expanded = true }) { Text("⋮", fontSize = 30.sp) }; DropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text("Light theme") }, onClick = { setTheme(AppTheme.LIGHT); expanded = false }); DropdownMenuItem({ Text("Dark theme") }, onClick = { setTheme(AppTheme.DARK); expanded = false }); HorizontalDivider(); DropdownMenuItem({ Text("Discard") }, onClick = { onDiscardRequest(); expanded = false }); DropdownMenuItem({ Text("About") }, onClick = { about = true; expanded = false }) } }
        } }
        if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("Hermes Text Editor") }, text = { Text("Version 1.0.2\n\nCreated by Hermes Agent and Notion AI\nwith help, direction, and oversight by Sina Chaghamirza.") }, confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } })
    }

    @Composable private fun ActionBar(modifier: Modifier, onNew: () -> Unit, onOpen: () -> Unit, onSave: () -> Unit) = Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = onNew, modifier = Modifier.weight(1f).height(52.dp)) { Text("New") }; Button(onClick = onOpen, modifier = Modifier.weight(1f).height(52.dp)) { Text("Open") }; Button(onClick = onSave, modifier = Modifier.weight(1f).height(52.dp)) { Text("Save") } } }
}
