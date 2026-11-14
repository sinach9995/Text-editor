@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.asoraksh.hermeseditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Bundled Material-style vector paths: no icon font, emoji or added dependency.
internal enum class ControlIcon { Search, Previous, Next, Close, Undo, Redo, Back }
private fun vector(kind: ControlIcon): ImageVector = ImageVector.Builder(kind.name, 24.dp, 24.dp, 24f, 24f,
    autoMirror = kind == ControlIcon.Undo || kind == ControlIcon.Redo || kind == ControlIcon.Back).apply {
    path(fill = SolidColor(Color.Black)) {
        when (kind) {
            ControlIcon.Search -> { moveTo(9.5f,3f); arcToRelative(6.5f,6.5f,0f,true,false,3.98f,11.64f); lineTo(19.49f,20.65f); lineTo(20.9f,19.24f); lineTo(14.89f,13.23f); arcToRelative(6.5f,6.5f,0f,false,false,-5.39f,-10.23f); close(); moveTo(9.5f,5f); arcToRelative(4.5f,4.5f,0f,true,true,0f,9f); arcToRelative(4.5f,4.5f,0f,true,true,0f,-9f); close() }
            ControlIcon.Previous -> { moveTo(7f,14f); lineTo(12f,9f); lineTo(17f,14f); lineTo(18.4f,12.6f); lineTo(12f,6.2f); lineTo(5.6f,12.6f); close() }
            ControlIcon.Next -> { moveTo(7f,10f); lineTo(12f,15f); lineTo(17f,10f); lineTo(18.4f,11.4f); lineTo(12f,17.8f); lineTo(5.6f,11.4f); close() }
            ControlIcon.Close -> { moveTo(6f,4.6f); lineTo(12f,10.6f); lineTo(18f,4.6f); lineTo(19.4f,6f); lineTo(13.4f,12f); lineTo(19.4f,18f); lineTo(18f,19.4f); lineTo(12f,13.4f); lineTo(6f,19.4f); lineTo(4.6f,18f); lineTo(10.6f,12f); lineTo(4.6f,6f); close() }
            ControlIcon.Undo -> { moveTo(12.5f,8f); curveTo(9.85f,8f,7.45f,8.99f,5.6f,10.6f); lineTo(2f,7f); verticalLineTo(16f); horizontalLineTo(11f); lineTo(7.38f,12.38f); curveTo(8.77f,11.22f,10.54f,10.5f,12.5f,10.5f); curveTo(16.04f,10.5f,19.05f,12.81f,20.1f,16f); lineTo(22.47f,15.22f); curveTo(21.08f,11.03f,17.15f,8f,12.5f,8f); close() }
            ControlIcon.Redo -> { moveTo(18.4f,10.6f); curveTo(16.55f,8.99f,14.15f,8f,11.5f,8f); curveTo(6.85f,8f,2.92f,11.03f,1.53f,15.22f); lineTo(3.9f,16f); curveTo(4.95f,12.81f,7.96f,10.5f,11.5f,10.5f); curveTo(13.46f,10.5f,15.23f,11.22f,16.62f,12.38f); lineTo(13f,16f); horizontalLineTo(22f); verticalLineTo(7f); close() }
            ControlIcon.Back -> { moveTo(20f,11f); horizontalLineTo(7.83f); lineTo(13.42f,5.41f); lineTo(12f,4f); lineTo(4f,12f); lineTo(12f,20f); lineTo(13.42f,18.59f); lineTo(7.83f,13f); horizontalLineTo(20f); close() }
        }
    }
}.build()

