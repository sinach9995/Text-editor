package com.asoraksh.hermeseditor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class AppTheme { DARK, LIGHT }

/** Single 52dp header band housing the home icon, open tab strip, and corner menu. */
@Composable
internal fun HeaderBar(
    theme: AppTheme,
    setTheme: (AppTheme) -> Unit,
    lang: String,
    setLang: (String) -> Unit,
    onDiscardRequest: () -> Unit,
    isMarkdown: Boolean,
    previewing: Boolean,
    onTogglePreview: () -> Unit,
    onDirRequest: () -> Unit,
    onHelpRequest: () -> Unit,
    onFind: () -> Unit,
    fontSize: Float,
    onFontSize: (Float) -> Unit,
    tabs: List<TabState>,
    activeTabId: String,
    homeActive: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onHome: () -> Unit,
    versionName: String,
) {
    var expanded by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val homeLabel = stringResource(R.string.recent_tab)
    val iconTint = if (theme == AppTheme.DARK) Color(0xFFF5F7FA) else Color(0xFF1B1D22)
    val showingDoc = !homeActive && tabs.isNotEmpty()
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(start = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier.align(Alignment.CenterVertically)
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .then(
                        if (homeActive) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
                        else Modifier
                    )
                    .clickable(onClick = onHome),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.hermes_editor_icon),
                    contentDescription = homeLabel,
                    modifier = Modifier
                        .size(if (homeActive) 34.dp else 30.dp)
                        .clip(RoundedCornerShape(9.dp)),
                )
            }
            Spacer(Modifier.width(6.dp))
            TabStrip(
                tabs = tabs,
                activeId = activeTabId,
                homeActive = homeActive,
                onSelect = onSelectTab,
                onClose = onCloseTab,
                modifier = Modifier.weight(1f),
            )
            CornerOverflow(
                expanded = expanded,
                onOpen = { expanded = true },
                onDismiss = { expanded = false },
                modifier = Modifier.align(Alignment.CenterVertically),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (theme == AppTheme.DARK) R.string.theme_to_light else R.string.theme_to_dark)) },
                    trailingIcon = {
                        Image(
                            painter = painterResource(id = if (theme == AppTheme.DARK) R.drawable.asora_theme_sun_minimal else R.drawable.asora_theme_moon_minimal),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            colorFilter = ColorFilter.tint(iconTint),
                        )
                    },
                    onClick = { setTheme(if (theme == AppTheme.DARK) AppTheme.LIGHT else AppTheme.DARK); expanded = false },
                )
                DropdownMenuItem(
                    text = { Text(if (lang == "fa") "English" else "فارسی") },
                    onClick = { setLang(if (lang == "fa") "en" else "fa"); expanded = false },
                )
                if (showingDoc) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.dir_menu)) }, onClick = { onDirRequest(); expanded = false })
                    if (isMarkdown) {
                        DropdownMenuItem({ Text(stringResource(if (previewing) R.string.edit_md else R.string.preview_md)) }, onClick = { onTogglePreview(); expanded = false })
                    }
                    DropdownMenuItem({ Text(stringResource(R.string.find)) }, onClick = { onFind(); expanded = false })
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.font_size), modifier = Modifier.weight(1f), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    FontStep("−", enabled = fontSize > 13f) { onFontSize(fontSize - 1f) }
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        Text("${fontSize.toInt()}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    FontStep("+", enabled = fontSize < 30f) { onFontSize(fontSize + 1f) }
                }
                if (showingDoc) {
                    DropdownMenuItem({ Text(stringResource(R.string.discard)) }, onClick = { onDiscardRequest(); expanded = false })
                }
                DropdownMenuItem({ Text(stringResource(R.string.help)) }, onClick = { onHelpRequest(); expanded = false })
                DropdownMenuItem({ Text(stringResource(R.string.about)) }, onClick = { about = true; expanded = false })
            }
        }
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Hermes Text Editor") },
            text = { Text(stringResource(R.string.about_body, versionName)) },
            confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun CornerOverflow(
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.() -> Unit,
) {
    val moreLabel = stringResource(R.string.more_menu)
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.size(44.dp).clickable(onClick = onOpen).semantics { contentDescription = moreLabel },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    repeat(3) {
                        Box(Modifier.size(3.5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = menu)
    }
}

/** Disabled at the 13sp/30sp bounds so tapping doesn't silently no-op. */
@Composable
private fun FontStep(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 18.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}
