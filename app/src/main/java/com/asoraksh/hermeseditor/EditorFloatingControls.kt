@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.asoraksh.hermeseditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable internal fun FloatingHistory(canUndo: Boolean, canRedo: Boolean, undo: () -> Unit, redo: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 3.dp, tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(4.dp)) {
            ControlButton(ControlIcon.Undo, stringResource(R.string.undo), canUndo, undo)
            ControlButton(ControlIcon.Redo, stringResource(R.string.redo), canRedo, redo)
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