@Composable internal fun ControlButton(kind: ControlIcon, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Icon(remember(kind) { vector(kind) }, contentDescription = label, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The document actions, as a column in the bottom-right corner instead of a
 * full-width bar along the bottom.
 *
 * A bar cost 60dp of height on every screen, all the time, for five actions that
 * are used in bursts. A column costs the right edge, where there is no text to
 * cover, and it is one-handed reach.
 *
 * The set is not fixed. While the keyboard is up, New and Open are hidden: they
 * are not things you do mid-sentence, and they were the two buttons that pushed
 * the column over the cursor. In landscape with the keyboard up there is no
 * vertical room left at all, so the three that remain lie down in a row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun FloatingActions(
    modifier: Modifier = Modifier,
    inRow: Boolean,
    showDocuments: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onExport: () -> Unit,
    onNew: () -> Unit,
    onNewMarkdown: () -> Unit,
    onOpen: () -> Unit,
    undo: () -> Unit,
    redo: () -> Unit,
) {
    var saveMenu by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    val gap = if (inRow) 10.dp else 8.dp
    // One list of buttons, laid out either as a column or as a row.
    val stack: @Composable () -> Unit = {
        Box {
            ActionDot(label = stringResource(R.string.ui_save), onClick = onSave, onLongClick = { saveMenu = true }) {
                LineIcon(LineIconKind.Save, MaterialTheme.colorScheme.primary, Modifier.size(22.dp))
            }
            // Save as / Export stay on a long press rather than taking two more
            // slots: the common action is one tap on a document you already own.
            DropdownMenu(saveMenu, onDismissRequest = { saveMenu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.ui_save_as)) }, onClick = { saveMenu = false; onSaveAs() })
                DropdownMenuItem({ Text(stringResource(R.string.ui_export_txt)) }, onClick = { saveMenu = false; onExport() })
            }
        }
        if (showDocuments) {
            Box {
                ActionDot(label = stringResource(R.string.ui_new), onClick = onNew, onLongClick = { newMenu = true }) {
                    LineIcon(LineIconKind.NewFile, MaterialTheme.colorScheme.primary, Modifier.size(22.dp))
                }
                DropdownMenu(newMenu, onDismissRequest = { newMenu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.new_txt)) }, onClick = { newMenu = false; onNew() })
                    DropdownMenuItem({ Text(stringResource(R.string.new_md)) }, onClick = { newMenu = false; onNewMarkdown() })
                }
            }
            ActionDot(label = stringResource(R.string.ui_open), onClick = onOpen) {
                LineIcon(LineIconKind.OpenFolder, MaterialTheme.colorScheme.primary, Modifier.size(22.dp))
            }
        }
        ActionDot(label = stringResource(R.string.undo), enabled = canUndo, onClick = undo) {
            Icon(remember { vector(ControlIcon.Undo) }, null, Modifier.size(20.dp), tint = tintFor(canUndo))
        }
        ActionDot(label = stringResource(R.string.redo), enabled = canRedo, onClick = redo) {
            Icon(remember { vector(ControlIcon.Redo) }, null, Modifier.size(20.dp), tint = tintFor(canRedo))
        }
    }
    // Order is deliberate: Save at the top of the stack and Redo at the bottom,
    // so the thumb falling on the corner hits Redo last and Save first.
    if (inRow) Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) { stack() }
    else Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) { stack() }
}

@Composable private fun tintFor(enabled: Boolean) =
    if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ActionDot(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** The three document icons, drawn as lines so they read as one family at 22dp. */
internal enum class LineIconKind { NewFile, OpenFolder, Save }

@Composable internal fun LineIcon(kind: LineIconKind, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val w = 1.7.dp.toPx()
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        fun poly(vararg points: Pair<Float, Float>) {
            val path = Path()
            points.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s)
            }
            drawPath(path, tint, style = stroke)
        }
        when (kind) {
            // Page with a folded corner, plus a plus at its foot.
            LineIconKind.NewFile -> {
                poly(13f, 3f, 5f, 3f, 5f, 21f, 13f, 21f, 13f, 9f, 13f, 3f)
                poly(13f, 3f, 17f, 7f, 13f, 7f)
                drawLine(tint, o(17.5f, 13.5f), o(17.5f, 20.5f), w, StrokeCap.Round)
                drawLine(tint, o(14f, 17f), o(21f, 17f), w, StrokeCap.Round)
            }
            // Folder with an open lid.
            LineIconKind.OpenFolder -> {
                poly(3f, 19f, 3f, 6f, 10f, 6f, 12f, 9f, 21f, 9f, 21f, 19f, 3f, 19f)
                poly(3f, 12f, 21f, 12f)
            }
            // Floppy: body, shutter and label.
            LineIconKind.Save -> {
                poly(4f, 4f, 16f, 4f, 20f, 8f, 20f, 20f, 4f, 20f, 4f, 4f)
                poly(8f, 4f, 8f, 9f, 15f, 9f, 15f, 4f)
                poly(8f, 20f, 8f, 14f, 16f, 14f, 16f, 20f)
            }
        }
    }
}

@Composable internal fun FloatingFind(query: String, change: (String) -> Unit, count: Int, index: Int,
                                     previous: () -> Unit, next: () -> Unit, close: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp, tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(remember { vector(ControlIcon.Search) }, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BasicTextField(query, change, singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, textDirection = TextDirection.Content),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).heightIn(min = 36.dp).focusRequester(focus),
                decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(stringResource(R.string.find_query), maxLines = 1, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                } })
            val resultLabel = if (count == 0) stringResource(R.string.no_matches)
                else stringResource(R.string.match_count, index + 1, count)
            TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(resultLabel) } }, state = rememberTooltipState()) {
                Text(stringResource(R.string.match_count, if (count == 0) 0 else index + 1, count),
                    color = if (count == 0 && query.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp).semantics { contentDescription = resultLabel })
            }
            ControlButton(ControlIcon.Previous, stringResource(R.string.previous_match), count > 0, previous)
            ControlButton(ControlIcon.Next, stringResource(R.string.next_match), count > 0, next)
            ControlButton(ControlIcon.Close, stringResource(R.string.close), onClick = close)
        }
    }
}
