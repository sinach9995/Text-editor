package com.asoraksh.hermeseditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The home page: what the app shows when nothing is open, and what the app icon
 * returns to. It is a page behind the icon, not a tab in the strip, so the strip
 * keeps showing the open documents while this is on screen.
 *
 * The heading is a title block -- the app name, then the page name -- with a rule
 * across the page under it; only what is under the rule is this page's list. The
 * first cut began with a bare button row flush against the top band, which read
 * as a clipped page rather than a composed one.
 *
 * The list is every document open right now, then this app's own history of what
 * it has opened, newest first. Nothing here reaches outside the app to list files
 * it has never seen.
 */
@Composable
fun RecentPage(
    files: List<RecentFile>,
    openUris: Set<String>,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onFile: (RecentFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // Opaque on purpose: this page is drawn over a live document, and the
        // first cut left the editor's own placeholder showing through the text.
        modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow touches: the page covers a real editor, and a tap that fell
            // through moved the caret in a document nobody could see.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
            Text(
                text = stringResource(R.string.recent_app_name),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.recent_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))
        }
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        )
        Column(Modifier.weight(1f).padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RecentAction(stringResource(R.string.ui_new), primary = true, onClick = onNew, modifier = Modifier.weight(1f))
                RecentAction(stringResource(R.string.ui_open), primary = false, onClick = onOpen, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            // No explanatory sentence: the heading says what the page is, and an
            // empty list simply has nothing under the two buttons.
            if (files.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.uri }) { file ->
                        RecentRow(file = file, open = file.uri in openUris, onClick = { onFile(file) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentAction(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (primary) 0.9f else 0.35f)),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun RecentRow(file: RecentFile, open: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                text = file.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (open) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.recent_open_now),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}